package com.hhovhann.hivemind.app;

import com.hhovhann.hivemind.app.cli.HiveCommand;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Hive Mind — shared agentic knowledge infrastructure.
 *
 * <p>Beans live in sibling modules under {@code com.hhovhann.hivemind.*}, so the
 * scan base is the parent package rather than this one.
 */
@SpringBootApplication(scanBasePackages = "com.hhovhann.hivemind")
@ConfigurationPropertiesScan("com.hhovhann.hivemind")
public class HiveMindApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(HiveMindApplication.class);
        // A CLI command prints and exits; starting a web server it will never
        // serve just adds noise and a port conflict.
        if (HiveCommand.from(args).isPresent()) {
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setBannerMode(Banner.Mode.OFF);
        }
        application.run(args);
    }
}
