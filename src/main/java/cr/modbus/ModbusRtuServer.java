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
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile SerialEndpoint activeSerial;

    public ModbusRtuServer(Config config, Map<Integer, RegisterBank> registers) {
        this.config = config;
        this.registers = Map.copyOf(registers);
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
            byte[] request = readRequest(serial);
            if (request == null) continue;
            LOG.info(() -> TrafficLog.entry("modbus", "RX-REQUEST", request,
                    TrafficLog.modbusRequest(request)));
            if (!Crc16.validModbusFrame(request)) {
                LOG.warning(() -> TrafficLog.entry("modbus", "RX-REJECTED", request,
                        "rejected Modbus RTU request: invalid CRC"));
                continue;
            }
            int unit = u(request[0]);
            if (unit != 0 && !registers.containsKey(unit)) {
                LOG.warning(() -> TrafficLog.entry("modbus", "RX-IGNORED", request,
                        "ignored valid Modbus RTU request for unconfigured unit " + unit));
                continue;
            }
            byte[] response = handle(request);
            if (unit != 0 && response != null) {
                LOG.info(() -> TrafficLog.entry("modbus", "TX-RESPONSE", response,
                        TrafficLog.modbusResponse(response)));
                serial.write(response);
            }
        }
    }

    private byte[] readRequest(SerialEndpoint serial) throws IOException {
        int unit = readByte(serial);
        if (unit < 0) return null;
        int function = readByte(serial);
        if (function < 0) return null;

        ByteArrayOutputStream request = new ByteArrayOutputStream(256);
        request.write(unit);
        request.write(function);
        int remainder;
        if (function == WRITE_MULTIPLE) {
            byte[] headerTail = readExact(serial, 5);
            if (headerTail == null) return null;
            request.writeBytes(headerTail);
            int byteCount = u(headerTail[4]);
            if (byteCount > RegisterBank.REGISTER_COUNT * 2) return null;
            remainder = byteCount + 2;
        } else {
            remainder = 6;
        }
        byte[] tail = readExact(serial, remainder);
        if (tail == null) return null;
        request.writeBytes(tail);
        return request.toByteArray();
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

    private byte[] readExact(SerialEndpoint serial, int length) throws IOException {
        byte[] bytes = new byte[length];
        int position = 0;
        while (position < length && running.get()) {
            int count = serial.read(bytes, position, length - position);
            if (count < 0) throw new IOException("serial port closed");
            if (count == 0) return null;
            byte[] chunk = Arrays.copyOfRange(bytes, position, position + count);
            LOG.info(() -> TrafficLog.entry("modbus", "RX", chunk,
                    TrafficLog.rawMeaning("Modbus RTU")));
            position += count;
        }
        return position == length ? bytes : null;
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
