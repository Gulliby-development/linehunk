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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-entry-point and documentation contract tests. */
class CliTest {
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
                try {
                    if (Files.isDirectory(oldPath)) {
                        String result = TreeDiff.diff(oldPath, newPath, context, jobs);
                        out.print(result);
                        return result.isEmpty() ? 0 : 1;
                    }
                    List<String> oldLines;
                    try {
                        oldLines = Lines.read(oldPath);
                    } catch (Lines.ReadException ex) {
                        report(err, oldArgument + ": " + readFailure(ex));
                        return 2;
                    }
                    List<String> newLines;
                    try {
                        newLines = Lines.read(newPath);
                    } catch (Lines.ReadException ex) {
                        report(err, newArgument + ": " + readFailure(ex));
                        return 2;
                    }
                    String result = UnifiedDiff.render(oldArgument, newArgument,
                            oldLines, newLines, context);
                    out.print(result);
                    return result.isEmpty() ? 0 : 1;
                } catch (Lines.ReadException ex) {
                    report(err, oldArgument + ": " + readFailure(ex));
                    return 2;
                } catch (TreeDiff.TreeDiffException ex) {
                    report(err, ex.getMessage());
                    return 2;
                } catch (IOException | SecurityException ex) {
                    report(err, oldArgument + ": cannot read");
                    return 2;
                }
            }

            @Override
            public int apply(String patch, String directory, boolean reverse, boolean check,
                             PrintStream out, PrintStream err) {
                return Applier.run(patch, directory, reverse, check, out, err);
            }
        });
    }

    private MainResult runMain(String... args) throws IOException, InterruptedException {
        String javaName = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", javaName);
        List<String> command = new java.util.ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Main.class.getName());
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Main subprocess did not finish within 30 seconds");
        }
        return new MainResult(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private record MainResult(int status, String output) {
    }

    @Test
    void mainLoadsBuiltInDiffAndApplyHandlersInFreshProcesses() throws IOException, InterruptedException {
        Path oldFile = tempDir.resolve("old-main.txt");
        Path newFile = tempDir.resolve("new-main.txt");
        writeLines(oldFile, List.of("old"));
        writeLines(newFile, List.of("new"));

        MainResult diff = runMain("diff", oldFile.toString(), newFile.toString());
        assertEquals(1, diff.status());
        assertEquals("--- " + oldFile + "\n+++ " + newFile + "\n"
                + "@@ -1,1 +1,1 @@\n-old\n+new\n", diff.output());

        Path applyRoot = tempDir.resolve("apply-main");
        Files.createDirectories(applyRoot);
        writeLines(applyRoot.resolve("file.txt"), List.of("old"));
        Path patch = tempDir.resolve("main.patch");
        Files.writeString(patch, "--- a/file.txt\n+++ b/file.txt\n"
                + "@@ -1,1 +1,1 @@\n-old\n+new\n", StandardCharsets.UTF_8);

        MainResult apply = runMain("apply", patch.toString(), applyRoot.toString());
        assertEquals(0, apply.status());
        assertEquals("patched file.txt\n1 file, 1 hunk\n", apply.output());
        assertEquals("new\n", Files.readString(applyRoot.resolve("file.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void helpAndUsageErrorsHaveExactStreamsAndStatuses() {
        Invocation help = run("--help");
        assertEquals(0, help.status());
        assertEquals("Usage: linehunk diff [-U N] [--jobs N] [--stat] A B\n"
                + "       linehunk apply [-R] [--check] PATCH DIR\n", help.stdout());
        assertEquals("", help.stderr());

        assertError(new String[0], "linehunk: command required\n");
        assertError(new String[]{"fetch"}, "linehunk: unknown command \"fetch\"\n");
        assertError(new String[]{"diff", "--jobs=2", "a", "b"},
                "linehunk: unknown option \"--jobs=2\"\n");
        assertError(new String[]{"diff", "-U"}, "linehunk: missing value for -U\n");
        assertError(new String[]{"diff", "-U", "-1", "a", "b"},
                "linehunk: invalid context \"-1\"\n");
        assertError(new String[]{"diff", "--jobs", "9", "a", "b"},
                "linehunk: invalid jobs \"9\"\n");
        assertError(new String[]{"diff", "a"}, "linehunk: exactly two paths required\n");
        assertError(new String[]{"apply", "p"}, "linehunk: exactly two arguments required\n");
    }

    @Test
    void optionErrorsAreSelectedLeftToRightAndValuesUseAsciiRanges() {
        assertError(new String[]{"diff", "--jobs"}, "linehunk: missing value for --jobs\n");
        assertError(new String[]{"diff", "-U", "-U", "3", "a", "b"},
                "linehunk: invalid context \"-U\"\n");
        assertError(new String[]{"diff", "--jobs", "0", "--mystery", "a", "b"},
                "linehunk: invalid jobs \"0\"\n");
        assertError(new String[]{"diff", "-U", "100", "a", "b"},
                "linehunk: invalid context \"100\"\n");
        assertError(new String[]{"diff", "-U", "١", "a", "b"},
                "linehunk: invalid context \"١\"\n");
        assertError(new String[]{"diff", "-R", "a", "b"},
                "linehunk: unknown option \"-R\"\n");
        assertError(new String[]{"apply", "-U", "p", "d"},
                "linehunk: unknown option \"-U\"\n");
        assertError(new String[]{"diff", "-"}, "linehunk: exactly two paths required\n");
    }

    @Test
    void optionsAreAcceptedAmongOperandsAndTheLastValueWins() throws IOException {
        Path oldFile = tempDir.resolve("old.txt");
        Path newFile = tempDir.resolve("new.txt");
        writeLines(oldFile, List.of("same", "old", "tail"));
        writeLines(newFile, List.of("same", "new", "tail"));

        Invocation result = run("diff", oldFile.toString(), "-U", "0",
                newFile.toString(), "--jobs", "1", "-U", "1");
        assertEquals(1, result.status());
        assertEquals("--- " + oldFile + "\n+++ " + newFile + "\n"
                + "@@ -1,3 +1,3 @@\n same\n-old\n+new\n tail\n", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void fileDiffMatchesUnifiedFormatAndNormalizesCrLfAgainstLf() throws IOException {
        Path oldFile = tempDir.resolve("a.txt");
        Path newFile = tempDir.resolve("b.txt");
        writeLines(oldFile, List.of("one", "two", "three", "four", "five", "six",
                "seven", "eight", "nine", "ten", "eleven", "twelve"));
        writeLines(newFile, List.of("one", "two", "three", "FOUR", "five", "six",
                "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen"));

        Invocation defaultContext = run("diff", oldFile.toString(), newFile.toString());
        assertEquals(1, defaultContext.status());
        assertEquals("--- " + oldFile + "\n+++ " + newFile + "\n"
                + "@@ -1,7 +1,7 @@\n"
                + " one\n two\n three\n-four\n+FOUR\n five\n six\n seven\n"
                + "@@ -10,3 +10,4 @@\n"
                + " ten\n eleven\n twelve\n+thirteen\n", defaultContext.stdout());
        assertEquals("", defaultContext.stderr());

        Invocation zeroContext = run("diff", "-U", "0", oldFile.toString(), newFile.toString());
        assertEquals(1, zeroContext.status());
        assertEquals("--- " + oldFile + "\n+++ " + newFile + "\n"
                + "@@ -4,1 +4,1 @@\n-four\n+FOUR\n"
                + "@@ -12,0 +13,1 @@\n+thirteen\n", zeroContext.stdout());

        Path crlf = tempDir.resolve("crlf.txt");
        Path lf = tempDir.resolve("lf.txt");
        Files.writeString(crlf, "first\r\nsecond\r\n", StandardCharsets.UTF_8);
        Files.writeString(lf, "first\nsecond\n", StandardCharsets.UTF_8);
        Invocation sameLines = run("diff", crlf.toString(), lf.toString());
        assertEquals(0, sameLines.status());
        assertEquals("", sameLines.stdout());
        assertEquals("", sameLines.stderr());
    }

    @Test
    void directoryDiffIsStableAcrossAllSupportedWorkerCounts() throws IOException {
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

        String expected = "--- a/edit.txt\n+++ b/edit.txt\n@@ -2,3 +2,3 @@\n"
                + " 2\n-3\n+three\n 4\n"
                + "--- a/empty-gone.txt\n+++ /dev/null\n"
                + "--- a/gone.txt\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-bye\n-now\n"
                + "--- /dev/null\n+++ b/new/empty.txt\n"
                + "--- /dev/null\n+++ b/new/sub.txt\n@@ -0,0 +1,2 @@\n+hello\n+world\n";

        for (int jobs = 1; jobs <= 8; jobs++) {
            Invocation result = run("diff", "-U", "1", "--jobs", Integer.toString(jobs),
                    oldTree.toString(), newTree.toString());
            assertEquals(1, result.status(), "jobs=" + jobs);
            assertEquals(expected, result.stdout(), "jobs=" + jobs);
            assertEquals("", result.stderr(), "jobs=" + jobs);
        }
    }

    @Test
    void directoryPatchCheckApplyRepeatAndReverseRoundTrip() throws IOException {
        Path oldTree = tempDir.resolve("A");
        Path newTree = tempDir.resolve("B");
        Path targetTree = tempDir.resolve("C");
        Files.createDirectories(oldTree);
        Files.createDirectories(newTree);
        Files.createDirectories(targetTree);
        writeLines(oldTree.resolve("edit.txt"), List.of("1", "2", "3", "4", "5"));
        Files.writeString(oldTree.resolve("same.txt"), "x\ny\n", StandardCharsets.UTF_8);
        writeLines(oldTree.resolve("gone.txt"), List.of("bye", "now"));
        Files.createFile(oldTree.resolve("empty-gone.txt"));

        writeLines(newTree.resolve("edit.txt"), List.of("1", "2", "three", "4", "5"));
        Files.writeString(newTree.resolve("same.txt"), "x\r\ny\r\n", StandardCharsets.UTF_8);
        writeLines(newTree.resolve("new/sub.txt"), List.of("hello", "world"));
        Files.createDirectories(newTree.resolve("new"));
        Files.createFile(newTree.resolve("new/empty.txt"));
        copyTree(oldTree, targetTree);

        Invocation diff = run("diff", "-U", "1", oldTree.toString(), newTree.toString());
        assertEquals(1, diff.status());
        Path patch = tempDir.resolve("p.diff");
        Files.writeString(patch, diff.stdout(), StandardCharsets.UTF_8);

        Map<String, String> beforeCheck = snapshot(targetTree);
        Invocation check = run("apply", "--check", patch.toString(), targetTree.toString());
        assertEquals(0, check.status());
        assertEquals("would patch edit.txt\n"
                + "would delete empty-gone.txt\n"
                + "would delete gone.txt\n"
                + "would create new/empty.txt\n"
                + "would create new/sub.txt\n"
                + "5 files, 3 hunks\n", check.stdout());
        assertEquals("", check.stderr());
        assertEquals(beforeCheck, snapshot(targetTree));

        Invocation applied = run("apply", patch.toString(), targetTree.toString());
        assertEquals(0, applied.status());
        assertEquals("patched edit.txt\ndeleted empty-gone.txt\ndeleted gone.txt\n"
                + "created new/empty.txt\ncreated new/sub.txt\n5 files, 3 hunks\n", applied.stdout());
        assertEquals("", applied.stderr());
        Invocation matchesNew = run("diff", newTree.toString(), targetTree.toString());
        assertEquals(0, matchesNew.status());
        assertEquals("", matchesNew.stdout());

        Invocation repeated = run("apply", patch.toString(), targetTree.toString());
        assertEquals(2, repeated.status());
        assertEquals("", repeated.stdout());
        assertEquals("linehunk: edit.txt: hunk 1 does not apply at line 2\n", repeated.stderr());

        Invocation reversed = run("apply", "-R", patch.toString(), targetTree.toString());
        assertEquals(0, reversed.status());
        assertEquals("patched edit.txt\ncreated empty-gone.txt\ncreated gone.txt\n"
                + "deleted new/empty.txt\ndeleted new/sub.txt\n5 files, 3 hunks\n", reversed.stdout());
        Invocation matchesOld = run("diff", oldTree.toString(), targetTree.toString());
        assertEquals(0, matchesOld.status());
        assertEquals("", matchesOld.stdout());
    }

    @Test
    void modeAndTreeReadErrorsKeepStdoutEmptyAndUseRequiredPaths() throws IOException {
        Path file = tempDir.resolve("file.txt");
        Path directory = tempDir.resolve("dir");
        Path missing = tempDir.resolve("missing");
        Files.writeString(file, "x\n", StandardCharsets.UTF_8);
        Files.createDirectories(directory);

        assertError(new String[]{"diff", missing.toString(), file.toString()},
                "linehunk: " + missing + ": no such file or directory\n");
        assertError(new String[]{"diff", file.toString(), directory.toString()},
                "linehunk: " + file + " and " + directory
                        + " must both be files or both be directories\n");
        assertError(new String[]{"apply", missing.toString(), file.toString()},
                "linehunk: " + file + ": not a directory\n");

        Path oldTree = tempDir.resolve("bad-A");
        Path newTree = tempDir.resolve("bad-B");
        Files.createDirectories(oldTree);
        Files.createDirectories(newTree);
        Files.write(oldTree.resolve("z-bad.txt"), new byte[]{(byte) 0xc3, (byte) 0x28});
        Files.write(oldTree.resolve("a-bad.txt"), new byte[]{(byte) 0xc3, (byte) 0x28});
        Invocation invalidUtf8 = run("diff", oldTree.toString(), newTree.toString());
        assertEquals(2, invalidUtf8.status());
        assertEquals("", invalidUtf8.stdout());
        assertEquals("linehunk: a/a-bad.txt: not valid UTF-8\n", invalidUtf8.stderr());
    }

    @Test
    void failedLaterApplySectionLeavesEarlierFilesUnchanged() throws IOException {
        Path root = tempDir.resolve("transaction");
        Files.createDirectories(root);
        writeLines(root.resolve("first.txt"), List.of("old"));
        writeLines(root.resolve("second.txt"), List.of("wrong"));
        Map<String, String> before = snapshot(root);
        Path patch = tempDir.resolve("transaction.patch");
        Files.writeString(patch,
                "--- a/first.txt\n+++ b/first.txt\n@@ -1,1 +1,1 @@\n-old\n+new\n"
                        + "--- a/second.txt\n+++ b/second.txt\n@@ -1,1 +1,1 @@\n-expected\n+next\n",
                StandardCharsets.UTF_8);

        Invocation result = run("apply", patch.toString(), root.toString());
        assertEquals(2, result.status());
        assertEquals("", result.stdout());
        assertEquals("linehunk: second.txt: hunk 1 does not apply at line 1\n", result.stderr());
        assertEquals(before, snapshot(root));
    }

    @Test
    void readmeContainsRequiredHeadingsAndWorkedDiffExamples() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        for (String heading : List.of("## Build", "## Usage",
                "## The algorithm in plain words", "## Patch format",
                "## Exit codes", "## Development")) {
            assertTrue(readme.contains(heading), "missing README heading: " + heading);
        }

        String fileExample = "--- a.txt\n+++ b.txt\n@@ -1,7 +1,7 @@\n"
                + " one\n two\n three\n-four\n+FOUR\n five\n six\n seven\n"
                + "@@ -10,3 +10,4 @@\n ten\n eleven\n twelve\n+thirteen\n";
        String directoryExample = "--- a/edit.txt\n+++ b/edit.txt\n@@ -2,3 +2,3 @@\n"
                + " 2\n-3\n+three\n 4\n"
                + "--- a/empty-gone.txt\n+++ /dev/null\n"
                + "--- a/gone.txt\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-bye\n-now\n"
                + "--- /dev/null\n+++ b/new/empty.txt\n"
                + "--- /dev/null\n+++ b/new/sub.txt\n@@ -0,0 +1,2 @@\n+hello\n+world\n";
        assertTrue(readme.contains(fileExample), "README file example must match the rendered output");
        assertTrue(readme.contains(directoryExample),
                "README directory example must match the rendered output");
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

    private static void copyTree(Path source, Path destination) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path sourcePath : paths.toList()) {
                Path targetPath = destination.resolve(source.relativize(sourcePath));
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(sourcePath, targetPath);
                }
            }
        }
    }

    private static String readFailure(Lines.ReadException exception) {
        return switch (exception.failure()) {
            case INVALID_UTF8 -> "not valid UTF-8";
            case TOO_MANY_LINES -> "more than 2000 lines";
        };
    }

    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path).toString(),
                        Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            }
        }
        return files;
    }

    private record Invocation(int status, String stdout, String stderr) {
    }
}
