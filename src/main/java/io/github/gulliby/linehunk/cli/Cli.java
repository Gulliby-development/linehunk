package io.github.gulliby.linehunk.cli;

import io.github.gulliby.linehunk.diff.Lines;
import io.github.gulliby.linehunk.diff.TreeDiff;
import io.github.gulliby.linehunk.diff.UnifiedDiff;
import io.github.gulliby.linehunk.patch.Applier;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Argument handling and command dispatch for linehunk. */
public final class Cli {
    public static final String HELP_TEXT =
            "Usage: linehunk diff [-U N] [--jobs N] A B\n"
                    + "       linehunk apply [-R] [--check] PATCH DIR\n";

    private static final CommandHandler DEFAULT_COMMAND_HANDLER = new BuiltInCommandHandler();
    private static volatile CommandHandler commandHandler = DEFAULT_COMMAND_HANDLER;

    private Cli() {
    }

    /**
     * Runs linehunk and writes only explicit-LF output.
     *
     * <p>The diff and apply implementations register their handler as those
     * command APIs become available. Parsing and validation are kept here so
     * every command has identical option and diagnostic ordering.</p>
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");

        if (args == null || args.length == 0) {
            return error(err, "command required");
        }
        if (args.length == 1 && "--help".equals(args[0])) {
            out.print(HELP_TEXT);
            return 0;
        }

        String command = args[0];
        if (!"diff".equals(command) && !"apply".equals(command)) {
            return error(err, "unknown command \"" + command + "\"");
        }

        Parsed parsed = parse(command, args);
        if (parsed.error != null) {
            return error(err, parsed.error);
        }

        if ("diff".equals(command)) {
            String pathError = validateDiffPaths(parsed.operands);
            if (pathError != null) {
                return error(err, pathError);
            }
        } else {
            String pathError = validateApplyDirectory(parsed.operands);
            if (pathError != null) {
                return error(err, pathError);
            }
        }

        CommandHandler handler = commandHandler;
        ByteArrayOutputStream commandOutput = new ByteArrayOutputStream();
        PrintStream bufferedOut = new PrintStream(commandOutput, true, StandardCharsets.UTF_8);
        try {
            int status;
            if ("diff".equals(command)) {
                status = handler.diff(parsed.operands.get(0), parsed.operands.get(1),
                        parsed.context, parsed.jobs, bufferedOut, err);
            } else {
                status = handler.apply(parsed.operands.get(0), parsed.operands.get(1),
                        parsed.reverse, parsed.check, bufferedOut, err);
            }
            bufferedOut.flush();
            if (status != 2) {
                out.print(commandOutput.toString(StandardCharsets.UTF_8));
            }
            return status;
        } catch (RuntimeException ex) {
            return error(err, "internal error");
        }
    }

    /** Installs the application command implementations used by {@link #run}. */
    public static void setCommandHandler(CommandHandler handler) {
        commandHandler = Objects.requireNonNull(handler, "handler");
    }

    /** Contract implemented by the diff and patch layers. */
    public interface CommandHandler {
        int diff(String a, String b, int context, int jobs, PrintStream out, PrintStream err);

        int apply(String patch, String directory, boolean reverse, boolean check,
                  PrintStream out, PrintStream err);
    }

    private static void report(PrintStream err, String message) {
        err.print("linehunk: ");
        err.print(message);
        err.print('\n');
    }

    private static final class BuiltInCommandHandler implements CommandHandler {
        @Override
        public int diff(String oldArgument, String newArgument, int context, int jobs,
                        PrintStream out, PrintStream err) {
            Path oldPath = Path.of(oldArgument);
            Path newPath = Path.of(newArgument);
            try {
                if (Files.isDirectory(oldPath)) {
                    String result = TreeDiff.diff(oldPath, newPath, context, jobs);
                    out.print(result);
                    return result.isEmpty() ? 0 : 1;
                }

                List<String> oldLines = readLines(oldPath, oldArgument, err);
                if (oldLines == null) {
                    return 2;
                }
                List<String> newLines = readLines(newPath, newArgument, err);
                if (newLines == null) {
                    return 2;
                }
                String result = UnifiedDiff.render(oldArgument, newArgument,
                        oldLines, newLines, context);
                out.print(result);
                return result.isEmpty() ? 0 : 1;
            } catch (TreeDiff.TreeDiffException ex) {
                report(err, ex.getMessage());
                return 2;
            } catch (SecurityException ex) {
                report(err, oldArgument + ": cannot read");
                return 2;
            }
        }

