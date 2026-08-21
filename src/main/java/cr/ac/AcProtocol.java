package cr.ac;

import cr.core.RegisterBank;
import cr.protocol.Crc16;


import java.util.Arrays;

public final class AcProtocol {
    public static final int TYPE_A1 = 0xA1;
    public static final int TYPE_A3 = 0xA3;
    public static final int TYPE_A4 = 0xA4;
    public static final int TYPE_AB = 0xAB;

    private AcProtocol() {}

    public static byte[] heartbeat() {
        byte[] frame = {
                0x7A, 0x7A, 0x21, (byte) 0xD5, 0x0C, 0x00,
                0x00, (byte) 0xAB, 0x0A, 0x0A, 0x00, 0x00
        };
        setAcCrc(frame);
        return frame;
    }

    public static byte[] configuration(byte[] lastA3, int[] controls, byte[] mac) {
        if (lastA3 == null || lastA3.length != 34) {
            throw new IllegalStateException("a valid A3 frame is required before sending controls");
        }
        if (controls.length < RegisterBank.CONTROL_COUNT) throw new IllegalArgumentException("control snapshot is incomplete");
        if (mac.length != 6) throw new IllegalArgumentException("MAC must contain six octets");

        byte[] frame = new byte[24];
        frame[0] = 0x7A;
        frame[1] = 0x7A;
        frame[2] = 0x21;
        frame[3] = (byte) 0xD5;
        frame[4] = 24;
        frame[7] = (byte) TYPE_A1;

        // A3 decoding indicates a big-endian timer. Hardware verification is
        // still required because the upstream setter contradicted its decoder.
        frame[10] = (byte) (controls[RegisterBank.CONTROL_TIMER_MINUTES] >>> 8);
        frame[11] = (byte) controls[RegisterBank.CONTROL_TIMER_MINUTES];
        frame[12] = bits(frame[12], 0x80, controls[RegisterBank.CONTROL_TURBO] << 7);
        frame[12] = bits(frame[12], 0x70, controls[RegisterBank.CONTROL_FAN] << 4);
        frame[12] = bits(frame[12], 0x08, controls[RegisterBank.CONTROL_POWER] << 3);
        frame[12] = bits(frame[12], 0x07, controls[RegisterBank.CONTROL_MODE]);
        frame[13] = bits(frame[13], 0x40, controls[RegisterBank.CONTROL_QUIET] << 6);
        frame[13] = bits(frame[13], 0x0F, controls[RegisterBank.CONTROL_SETPOINT_C] - 16);
        frame[14] = bits(frame[14], 0xF0, controls[RegisterBank.CONTROL_SWEEP_LR] << 4);
        frame[14] = bits(frame[14], 0x0F, controls[RegisterBank.CONTROL_SWEEP_UD]);
        frame[15] = bits(frame[15], 0x80, controls[RegisterBank.CONTROL_DISPLAY] << 7);
        frame[15] = bits(frame[15], 0x40, controls[RegisterBank.CONTROL_IONIZER] << 6);
        frame[15] = bits(frame[15], 0x10, controls[RegisterBank.CONTROL_AUX_HEATER] << 4);
        frame[15] = bits(frame[15], 0x02, controls[RegisterBank.CONTROL_SLEEP] << 1);
        frame[15] = bits(frame[15], 0x01, controls[RegisterBank.CONTROL_ENERGY_SAVING]);
        System.arraycopy(mac, 0, frame, 16, mac.length);
        setAcCrc(frame);
        return frame;
    }

    public static int frameType(byte[] frame) {
        return frame.length > 7 ? Byte.toUnsignedInt(frame[7]) : -1;
    }

    public static boolean hasEnvelope(byte[] frame) {
        return frame.length >= 12
                && frame[0] == 0x7A
                && frame[1] == 0x7A
                && Byte.toUnsignedInt(frame[4]) == frame.length;
    }

    private static byte bits(byte original, int mask, int value) {
        return (byte) ((Byte.toUnsignedInt(original) & ~mask) | (value & mask));
    }

    private static void setAcCrc(byte[] frame) {
        int crc = Crc16.modbus(frame, 0, frame.length - 2);
        frame[frame.length - 2] = (byte) (crc >>> 8);
        frame[frame.length - 1] = (byte) crc;
    }

    static byte[] copyA3(byte[] frame) {
        return Arrays.copyOf(frame, frame.length);
    }
}
