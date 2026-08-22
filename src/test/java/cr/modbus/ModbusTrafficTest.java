package cr.modbus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModbusTrafficTest {
    @Test
    void countsMessagesByUnitAndSortsSnapshot() {
        ModbusTraffic traffic = new ModbusTraffic();
        traffic.recordRequest(11);
        traffic.recordRequest(3);
        traffic.recordResponse(11);
        traffic.recordRequest(11);
        traffic.recordCrcError();
        traffic.recordCrcError();

        assertEquals(
                java.util.List.of(
                        new ModbusTraffic.DeviceCount(3, 1, 0),
                        new ModbusTraffic.DeviceCount(11, 2, 1)),
                traffic.snapshot());
        assertEquals(2, traffic.crcErrorCount());
        assertEquals(new ModbusTraffic.DeviceCount(11, 2, 1), traffic.device(11).orElseThrow());
        assertTrue(traffic.device(99).isEmpty());
    }
}
