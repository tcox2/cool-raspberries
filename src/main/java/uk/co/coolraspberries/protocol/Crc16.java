package uk.co.coolraspberries.protocol;

public final class Crc16 {
    private Crc16() {}

    public static int modbus(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= Byte.toUnsignedInt(data[i]);
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
            }
        }
        return crc & 0xFFFF;
    }

    public static boolean validAcFrame(byte[] frame) {
        if (frame.length < 4) return false;
        int actual = (Byte.toUnsignedInt(frame[frame.length - 2]) << 8)
                | Byte.toUnsignedInt(frame[frame.length - 1]);
        return actual == modbus(frame, 0, frame.length - 2);
    }

    public static boolean validModbusFrame(byte[] frame) {
        if (frame.length < 4) return false;
        int actual = Byte.toUnsignedInt(frame[frame.length - 2])
                | (Byte.toUnsignedInt(frame[frame.length - 1]) << 8);
        return actual == modbus(frame, 0, frame.length - 2);
    }

    public static byte[] appendModbusCrc(byte[] payload) {
        int crc = modbus(payload, 0, payload.length);
        byte[] result = java.util.Arrays.copyOf(payload, payload.length + 2);
        result[payload.length] = (byte) crc;
        result[payload.length + 1] = (byte) (crc >>> 8);
        return result;
    }
}
