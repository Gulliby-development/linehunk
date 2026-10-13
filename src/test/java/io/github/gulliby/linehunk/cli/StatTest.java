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

class StatTest {
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
    void fileSummaryUsesSecondOperandAndReportsAddedAndRemovedLines() throws IOException {
        Path oldFile = tempDir.resolve("a.txt");
        Path newFile = tempDir.resolve("b.txt");
        writeLines(oldFile, List.of("one", "two", "three", "four", "five", "six",
                "seven", "eight", "nine", "ten", "eleven", "twelve"));
        writeLines(newFile, List.of("one", "two", "three", "FOUR", "five", "six",
                "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen"));

        Invocation result = run("diff", "--stat", oldFile.toString(), newFile.toString());

        assertEquals(1, result.status());
        assertEquals(newFile + " | +2 -1\n1 file changed, +2 -1\n", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void directorySummaryIsSortedAndAlignsNamesIncludingEmptyCreatesAndDeletes() throws IOException {
        Path oldTree = tempDir.resolve("A");
        Path newTree = tempDir.resolve("B");
        Files.createDirectories(oldTree);
        Files.createDirectories(newTree);
        writeLines(oldTree.resolve("edit.txt"), List.of("1", "2", "3", "4", "5"));
        Files.writeString(oldTree.resolve("same.txt"), "x\ny\n", StandardCharsets.UTF_8);
        Files.writeString(newTree.resolve("same.txt"), "x\r\ny\r\n", StandardCharsets.UTF_8);
        writeLines(oldTree.resolve("gone.txt"), List.of("bye", "now"));
        Files.createFile(oldTree.resolve("empty-gone.txt"));
        writeLines(newTree.resolve("edit.txt"), List.of("1", "2", "three", "4", "5"));
        writeLines(newTree.resolve("new/sub.txt"), List.of("hello", "world"));
        Files.createDirectories(newTree.resolve("new"));
        Files.createFile(newTree.resolve("new/empty.txt"));

        Invocation result = run("diff", "--stat", oldTree.toString(), newTree.toString());

        assertEquals(1, result.status());
        assertEquals("edit.txt       | +1 -1\n"
                + "empty-gone.txt | +0 -0\n"
                + "gone.txt       | +0 -2\n"
                + "new/empty.txt  | +0 -0\n"
                + "new/sub.txt    | +2 -0\n"
                + "5 files changed, +3 -3\n", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void statIsAcceptedBeforeBetweenAndAfterOperandsAndRepeatedOptionsAreHarmless()
            throws IOException {
        Path oldFile = tempDir.resolve("old.txt");
        Path newFile = tempDir.resolve("new.txt");
        writeLines(oldFile, List.of("keep", "old", "tail"));
        writeLines(newFile, List.of("keep", "new", "tail"));
        String expected = newFile + " | +1 -1\n1 file changed, +1 -1\n";

        for (String[] args : List.of(
                new String[]{"diff", "--stat", oldFile.toString(), newFile.toString()},
                new String[]{"diff", oldFile.toString(), "--stat", newFile.toString()},
                new String[]{"diff", oldFile.toString(), newFile.toString(), "--stat"},
                new String[]{"diff", "--stat", oldFile.toString(), newFile.toString(),
                        "--stat", "--stat"})) {
            Invocation result = run(args);
            assertEquals(1, result.status());
            assertEquals(expected, result.stdout());
            assertEquals("", result.stderr());
        }
    }

    @Test
    void summaryDoesNotDependOnContextOrWorkerCount() throws IOException {
        Path oldTree = tempDir.resolve("A");
        Path newTree = tempDir.resolve("B");
        Path oldFile = oldTree.resolve("nested/change.txt");
        Path newFile = newTree.resolve("nested/change.txt");
        writeLines(oldFile, List.of("first", "old", "last"));
        writeLines(newFile, List.of("first", "new", "last"));
        String expected = "nested/change.txt | +1 -1\n1 file changed, +1 -1\n";

        for (String[] options : List.of(
                new String[]{"-U", "0"},
                new String[]{"-U", "9"},
                new String[]{"--jobs", "1"},
                new String[]{"--jobs", "8"},
                new String[]{"-U", "0", "--jobs", "1"},
                new String[]{"-U", "9", "--jobs", "8"})) {
            String[] args = new String[options.length + 4];
            args[0] = "diff";
            args[1] = oldTree.toString();
            System.arraycopy(options, 0, args, 2, options.length);
            args[args.length - 2] = newTree.toString();
            args[args.length - 1] = "--stat";
            Invocation result = run(args);
            assertEquals(1, result.status());
            assertEquals(expected, result.stdout());
            assertEquals("", result.stderr());
        }
    }

    @Test
    void identicalFilesAndLineEndingEquivalentFilesPrintNothing() throws IOException {
        Path sameFile = tempDir.resolve("same.txt");
        writeLines(sameFile, List.of("same", "content"));
        Invocation same = run("diff", "--stat", sameFile.toString(), sameFile.toString());
        assertEquals(0, same.status());
        assertEquals("", same.stdout());
        assertEquals("", same.stderr());

        Path crlf = tempDir.resolve("crlf.txt");
        Path lf = tempDir.resolve("lf.txt");
        Files.writeString(crlf, "first\r\nsecond\r\n", StandardCharsets.UTF_8);
        Files.writeString(lf, "first\nsecond\n", StandardCharsets.UTF_8);
        Invocation equivalent = run("diff", crlf.toString(), "--stat", lf.toString());
        assertEquals(0, equivalent.status());
        assertEquals("", equivalent.stdout());
        assertEquals("", equivalent.stderr());
    }

    @Test
    void contentThatLooksLikeSectionHeadersIsCountedAsHunkContent() throws IOException {
        Path oldFile = tempDir.resolve("OLD");
        Path newFile = tempDir.resolve("NEW");
        writeLines(oldFile, List.of("-- gone", "keep"));
        writeLines(newFile, List.of("++ new", "keep"));

        Invocation result = run("diff", "--stat", oldFile.toString(), newFile.toString());

        assertEquals(1, result.status());
        assertEquals(newFile + " | +1 -1\n1 file changed, +1 -1\n", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void optionAndReadErrorsKeepStdoutEmptyAndUseExactDiagnostics() throws IOException {
        Path invalid = tempDir.resolve("invalid.txt");
        Path valid = tempDir.resolve("valid.txt");
        Files.write(invalid, new byte[]{(byte) 0xc3, (byte) 0x28});
        writeLines(valid, List.of("valid"));

        assertError(new String[]{"diff", "--stat", invalid.toString(), valid.toString()},
                "linehunk: " + invalid + ": not valid UTF-8\n");
        assertError(new String[]{"diff", "--stat=1", "a", "b"},
                "linehunk: unknown option \"--stat=1\"\n");
        assertError(new String[]{"apply", "--stat", "patch", "directory"},
                "linehunk: unknown option \"--stat\"\n");
        assertError(new String[]{"diff", "--stat", "only-one"},
                "linehunk: exactly two paths required\n");
    }

    @Test
    void readmeDocumentsTheUsageLineAndBothSummaryExamples() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");

        assertTrue(readme.contains("linehunk diff [-U N] [--jobs N] [--stat] A B"));
        assertTrue(readme.contains("### Summary with --stat"));
        assertTrue(readme.contains("b.txt | +2 -1\n1 file changed, +2 -1\n"));
        assertTrue(readme.contains("edit.txt       | +1 -1\n"
                + "empty-gone.txt | +0 -0\n"
                + "gone.txt       | +0 -2\n"
                + "new/empty.txt  | +0 -0\n"
                + "new/sub.txt    | +2 -0\n"
                + "5 files changed, +3 -3\n"));
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

    private record Invocation(int status, String stdout, String stderr) {
    }
}
