package ch.benedict.m321.loadgenerator.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prüft das Zählen — auch dann, wenn viele Threads gleichzeitig zählen.
 */
class LoadStatisticsTest {

    @Test
    void countsAcceptedAndFailedPerInstance() {
        LoadStatistics statistics = new LoadStatistics();

        statistics.recordAccepted("instanz-a");
        statistics.recordAccepted("instanz-a");
        statistics.recordAccepted("instanz-b");
        statistics.recordAccepted(null);
        statistics.recordFailed();

        assertEquals(4, statistics.acceptedCount());
        assertEquals(1, statistics.failedCount());
        Map<String, Long> byInstance = statistics.acceptedByInstance();
        assertEquals(2L, byInstance.get("instanz-a"));
        assertEquals(1L, byInstance.get("instanz-b"));
        assertEquals(1L, byInstance.get(LoadStatistics.UNKNOWN_INSTANCE));
    }

    @Test
    void losesNoCountWhenManyThreadsCountAtOnce() {
        LoadStatistics statistics = new LoadStatistics();

        // 10'000 virtuelle Threads zählen gleichzeitig. Mit einem normalen
        // long statt AtomicLong kämen hier fast sicher weniger heraus.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10_000; i++) {
                executor.submit(() -> statistics.recordAccepted("instanz-a"));
            }
        }

        assertEquals(10_000, statistics.acceptedCount());
        Map<String, Long> byInstance = statistics.acceptedByInstance();
        assertEquals(10_000L, byInstance.get("instanz-a"));
    }
}
