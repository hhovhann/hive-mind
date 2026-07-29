package com.hhovhann.hivemind.app.doctor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/doctor")
public class DoctorController {

    private final HiveDoctor doctor;

    public DoctorController(HiveDoctor doctor) {
        this.doctor = doctor;
    }

    @GetMapping
    public ResponseEntity<DoctorReport> report() {
        DoctorReport report = doctor.run();
        return ResponseEntity.status(report.healthy() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(report);
    }
}
