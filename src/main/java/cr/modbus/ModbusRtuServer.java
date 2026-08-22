package cr.modbus;

import cr.Config;
import cr.core.RegisterBank;
import cr.protocol.Crc16;
import cr.protocol.TrafficLog;
import cr.serial.JSerialEndpoint;
import cr.serial.SerialEndpoint;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ModbusRtuServer implements Runnable, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(ModbusRtuServer.class.getName());
    private static final int READ_HOLDING = 3;
    private static final int READ_INPUT = 4;
    private static final int WRITE_SINGLE = 6;
    private static final int WRITE_MULTIPLE = 16;
    private final Config config;
    private final Map<Integer, RegisterBank> registers;
    private final ModbusTraffic traffic;
    private final Map<Integer, byte[]> pendingEchoResponses = new java.util.HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile SerialEndpoint activeSerial;

    public ModbusRtuServer(Config config, Map<Integer, RegisterBank> registers) {
        this(config, registers, new ModbusTraffic());
    }

    public ModbusRtuServer(Config config, Map<Integer, RegisterBank> registers, ModbusTraffic traffic) {
        this.config = config;
        this.registers = Map.copyOf(registers);
        this.traffic = traffic;
    }

    @Override
    public void run() {
        long backoffMillis = 1_000;
        while (running.get()) {
            try (SerialEndpoint serial = JSerialEndpoint.open(config.modbusSerial())) {
                activeSerial = serial;
                LOG.info(() -> "Modbus RTU serial connected: " + serial.description()
                        + ", units " + registers.keySet());
                backoffMillis = 1_000;
                serve(serial);
            } catch (Exception error) {
                if (running.get()) {
                    LOG.log(Level.WARNING, "Modbus RTU server failed; reconnecting", error);
                    sleep(backoffMillis);
                    backoffMillis = Math.min(backoffMillis * 2, 30_000);
                }
            } finally {
                activeSerial = null;
            }
        }
    }

    private void serve(SerialEndpoint serial) throws IOException {
        while (running.get()) {
            byte[] request = readFrame(serial);
            if (request == null) continue;
            if (isResponse(request)) {
                int unit = u(request[0]);
                traffic.recordResponse(unit);
                LOG.info(() -> TrafficLog.entry("modbus", "RX-RESPONSE-OBSERVED", request,
                        TrafficLog.modbusResponse(request)));
                continue;
            }
            LOG.info(() -> TrafficLog.entry("modbus", "RX-REQUEST", request,
                    TrafficLog.modbusRequest(request)));
            if (!Crc16.validModbusFrame(request)) {
                LOG.warning(() -> TrafficLog.entry("modbus", "RX-REJECTED", request,
                        "rejected Modbus RTU request: invalid CRC"));
                continue;
            }
            int unit = u(request[0]);
            traffic.recordRequest(unit);
            if (unit != 0 && !registers.containsKey(unit)) {
                LOG.warning(() -> TrafficLog.entry("modbus", "RX-IGNORED", request,
                        "ignored valid Modbus RTU request for unconfigured unit " + unit));
                continue;
            }
            byte[] response = handle(request);
            if (unit != 0 && response != null) {
                pendingEchoResponses.remove(unit);
                traffic.recordResponse(unit);
                LOG.info(() -> TrafficLog.entry("modbus", "TX-RESPONSE", response,
                        TrafficLog.modbusResponse(response)));
                serial.write(response);
            }
        }
    }

    private byte[] readFrame(SerialEndpoint serial) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(256);
        while (running.get() && frame.size() < 256) {
            int value = readByte(serial);
            if (value < 0) return frame.size() == 0 ? null : frame.toByteArray();
            frame.write(value);
            byte[] bytes = frame.toByteArray();
            if (completeFrame(bytes)) return bytes;
        }
        return frame.toByteArray();
    }

    static boolean completeFrame(byte[] frame) {
        if (frame.length < 5 || !Crc16.validModbusFrame(frame)) return false;
        int rawFunction = u(frame[1]);
        if ((rawFunction & 0x80) != 0) return frame.length == 5;
        int function = rawFunction & 0x7f;
        return switch (function) {
            case READ_HOLDING, READ_INPUT -> frame.length == 8
                    || (u(frame[2]) > 0 && (u(frame[2]) & 1) == 0
                    && frame.length == 5 + u(frame[2]));
            case WRITE_SINGLE -> frame.length == 8;
            case WRITE_MULTIPLE -> frame.length == 8
                    || (frame.length >= 9 && u(frame[6]) <= 246
                    && frame.length == 9 + u(frame[6]));
            default -> frame.length == 8;
        };
    }

    private boolean isResponse(byte[] frame) {
        if (!Crc16.validModbusFrame(frame)) return false;
        int unit = u(frame[0]);
        int rawFunction = u(frame[1]);
        if ((rawFunction & 0x80) != 0) {
            pendingEchoResponses.remove(unit);
            return true;
        }
        return switch (rawFunction) {
            case READ_HOLDING, READ_INPUT -> frame.length != 8;
            case WRITE_MULTIPLE -> frame.length == 8;
            case WRITE_SINGLE -> isEchoResponse(unit, frame);
            default -> false;
        };
    }

    private boolean isEchoResponse(int unit, byte[] frame) {
        byte[] request = pendingEchoResponses.remove(unit);
        if (request != null && Arrays.equals(request, frame)) return true;
        pendingEchoResponses.put(unit, Arrays.copyOf(frame, frame.length));
        return false;
    }

    byte[] handle(byte[] request) {
        int unit = u(request[0]);
        if (unit == 0) {
            int function = u(request[1]);
            if (function != WRITE_SINGLE && function != WRITE_MULTIPLE) return null;
            for (RegisterBank bank : registers.values()) handleForBank(request, bank);
            return null;
        }
        RegisterBank bank = registers.get(unit);
        if (bank == null) return null;
        return handleForBank(request, bank);
    }

    private byte[] handleForBank(byte[] request, RegisterBank registers) {
        int function = u(request[1]);
        try {
            return switch (function) {
                case READ_HOLDING -> readRegisters(request, false, registers);
                case READ_INPUT -> readRegisters(request, true, registers);
                case WRITE_SINGLE -> writeSingle(request, registers);
                case WRITE_MULTIPLE -> writeMultiple(request, registers);
                default -> exception(request, 1);
            };
        } catch (IllegalStateException notReady) {
            LOG.fine(() -> "Modbus write deferred: " + notReady.getMessage());
            return exception(request, 6);
        } catch (IllegalArgumentException badAddressOrValue) {
            LOG.fine(() -> "Modbus request rejected: " + badAddressOrValue.getMessage());
            return exception(request, 3);
        } catch (RuntimeException error) {
            LOG.log(Level.WARNING, "Modbus request processing failed", error);
            return exception(request, 4);
        }
    }

    private byte[] readRegisters(byte[] request, boolean input, RegisterBank registers) {
        int address = word(request, 2);
        int count = word(request, 4);
        if (count < 1 || count > 125) throw new IllegalArgumentException("invalid register count");
        int[] values = input ? registers.readInput(address, count) : registers.readHolding(address, count);
        byte[] payload = new byte[3 + values.length * 2];
        payload[0] = request[0];
        payload[1] = request[1];
        payload[2] = (byte) (values.length * 2);
        for (int i = 0; i < values.length; i++) putWord(payload, 3 + i * 2, values[i]);
        return Crc16.appendModbusCrc(payload);
    }

    private byte[] writeSingle(byte[] request, RegisterBank registers) {
        int address = word(request, 2);
        registers.writeHolding(address, new int[]{word(request, 4)});
        return Arrays.copyOf(request, request.length);
    }

    private byte[] writeMultiple(byte[] request, RegisterBank registers) {
        int address = word(request, 2);
        int count = word(request, 4);
        int byteCount = u(request[6]);
        if (count < 1 || count > 123 || byteCount != count * 2 || request.length != 9 + byteCount) {
            throw new IllegalArgumentException("invalid multiple-register request");
        }
        int[] values = new int[count];
        for (int i = 0; i < count; i++) values[i] = word(request, 7 + i * 2);
        registers.writeHolding(address, values);
        byte[] payload = Arrays.copyOf(request, 6);
        return Crc16.appendModbusCrc(payload);
    }

    private byte[] exception(byte[] request, int code) {
        return Crc16.appendModbusCrc(new byte[]{request[0], (byte) (u(request[1]) | 0x80), (byte) code});
    }

    private int readByte(SerialEndpoint serial) throws IOException {
        byte[] value = new byte[1];
        int count = serial.read(value, 0, 1);
        if (count == 1) {
            LOG.info(() -> TrafficLog.entry("modbus", "RX", value,
                    TrafficLog.rawMeaning("Modbus RTU")));
        }
        return count == 1 ? u(value[0]) : -1;
    }

    private static int word(byte[] data, int offset) {
        return (u(data[offset]) << 8) | u(data[offset + 1]);
    }

    private static void putWord(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

    private static int u(byte value) {
        return Byte.toUnsignedInt(value);
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
