package cr;

import cr.ac.AcWorker;
import cr.core.RegisterBank;
import cr.modbus.ModbusRtuServer;
import cr.web.WebServer;


import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {}

    public static void main(String[] args) {
        Path configPath = Path.of(args.length == 0 ? "/etc/cool-raspberries/gateway.properties" : args[0]);
        try {
            Config config = Config.load(configPath);
            Logging.configure(config);
            run(config);
        } catch (Exception fatal) {
            System.err.println("cool-raspberries failed to start: " + fatal.getMessage());
            fatal.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(Config config) throws Exception {
        Map<String, RegisterBank> registersById = new LinkedHashMap<>();
        Map<Integer, RegisterBank> registersByUnit = new LinkedHashMap<>();
        List<AcWorker> acWorkers = new ArrayList<>();
        for (Config.AirConditioner ac : config.airConditioners()) {
            RegisterBank registers = new RegisterBank(ac.staleAfter());
            registersById.put(ac.id(), registers);
            registersByUnit.put(ac.modbusUnitId(), registers);
            acWorkers.add(new AcWorker(ac, registers));
        }
        ModbusRtuServer modbusServer = new ModbusRtuServer(config, registersByUnit);
        WebServer webServer = new WebServer(config, registersById);
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(acWorkers.size() + 1, runnable -> {
            Thread thread = new Thread(runnable, "serial-worker-" + threadNumber.incrementAndGet());
            thread.setDaemon(false);
            thread.setUncaughtExceptionHandler((ignored, error) ->
                    LOG.log(Level.SEVERE, "worker terminated unexpectedly", error));
            return thread;
        });
        ExecutorCompletionService<Void> completion = new ExecutorCompletionService<>(workers);
        AtomicBoolean stopping = new AtomicBoolean();

        Runnable shutdown = () -> {
            if (!stopping.compareAndSet(false, true)) return;
            LOG.info("shutdown requested");
            webServer.close();
            acWorkers.forEach(AcWorker::close);
            modbusServer.close();
            workers.shutdownNow();
        };
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "shutdown"));

        for (AcWorker acWorker : acWorkers) {
            completion.submit(() -> {
                acWorker.run();
                return null;
            });
        }
        completion.submit(() -> {
            modbusServer.run();
            return null;
        });
        webServer.start();
        LOG.info("cool-raspberries started");
        try {
            Future<Void> firstStopped = completion.take();
            firstStopped.get();
            if (!stopping.get()) throw new IllegalStateException("a serial worker stopped unexpectedly");
        } catch (ExecutionException failedWorker) {
            if (!stopping.get()) throw new IllegalStateException("a serial worker failed", failedWorker.getCause());
        } finally {
            shutdown.run();
        }
    }
}
