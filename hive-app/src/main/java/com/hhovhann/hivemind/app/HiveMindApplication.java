package com.hhovhann.hivemind.app;

import com.hhovhann.hivemind.app.cli.HiveCommand;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
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
        if (HiveCommand.MCP.present(args)) {
            claimStdoutForTheProtocol();
        }
        SpringApplication application = new SpringApplication(HiveMindApplication.class);
        // A CLI command prints and exits; starting a web server it will never
        // serve just adds noise and a port conflict.
        if (HiveCommand.from(args).isPresent()) {
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setBannerMode(Banner.Mode.OFF);
        }
        application.run(args);
    }

    /**
     * Points {@code System.out} at stderr, so only the MCP transport writes to stdout.
     *
     * <p>On the stdio transport stdout is a JSON-RPC wire. One Spring startup line on
     * it corrupts a frame and the client drops the session, reporting a parse error
     * that names no culprit — which is a long afternoon. Redirecting the stream is
     * more reliable than finding every writer: it catches logging, whatever a library
     * decides to print, and the {@code System.out.printf} the other CLI runners are
     * built on. The transport is handed {@link FileDescriptor#out} directly and so is
     * unaffected.
     *
     * <p>Has to happen here, before {@code run}, because the first startup log line is
     * emitted inside it.
     */
    private static void claimStdoutForTheProtocol() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true));
    }
}