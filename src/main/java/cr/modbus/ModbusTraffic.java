package cr.modbus;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/** In-memory observation counters for valid Modbus RTU requests seen on the bus. */
public final class ModbusTraffic {
    private final ConcurrentMap<Integer, LongAdder> messagesByUnit = new ConcurrentHashMap<>();

    public void record(int unitId) {
        messagesByUnit.computeIfAbsent(unitId, ignored -> new LongAdder()).increment();
    }

    public List<DeviceCount> snapshot() {
        return messagesByUnit.entrySet().stream()
                .map(entry -> new DeviceCount(entry.getKey(), entry.getValue().sum()))
                .sorted(Comparator.comparingInt(DeviceCount::unitId))
                .toList();
    }

    public record DeviceCount(int unitId, long messageCount) {}
}
