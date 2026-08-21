package cr.ac;

import org.junit.jupiter.api.Test;
import cr.TestFrames;
import cr.core.RegisterBank;
import cr.protocol.Crc16;

import static org.junit.jupiter.api.Assertions.*;

class AcProtocolTest {
    @Test
    void buildsConfigurationFromValidatedControls() {
        byte[] a3 = TestFrames.sampleA3();
        int[] controls = new int[RegisterBank.CONTROL_COUNT];
        controls[RegisterBank.CONTROL_POWER] = 1;
        controls[RegisterBank.CONTROL_MODE] = 4;
        controls[RegisterBank.CONTROL_FAN] = 5;
        controls[RegisterBank.CONTROL_SETPOINT_C] = 23;
        controls[RegisterBank.CONTROL_TURBO] = 1;
        controls[RegisterBank.CONTROL_QUIET] = 1;
        controls[RegisterBank.CONTROL_SWEEP_LR] = 3;
        controls[RegisterBank.CONTROL_SWEEP_UD] = 7;
        controls[RegisterBank.CONTROL_DISPLAY] = 1;
        controls[RegisterBank.CONTROL_AUX_HEATER] = 1;
        controls[RegisterBank.CONTROL_SLEEP] = 1;
        controls[RegisterBank.CONTROL_TIMER_MINUTES] = 0x1234;

        byte[] frame = AcProtocol.configuration(a3, controls,
                new byte[]{1, 2, 3, 4, 5, 6});

        assertEquals(24, frame.length);
        assertEquals(AcProtocol.TYPE_A1, Byte.toUnsignedInt(frame[7]));
        assertEquals(0x12, Byte.toUnsignedInt(frame[10]));
        assertEquals(0x34, Byte.toUnsignedInt(frame[11]));
        assertEquals(0b1101_1100, Byte.toUnsignedInt(frame[12]));
        assertEquals(0b0100_0111, Byte.toUnsignedInt(frame[13]));
        assertEquals(0x37, Byte.toUnsignedInt(frame[14]));
        assertEquals(0b1001_0010, Byte.toUnsignedInt(frame[15]));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6},
                java.util.Arrays.copyOfRange(frame, 16, 22));
        assertTrue(Crc16.validAcFrame(frame));
    }

    @Test
    void doesNotCarryUncontrolledBitsFromObservedState() {
        byte[] a3 = TestFrames.sampleA3();
        a3[13] = (byte) 0xff;
        a3[14] = (byte) 0xff;
        a3[15] = (byte) 0xff;
        a3[16] = (byte) 0xff;
        int[] controls = new int[RegisterBank.CONTROL_COUNT];
        controls[RegisterBank.CONTROL_SETPOINT_C] = 24;

        byte[] frame = AcProtocol.configuration(a3, controls, new byte[6]);

        assertEquals(0, Byte.toUnsignedInt(frame[12]));
        assertEquals(8, Byte.toUnsignedInt(frame[13]));
        assertEquals(0, Byte.toUnsignedInt(frame[14]));
        assertEquals(0, Byte.toUnsignedInt(frame[15]));
    }

}
