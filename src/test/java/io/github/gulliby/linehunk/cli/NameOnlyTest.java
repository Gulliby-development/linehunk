package io.github.gulliby.linehunk.cli;

import io.github.gulliby.linehunk.diff.Lines;
import io.github.gulliby.linehunk.diff.TreeDiff;
import io.github.gulliby.linehunk.diff.UnifiedDiff;
import io.github.gulliby.linehunk.patch.Applier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameOnlyTest {
    @TempDir
    Path tempDir;

    @BeforeEach
    void connectCommandImplementations() {
        Cli.setCommandHandler(new Cli.CommandHandler() {
            @Override
            public int diff(String oldArgument, String newArgument, int context, int jobs,
                            PrintStream out, PrintStream err) {
                Path oldPath = Path.of(oldArgument);
                Path newPath = Path.of(newArgument);
                if (Files.isDirectory(oldPath)) {
                    try {
                        String result = TreeDiff.diff(oldPath, newPath, context, jobs);
                        out.print(result);
                        return result.isEmpty() ? 0 : 1;
                    } catch (TreeDiff.TreeDiffException ex) {
                        report(err, ex.getMessage());
                        return 2;
                    }
                }

                List<String> oldLines = readLines(oldPath, oldArgument, err);
                if (oldLines == null) {
                    return 2;
                }
                List<String> newLines = readLines(newPath, newArgument, err);
                if (newLines == null) {
                    return 2;
                }
                String result = UnifiedDiff.render(oldArgument, newArgument, oldLines, newLines, context);
                out.print(result);
                return result.isEmpty() ? 0 : 1;
            }

            @Override
            public int apply(String patch, String directory, boolean reverse, boolean check,
                             PrintStream out, PrintStream err) {
                return Applier.run(patch, directory, reverse, check, out, err);
            }
        });
    }

    @Test
    void fileNameOnlyPrintsTheSecondOperandAndPreservesNormalDiffStatus() throws IOException {
        Path oldFile = tempDir.resolve("a.txt");
        Path newFile = tempDir.resolve("b.txt");
        writeLines(oldFile, List.of("one", "two", "three", "four", "five", "six",
                "seven", "eight", "nine", "ten", "eleven", "twelve"));
        writeLines(newFile, List.of("one", "two", "three", "FOUR", "five", "six",
                "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen"));

        Invocation result = run("diff", "--name-only", oldFile.toString(), newFile.toString());

        assertEquals(1, result.status());
        assertEquals(newFile + "\n", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void treeNameOnlyIsSortedAndIncludesEmptyFileCreatesAndDeletes() throws IOException {
        TreePair trees = createExampleTrees();

        Invocation result = run("diff", "--name-only", trees.oldTree().toString(),
                trees.newTree().toString());

        assertEquals(1, result.status());
        assertEquals("edit.txt\n"
                + "empty-gone.txt\n"
                + "gone.txt\n"
                + "new/empty.txt\n"
                + "new/sub.txt\n", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void nameOnlyIsAcceptedBeforeBetweenAndAfterOperandsAndMayRepeat() throws IOException {
        Path oldFile = tempDir.resolve("old.txt");
        Path newFile = tempDir.resolve("new.txt");
        writeLines(oldFile, List.of("before", "old", "after"));
        writeLines(newFile, List.of("before", "new", "after"));
        String expected = newFile + "\n";

        for (String[] args : List.of(
                new String[]{"diff", "--name-only", oldFile.toString(), newFile.toString()},
                new String[]{"diff", oldFile.toString(), "--name-only", newFile.toString()},
                new String[]{"diff", oldFile.toString(), newFile.toString(), "--name-only"},
                new String[]{"diff", "--name-only", oldFile.toString(), "--name-only",
                        newFile.toString(), "--name-only"})) {
            Invocation result = run(args);
            assertEquals(1, result.status());
            assertEquals(expected, result.stdout());
            assertEquals("", result.stderr());
        }
    }

    @Test
    void nameOnlyDoesNotDependOnContextOrWorkerCount() throws IOException {
        TreePair trees = createExampleTrees();
        String expected = "edit.txt\n"
                + "empty-gone.txt\n"
                + "gone.txt\n"
                + "new/empty.txt\n"
                + "new/sub.txt\n";

        for (String[] options : List.of(
                new String[]{"-U", "0"},
                new String[]{"-U", "99"},
                new String[]{"--jobs", "1"},
                new String[]{"--jobs", "8"},
                new String[]{"-U", "0", "--jobs", "1"},
                new String[]{"-U", "99", "--jobs", "8"})) {
            String[] args = new String[options.length + 4];
            args[0] = "diff";
            args[1] = "--name-only";
            System.arraycopy(options, 0, args, 2, options.length);
            args[args.length - 2] = trees.oldTree().toString();
            args[args.length - 1] = trees.newTree().toString();

            Invocation result = run(args);
            assertEquals(1, result.status());
            assertEquals(expected, result.stdout());
            assertEquals("", result.stderr());
        }
    }

    @Test
    void identicalAndLineEndingEquivalentFilesPrintNothing() throws IOException {
        Path sameFile = tempDir.resolve("same.txt");
        writeLines(sameFile, List.of("same", "content"));

        Invocation same = run("diff", "--name-only", sameFile.toString(), sameFile.toString());

        assertEquals(0, same.status());
        assertEquals("", same.stdout());
        assertEquals("", same.stderr());

        Path crlf = tempDir.resolve("crlf.txt");
        Path lf = tempDir.resolve("lf.txt");
        Files.writeString(crlf, "first\r\nsecond\r\n", StandardCharsets.UTF_8);
        Files.writeString(lf, "first\nsecond\n", StandardCharsets.UTF_8);

        Invocation equivalent = run("diff", crlf.toString(), "--name-only", lf.toString());

        assertEquals(0, equivalent.status());
        assertEquals("", equivalent.stdout());
        assertEquals("", equivalent.stderr());
    }

    @Test
    void combinedOptionsAndArgumentErrorsHaveRequiredPrecedence() {
        String combinedError = "linehunk: --stat and --name-only cannot be combined\n";
        assertError(new String[]{"diff", "--stat", "--name-only", "a", "b"}, combinedError);
        assertError(new String[]{"diff", "--name-only", "a", "--stat"}, combinedError);
        assertError(new String[]{"diff", "--name-only", "--stat"}, combinedError);
        assertError(new String[]{"diff", "--name-only", "only-one", "--stat"}, combinedError);

        assertError(new String[]{"diff", "--name-only", "--mystery", "--stat", "a", "b"},
                "linehunk: unknown option \"--mystery\"\n");
        assertError(new String[]{"diff", "--name-only", "-U"},
                "linehunk: missing value for -U\n");
        assertError(new String[]{"diff", "--name-only", "-U", "100", "--stat", "a", "b"},
                "linehunk: invalid context \"100\"\n");
        assertError(new String[]{"diff", "--name-only", "--jobs", "9", "--stat", "a", "b"},
                "linehunk: invalid jobs \"9\"\n");
        assertError(new String[]{"diff", "--name-only", "--jobs", "9", "-U", "bad", "--stat"},
                "linehunk: invalid jobs \"9\"\n");
        assertError(new String[]{"diff", "--name-only", "-U", "bad", "--jobs", "9", "--stat"},
                "linehunk: invalid context \"bad\"\n");
        assertError(new String[]{"diff", "--name-only", "only-one"},
                "linehunk: exactly two paths required\n");
    }

    @Test
    void applyAndEqualsFormsRejectNameOnlyAsUnknownOptions() {
        assertError(new String[]{"apply", "--name-only", "patch", "directory"},
                "linehunk: unknown option \"--name-only\"\n");
        assertError(new String[]{"diff", "--name-only=1", "a", "b"},
                "linehunk: unknown option \"--name-only=1\"\n");
    }

    @Test
    void readErrorsKeepStdoutEmptyAndUseOneDiagnosticLine() throws IOException {
        Path invalid = tempDir.resolve("invalid.txt");
        Path valid = tempDir.resolve("valid.txt");
        Files.write(invalid, new byte[]{(byte) 0xc3, (byte) 0x28});
        writeLines(valid, List.of("valid"));

        assertError(new String[]{"diff", "--name-only", invalid.toString(), valid.toString()},
                "linehunk: " + invalid + ": not valid UTF-8\n");
    }

    @Test
    void readmeDocumentsNameOnlyUsageAndTheExactDirectoryExample() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        String tick = Character.toString(96);
        String fence = tick.repeat(3);

        assertTrue(readme.contains("linehunk diff [-U N] [--jobs N] [--stat | --name-only] A B"));
        assertTrue(readme.contains("Usage: linehunk diff [-U N] [--jobs N] [--stat | --name-only] A B"));
        assertTrue(readme.contains(tick + "--name-only" + tick + " (diff only) prints only the names of the "
                + "changed files instead of the diff; it cannot be combined with "
                + tick + "--stat" + tick + "."));
        assertTrue(readme.contains("### Names only with --name-only\n\n"
                + "To get just the list of changed files, for example to feed another command, run "
                + tick + "linehunk diff --name-only A B" + tick + ". It prints one name per line, the same "
                + "names that " + tick + "--stat" + tick + " shows (the second operand for a file diff, "
                + "sorted relative paths without the " + tick + "a/" + tick + " or " + tick + "b/" + tick
                + " prefix for a tree diff), and no totals line. For the directory example above:\n\n"
                + fence + "text\n"
                + "edit.txt\n"
                + "empty-gone.txt\n"
                + "gone.txt\n"
                + "new/empty.txt\n"
                + "new/sub.txt\n"
                + fence + "\n\n"
                + "The exit codes are those of a normal diff (0 for no differences, in which case nothing is "
                + "printed, 1 for differences, 2 for an error). " + tick + "--name-only" + tick
                + " is a diff option, not an option of " + tick + "apply" + tick
                + ", and combining it with " + tick + "--stat" + tick + " is an error ("
                + tick + "linehunk: --stat and --name-only cannot be combined" + tick
                + ", exit code 2)."));
    }

    private TreePair createExampleTrees() throws IOException {
        Path oldTree = tempDir.resolve("A");
        Path newTree = tempDir.resolve("B");
                Files.createDirectories(newTree);
        writeLines(oldTree.resolve("edit.txt"), List.of("1", "2", "3", "4", "5"));
        Files.writeString(oldTree.resolve("same.txt"), "x\ny\n", StandardCharsets.UTF_8);
        Files.writeString(newTree.resolve("same.txt"), "x\r\ny\r\n", StandardCharsets.UTF_8);
        writeLines(oldTree.resolve("gone.txt"), List.of("bye", "now"));
        Files.createFile(oldTree.resolve("empty-gone.txt"));
        writeLines(newTree.resolve("edit.txt"), List.of("1", "2", "three", "4", "5"));
        writeLines(newTree.resolve("new").resolve("sub.txt"), List.of("hello", "world"));
        Files.createFile(newTree.resolve("new").resolve("empty.txt"));
        return new TreePair(oldTree, newTree);
    }

    private Invocation run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            int status = Cli.run(args, out, err);
            return new Invocation(status, stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        }
    }

    private void assertError(String[] args, String expectedError) {
        Invocation result = run(args);
        assertEquals(2, result.status());
        assertEquals("", result.stdout());
        assertEquals(expectedError, result.stderr());
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

    private static void report(PrintStream err, String message) {
        err.print("linehunk: ");
        err.print(message);
        err.print('\n');
    }

    private static void writeLines(Path path, List<String> lines) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, lines.isEmpty() ? "" : String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
    }

    private record TreePair(Path oldTree, Path newTree) {
    }

    private record Invocation(int status, String stdout, String stderr) {
    }
}
