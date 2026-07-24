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
        int[] controls = new int[RegisterBank.REGISTER_COUNT];
        controls[RegisterBank.POWER] = 1;
        controls[RegisterBank.MODE] = 4;
        controls[RegisterBank.FAN] = 5;
        controls[RegisterBank.SETPOINT_C] = 23;
        controls[RegisterBank.TURBO] = 1;
        controls[RegisterBank.QUIET] = 1;
        controls[RegisterBank.SWEEP_LR] = 3;
        controls[RegisterBank.SWEEP_UD] = 7;
        controls[RegisterBank.DISPLAY] = 1;
        controls[RegisterBank.IONIZER] = 0;
        controls[RegisterBank.AUX_HEATER] = 1;
        controls[RegisterBank.SLEEP] = 1;
        controls[RegisterBank.ENERGY_SAVING] = 0;
        controls[RegisterBank.TIMER_MINUTES] = 0x1234;

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

}
