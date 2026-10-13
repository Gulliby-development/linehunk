package io.github.gulliby.linehunk.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Process entry point for the linehunk command-line application. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.exit(Cli.run(args, out, err));
    }
}
