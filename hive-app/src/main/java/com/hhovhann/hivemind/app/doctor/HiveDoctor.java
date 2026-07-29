package com.hhovhann.hivemind.app.doctor;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

/**
 * Runs every {@link HealthProbe} and reports what Hive Mind can and cannot reach.
 *
 * <p>Probes run concurrently on virtual threads: they are all network waits, and a
 * down dependency should cost one timeout for the whole report, not one each.
 */
@Service
public class HiveDoctor {

    private final List<HealthProbe> probes;

    public HiveDoctor(List<HealthProbe> probes) {
        this.probes = List.copyOf(probes);
    }

    public DoctorReport run() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<CheckResult>> pending = probes.stream()
                    .map(probe -> CompletableFuture.supplyAsync(() -> guard(probe), executor))
                    .toList();
            List<CheckResult> results = pending.stream()
                    .map(CompletableFuture::join)
                    .sorted(java.util.Comparator.comparing(CheckResult::component))
                    .toList();
            return new DoctorReport(Instant.now(), results);
        }
    }

    /** A probe that throws is itself a failure worth reporting, not a crash. */
    private static CheckResult guard(HealthProbe probe) {
        long start = System.nanoTime();
        try {
            return probe.probe();
        } catch (RuntimeException e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return CheckResult.down(probe.component(), "probe threw: " + e, elapsedMs);
        }
    }
}
