package uk.co.coolraspberries;

import uk.co.coolraspberries.ac.AcProtocol;
import uk.co.coolraspberries.protocol.Crc16;

public final class TestFrames {
    private TestFrames() {}

    public static byte[] sampleA3() {
        byte[] frame = new byte[34];
        frame[0] = 0x7A;
        frame[1] = 0x7A;
        frame[2] = (byte) 0xD5;
        frame[3] = 0x21;
        frame[4] = 34;
        frame[7] = (byte) AcProtocol.TYPE_A3;
        frame[8] = 0x0A;
        frame[9] = 0x0A;
        frame[13] = 0x11;
        frame[14] = 0x05;
        int crc = Crc16.modbus(frame, 0, 32);
        frame[32] = (byte) (crc >>> 8);
        frame[33] = (byte) crc;
        return frame;
    }
}
