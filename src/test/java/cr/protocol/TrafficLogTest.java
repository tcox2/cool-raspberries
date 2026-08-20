package cr.protocol;

import cr.TestFrames;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TrafficLogTest {
    @Test
    void formatsBytesAndDecodesAcOperatingState() {
        byte[] frame = TestFrames.sampleA3();
        String entry = TrafficLog.entry("ac/living", "RX-FRAME", frame, TrafficLog.acFrame(frame));

        assertTrue(entry.contains("serial=ac/living direction=RX-FRAME bytes=\"7A 7A D5 21 22"));
        assertTrue(entry.contains("AC A3 D5→21"));
        assertTrue(entry.contains("CRC=valid"));
        assertTrue(entry.contains("power=0"));
        assertTrue(entry.contains("mode=cool(1)"));
        assertTrue(entry.contains("fan=1"));
        assertTrue(entry.contains("setpoint=21°C"));
    }

    @Test
    void decodesModbusReadWriteAndExceptionTraffic() {
        byte[] read = Crc16.appendModbusCrc(new byte[]{2, 3, 0, 4, 0, 2});
        byte[] write = Crc16.appendModbusCrc(new byte[]{2, 6, 0, 3, 0, 25});
        byte[] exception = Crc16.appendModbusCrc(new byte[]{2, (byte) 0x86, 3});

        assertTrue(TrafficLog.modbusRequest(read).contains(
                "unit=2, function=03/read-holding, CRC=valid, address=4, count=2"));
        assertTrue(TrafficLog.modbusResponse(write).contains(
                "write acknowledged: address=3, value=25"));
        assertTrue(TrafficLog.modbusResponse(exception).contains(
                "exception for function=06/write-single, code=3/illegal-value"));
    }

    @Test
    void marksInvalidCrcClearly() {
        byte[] invalid = {1, 3, 0, 0, 0, 1, 0, 0};
        assertTrue(TrafficLog.modbusRequest(invalid).contains("CRC=INVALID"));
    }
}
