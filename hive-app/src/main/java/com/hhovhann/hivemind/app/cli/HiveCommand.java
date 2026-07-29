package com.hhovhann.hivemind.app.cli;

import java.util.Arrays;
import java.util.Optional;

/**
 * Commands Hive Mind can run as a one-shot CLI instead of as a server.
 *
 * <p>Recognising these in {@code main} lets us skip starting the web server for a
 * command that just prints and exits. Accepted as {@code doctor} or {@code --doctor}.
 */
public enum HiveCommand {
    DOCTOR("doctor"),
    EXTRACT("extract"),
    SCORE("score"),
    LOAD("load"),
    ASK("ask"),
    EVALUATE("evaluate"),
    EXPORT("export"),
    /** Not a one-shot: prints nothing and runs until the MCP client closes the pipe. */
    MCP("mcp");

    private final String token;

    HiveCommand(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static Optional<HiveCommand> from(String[] args) {
        return Arrays.stream(args)
                .map(HiveCommand::strip)
                .flatMap(candidate -> Arrays.stream(values()).filter(command -> command.token.equals(candidate)))
                .findFirst();
    }

    public boolean present(String[] args) {
        return from(args).filter(this::equals).isPresent();
    }

    /** Reads {@code --name=value} from the argument list. */
    public static Optional<String> option(String[] args, String name) {
        String prefix = "--" + name + "=";
        return Arrays.stream(args)
                .filter(arg -> arg.startsWith(prefix))
                .map(arg -> arg.substring(prefix.length()))
                .findFirst();
    }

    private static String strip(String arg) {
        return arg.startsWith("--") ? arg.substring(2) : arg;
    }
}