        @Override
        public int apply(String patch, String directory, boolean reverse, boolean check,
                         PrintStream out, PrintStream err) {
            return Applier.run(patch, directory, reverse, check, out, err);
        }

        private static List<String> readLines(Path path, String displayPath, PrintStream err) {
            try {
                return Lines.read(path);
            } catch (Lines.ReadException ex) {
                String reason = switch (ex.failure()) {
                    case INVALID_UTF8 -> "not valid UTF-8";
                    case TOO_MANY_LINES -> "more than 2000 lines";
                };
                report(err, displayPath + ": " + reason);
            } catch (IOException | SecurityException ex) {
                report(err, displayPath + ": cannot read");
            }
            return null;
        }
    }

    private static Parsed parse(String command, String[] args) {
        int context = 3;
        int jobs = 4;
        boolean reverse = false;
        boolean check = false;
        List<String> operands = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("diff".equals(command) && ("-U".equals(arg) || "--jobs".equals(arg))) {
                if (i + 1 >= args.length) {
                    return Parsed.failure("missing value for " + arg);
                }
                String value = args[++i];
                if ("-U".equals(arg)) {
                    if (!isAsciiDigits(value, 1, 2)) {
                        return Parsed.failure("invalid context \"" + value + "\"");
                    }
                    context = Integer.parseInt(value);
                    if (context > 99) {
                        return Parsed.failure("invalid context \"" + value + "\"");
                    }
                } else {
                    if (!isAsciiDigits(value, 1, 1)) {
                        return Parsed.failure("invalid jobs \"" + value + "\"");
                    }
                    jobs = Integer.parseInt(value);
                    if (jobs < 1 || jobs > 8) {
                        return Parsed.failure("invalid jobs \"" + value + "\"");
                    }
                }
            } else if ("apply".equals(command) && ("-R".equals(arg) || "--check".equals(arg))) {
                if ("-R".equals(arg)) {
                    reverse = true;
                } else {
                    check = true;
                }
            } else if (arg.startsWith("-") && arg.length() > 1) {
                return Parsed.failure("unknown option \"" + arg + "\"");
            } else {
                operands.add(arg);
            }
        }

        int expected = 2;
        if (operands.size() != expected) {
            return Parsed.failure("exactly two " + ("diff".equals(command) ? "paths" : "arguments")
                    + " required");
        }
        return new Parsed(operands, context, jobs, reverse, check, null);
    }

    private static boolean isAsciiDigits(String value, int minLength, int maxLength) {
        if (value.length() < minLength || value.length() > maxLength) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String validateDiffPaths(List<String> operands) {
        Path a;
        Path b;
        try {
            a = Path.of(operands.get(0));
        } catch (InvalidPathException ex) {
            return operands.get(0) + ": cannot read";
        }
        try {
            b = Path.of(operands.get(1));
        } catch (InvalidPathException ex) {
            return operands.get(1) + ": cannot read";
        }

        if (!Files.exists(a)) {
            return operands.get(0) + ": no such file or directory";
        }
        if (!Files.exists(b)) {
            return operands.get(1) + ": no such file or directory";
        }
        boolean aDirectory = Files.isDirectory(a);
        boolean bDirectory = Files.isDirectory(b);
        boolean aFile = Files.isRegularFile(a);
        boolean bFile = Files.isRegularFile(b);
        if (!(aDirectory && bDirectory) && !(aFile && bFile)) {
            return operands.get(0) + " and " + operands.get(1)
                    + " must both be files or both be directories";
        }
        return null;
    }

    private static String validateApplyDirectory(List<String> operands) {
        try {
            if (!Files.isDirectory(Path.of(operands.get(1)))) {
                return operands.get(1) + ": not a directory";
            }
        } catch (InvalidPathException ex) {
            return operands.get(1) + ": not a directory";
        }
        return null;
    }

    private static int error(PrintStream err, String message) {
        err.print("linehunk: ");
        err.print(message);
        err.print('\n');
        return 2;
    }

    private static final class Parsed {
        private final List<String> operands;
        private final int context;
        private final int jobs;
        private final boolean reverse;
        private final boolean check;
        private final String error;

        private Parsed(List<String> operands, int context, int jobs,
                       boolean reverse, boolean check, String error) {
            this.operands = operands;
            this.context = context;
            this.jobs = jobs;
            this.reverse = reverse;
            this.check = check;
            this.error = error;
        }

        private static Parsed failure(String error) {
            return new Parsed(List.of(), 3, 4, false, false, error);
        }
    }
}
