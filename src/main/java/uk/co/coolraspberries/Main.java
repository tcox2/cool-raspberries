package uk.co.coolraspberries;

import uk.co.coolraspberries.ac.AcWorker;
import uk.co.coolraspberries.core.RegisterBank;
import uk.co.coolraspberries.modbus.ModbusRtuServer;
import uk.co.coolraspberries.web.WebServer;

import java.nio.file.Path;
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
        RegisterBank registers = new RegisterBank(config.staleAfter());
        AcWorker acWorker = new AcWorker(config, registers);
        ModbusRtuServer modbusServer = new ModbusRtuServer(config, registers);
        WebServer webServer = new WebServer(config, registers);
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
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
            acWorker.close();
            modbusServer.close();
            workers.shutdownNow();
        };
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "shutdown"));

        completion.submit(() -> {
            acWorker.run();
            return null;
        });
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
