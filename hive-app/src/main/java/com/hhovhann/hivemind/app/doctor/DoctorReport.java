package com.hhovhann.hivemind.app.doctor;

import java.time.Instant;
import java.util.List;

public record DoctorReport(Instant checkedAt, List<CheckResult> checks) {

    public DoctorReport {
        checks = List.copyOf(checks);
    }

    public boolean healthy() {
        return checks.stream().allMatch(CheckResult::healthy);
    }

    public List<CheckResult> failures() {
        return checks.stream().filter(c -> !c.healthy()).toList();
    }
}
