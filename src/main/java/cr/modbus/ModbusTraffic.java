package cr.modbus;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/** In-memory observation counters for valid Modbus RTU traffic seen on the bus. */
public final class ModbusTraffic {
    private final ConcurrentMap<Integer, Counters> messagesByUnit = new ConcurrentHashMap<>();
    private final LongAdder crcErrors = new LongAdder();

    public void recordRequest(int unitId) {
        messagesByUnit.computeIfAbsent(unitId, ignored -> new Counters()).requests.increment();
    }

    public void recordResponse(int unitId) {
        messagesByUnit.computeIfAbsent(unitId, ignored -> new Counters()).responses.increment();
    }

    public void recordCrcError() {
        crcErrors.increment();
    }

    public long crcErrorCount() {
        return crcErrors.sum();
    }

    public List<DeviceCount> snapshot() {
        return messagesByUnit.entrySet().stream()
                .map(entry -> new DeviceCount(entry.getKey(),
                        entry.getValue().requests.sum(), entry.getValue().responses.sum()))
                .sorted(Comparator.comparingInt(DeviceCount::unitId))
                .toList();
    }

    private static final class Counters {
        private final LongAdder requests = new LongAdder();
        private final LongAdder responses = new LongAdder();
    }

    public record DeviceCount(int unitId, long requestCount, long responseCount) {}
}
