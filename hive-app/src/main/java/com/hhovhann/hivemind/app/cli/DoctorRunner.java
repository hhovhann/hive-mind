package com.hhovhann.hivemind.app.cli;

import com.hhovhann.hivemind.app.doctor.CheckResult;
import com.hhovhann.hivemind.app.doctor.DoctorReport;
import com.hhovhann.hivemind.app.doctor.HiveDoctor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code ./gradlew :hive-app:bootRun --args='doctor'} — prints what Hive Mind can
 * reach and exits non-zero if anything is unusable, so it works in CI too.
 */
@Component
public class DoctorRunner implements ApplicationRunner {

    private final HiveDoctor doctor;
    private final ConfigurableApplicationContext context;

    public DoctorRunner(HiveDoctor doctor, ConfigurableApplicationContext context) {
        this.doctor = doctor;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!HiveCommand.DOCTOR.present(args.getSourceArgs())) {
            return;
        }
        DoctorReport report = doctor.run();
        print(report);
        System.exit(SpringApplication.exit(context, () -> report.healthy() ? 0 : 1));
    }

    private void print(DoctorReport report) {
        StringBuilder out = new StringBuilder("\n  Hive Mind doctor\n  ")
                .append("-".repeat(72))
                .append('\n');
        for (CheckResult check : report.checks()) {
            out.append("  %-3s %-12s %6s  %s%n".formatted(
                    icon(check.status()), check.component(), check.latencyMs() + "ms", check.detail()));
        }
        out.append("  ").append("-".repeat(72)).append('\n');
        out.append(report.healthy()
                        ? "  All dependencies ready.\n"
                        : "  %d of %d checks need attention.\n".formatted(report.failures().size(),
                                report.checks().size()))
                .append('\n');
        System.out.print(out);
    }

    private static String icon(CheckResult.Status status) {
        return switch (status) {
            case OK -> "OK";
            case DEGRADED -> "!!";
            case DOWN -> "XX";
        };
    }
}
