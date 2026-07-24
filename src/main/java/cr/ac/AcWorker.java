package cr.ac;

import cr.Config;
import cr.core.RegisterBank;
import cr.protocol.AcFrameDecoder;
import cr.protocol.Crc16;
import cr.serial.JSerialEndpoint;
import cr.serial.SerialEndpoint;


import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AcWorker implements Runnable, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(AcWorker.class.getName());
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(60);
    private final Config config;
    private final RegisterBank registers;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile SerialEndpoint activeSerial;
    private byte[] lastA3;

    public AcWorker(Config config, RegisterBank registers) {
        this.config = config;
        this.registers = registers;
    }

    @Override
    public void run() {
        long backoffMillis = 1_000;
        while (running.get()) {
            try (SerialEndpoint serial = JSerialEndpoint.open(config.acSerial())) {
                activeSerial = serial;
                LOG.info(() -> "AC serial connected: " + serial.description());
                backoffMillis = 1_000;
                process(serial);
            } catch (Exception error) {
                if (running.get()) {
                    LOG.log(Level.WARNING, "AC serial worker failed; reconnecting", error);
                    sleep(backoffMillis);
                    backoffMillis = Math.min(backoffMillis * 2, 30_000);
                }
            } finally {
                activeSerial = null;
            }
        }
    }

    private void process(SerialEndpoint serial) throws IOException {
        AcFrameDecoder decoder = new AcFrameDecoder();
        byte[] one = new byte[1];
        Instant nextHeartbeat = Instant.now().plus(HEARTBEAT_INTERVAL);
        while (running.get()) {
            int count = serial.read(one, 0, 1);
            if (count == 1) {
                decoder.accept(Byte.toUnsignedInt(one[0])).ifPresent(this::handleFrame);
            }
            Instant now = Instant.now();
            if (!now.isBefore(nextHeartbeat)) {
                serial.write(AcProtocol.heartbeat());
                nextHeartbeat = now.plus(HEARTBEAT_INTERVAL);
            }
            if (lastA3 != null && registers.consumeControlsDirty()) {
                byte[] frame = AcProtocol.configuration(lastA3, registers.controlsSnapshot(), config.controllerMac());
                serial.write(frame);
                LOG.fine(() -> "sent A1 control frame " + HexFormat.ofDelimiter(" ").formatHex(frame));
            }
        }
    }

    private void handleFrame(byte[] frame) {
        if (!AcProtocol.hasEnvelope(frame) || !Crc16.validAcFrame(frame)) {
            registers.recordCrcError();
            LOG.fine(() -> "rejected invalid AC frame " + HexFormat.ofDelimiter(" ").formatHex(frame));
            return;
        }
        switch (AcProtocol.frameType(frame)) {
            case AcProtocol.TYPE_A3 -> {
                if (frame.length != 34) {
                    LOG.warning("rejected A3 with unexpected length " + frame.length);
                    return;
                }
                lastA3 = AcProtocol.copyA3(frame);
                registers.updateFromA3(frame);
            }
            case AcProtocol.TYPE_A4 -> {
                if (frame.length != 13) {
                    LOG.warning("rejected A4 with unexpected length " + frame.length);
                    return;
                }
                int raw = Byte.toUnsignedInt(frame[10]);
                int state = switch (raw) {
                    case 0 -> 1;
                    case 1 -> 0;
                    case 0xA5 -> 2;
                    default -> 0xFFFF;
                };
                registers.updateRemoteState(state);
            }
            default -> LOG.finer(() -> "ignored valid AC frame type "
                    + Integer.toHexString(AcProtocol.frameType(frame)));
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    @Override
    public void close() {
        running.set(false);
        SerialEndpoint serial = activeSerial;
        if (serial != null) serial.close();
    }
}
