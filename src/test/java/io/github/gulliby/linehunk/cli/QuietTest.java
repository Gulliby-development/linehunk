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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuietTest {
    @TempDir
    Path tempDir;

    private final List<DiffCall> diffCalls = new ArrayList<>();

    @BeforeEach
    void connectCommandImplementations() {
        diffCalls.clear();
        Cli.setCommandHandler(new Cli.CommandHandler() {
            @Override
            public int diff(String oldArgument, String newArgument, int context, int jobs,
                            PrintStream out, PrintStream err) {
                diffCalls.add(new DiffCall(oldArgument, newArgument, context, jobs));
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
                String result = UnifiedDiff.render(oldArgument, newArgument,
                        oldLines, newLines, context);
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
    void bothQuietSpellingsReportDifferentAndEqualFilesOnlyByStatus() throws IOException {
        Path oldFile = tempDir.resolve("a.txt");
        Path changedFile = tempDir.resolve("b.txt");
        Path sameFile = tempDir.resolve("c.txt");
        writeLines(oldFile, "one", "two", "three");
        writeLines(changedFile, "one", "TWO", "three");
        Files.copy(oldFile, sameFile);

        assertQuietResult(run("diff", "-q", oldFile.toString(), changedFile.toString()), 1);
        assertQuietResult(run("diff", oldFile.toString(), changedFile.toString(), "--quiet"), 1);
        assertQuietResult(run("diff", "--quiet", oldFile.toString(), sameFile.toString()), 0);
        assertEquals(3, diffCalls.size());
    }

    @Test
    void quietMayAppearBeforeBetweenOrAfterOperandsAndRepeatInEitherSpelling() throws IOException {
        Path oldFile = tempDir.resolve("old.txt");
        Path newFile = tempDir.resolve("new.txt");
        writeLines(oldFile, "old");
        writeLines(newFile, "new");

        assertQuietResult(run("diff", "--quiet", oldFile.toString(), newFile.toString()), 1);
        assertQuietResult(run("diff", oldFile.toString(), "-q", newFile.toString()), 1);
        assertQuietResult(run("diff", oldFile.toString(), newFile.toString(), "-q"), 1);
        assertQuietResult(run("diff", "-q", oldFile.toString(), "--quiet",
                newFile.toString(), "-q"), 1);
        assertEquals(4, diffCalls.size());
    }

    @Test
    void quietKeepsEmptyLineEndingAndBomNormalization() throws IOException {
        Path emptyA = tempDir.resolve("empty-a.txt");
        Path emptyB = tempDir.resolve("empty-b.txt");
        Path nonempty = tempDir.resolve("nonempty.txt");
        writeLines(emptyA);
        writeLines(emptyB);
        writeLines(nonempty, "line");
        assertQuietResult(run("diff", "-q", emptyA.toString(), emptyB.toString()), 0);
        assertQuietResult(run("diff", "-q", emptyA.toString(), nonempty.toString()), 1);

        Path lf = tempDir.resolve("lf.txt");
        Path crlf = tempDir.resolve("crlf.txt");
        Files.writeString(lf, "first\nsecond\n", StandardCharsets.UTF_8);
        Files.writeString(crlf, "first\r\nsecond\r\n", StandardCharsets.UTF_8);
        assertQuietResult(run("diff", "--quiet", lf.toString(), crlf.toString()), 0);

        Path bom = tempDir.resolve("bom.txt");
        Path plain = tempDir.resolve("plain.txt");
        Files.write(bom, new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf,
                (byte) 'o', (byte) 'n', (byte) 'e', (byte) '\n'});
        Files.writeString(plain, "one\n", StandardCharsets.UTF_8);
        assertQuietResult(run("diff", "-q", bom.toString(), plain.toString()), 0);
    }

    @Test
    void quietTreeDiffUsesRequestedJobsAndContextAndHandlesEmptyChanges() throws IOException {
        Path oldTree = tempDir.resolve("A");
        Path newTree = tempDir.resolve("B");
        Files.createDirectories(oldTree);
        Files.createDirectories(newTree);
        writeLines(oldTree.resolve("base.txt"), "same");
        writeLines(newTree.resolve("base.txt"), "same");
        writeLines(oldTree.resolve("shared.txt"), "same");
        writeLines(newTree.resolve("shared.txt"), "same");

        assertQuietResult(run("diff", "--quiet", "--jobs", "4",
                oldTree.toString(), newTree.toString()), 0);

        writeLines(newTree.resolve("base.txt"), "changed");
        writeLines(oldTree.resolve("deleted.txt"), "removed");
        writeLines(oldTree.resolve("deleted-empty.txt"));
        writeLines(newTree.resolve("added.txt"), "created");
        writeLines(newTree.resolve("added-empty.txt"));

        assertQuietResult(run("diff", "-q", "-U", "0", "--jobs", "1",
                oldTree.toString(), newTree.toString()), 1);
        assertQuietResult(run("diff", oldTree.toString(), "--quiet", "-U", "3",
                newTree.toString(), "--jobs", "4"), 1);
        assertQuietResult(run("diff", "--jobs", "8", "-U", "99", "-q",
                oldTree.toString(), newTree.toString()), 1);

        assertEquals(4, diffCalls.size());
        assertEquals(new DiffCall(oldTree.toString(), newTree.toString(), 3, 4), diffCalls.get(0));
        assertEquals(new DiffCall(oldTree.toString(), newTree.toString(), 0, 1), diffCalls.get(1));
        assertEquals(new DiffCall(oldTree.toString(), newTree.toString(), 3, 4), diffCalls.get(2));
        assertEquals(new DiffCall(oldTree.toString(), newTree.toString(), 99, 8), diffCalls.get(3));
    }

    @Test
    void quietConflictsAndScanErrorsFollowRequiredPrecedence() {
        assertError(new String[]{"diff", "--mystery", "-q", "--stat", "a", "b"},
                "unknown option \"--mystery\"");
        assertError(new String[]{"diff", "-q", "--stat", "-U"},
                "missing value for -U");
        assertError(new String[]{"diff", "--quiet", "--name-only", "--jobs"},
                "missing value for --jobs");
        assertError(new String[]{"diff", "-q", "--stat", "-U", "100", "a", "b"},
                "invalid context \"100\"");
        assertError(new String[]{"diff", "-q", "--stat", "--jobs", "0", "a", "b"},
                "invalid jobs \"0\"");
        assertError(new String[]{"diff", "--jobs", "9", "--quiet", "--name-only", "a", "b"},
                "invalid jobs \"9\"");
        assertError(new String[]{"diff", "--stat", "--name-only", "-q", "a", "b"},
                "--stat and --name-only cannot be combined");
        assertError(new String[]{"diff", "-q", "--stat", "a", "b"},
                "--quiet cannot be combined with --stat or --name-only");
        assertError(new String[]{"diff", "--name-only", "a", "-q", "b"},
                "--quiet cannot be combined with --stat or --name-only");
        assertError(new String[]{"diff", "-q", "--stat"},
                "--quiet cannot be combined with --stat or --name-only");
        assertError(new String[]{"diff", "-q"}, "exactly two paths required");
        assertError(new String[]{"diff", "-q", "missing"}, "exactly two paths required");
        assertError(new String[]{"diff", "-q", "missing-a", "missing-b", "extra"},
                "exactly two paths required");
    }

    @Test
    void quietOptionsAreExactAndRejectedByApply() {
        assertError(new String[]{"diff", "--quiet=1", "a", "b"},
                "unknown option \"--quiet=1\"");
        assertError(new String[]{"diff", "-qU", "a", "b"},
                "unknown option \"-qU\"");
        assertError(new String[]{"apply", "-q", "patch", "directory"},
                "unknown option \"-q\"");
        assertError(new String[]{"apply", "--quiet", "patch", "directory"},
                "unknown option \"--quiet\"");
    }

    @Test
    void quietPreservesPathAndReadErrorsFromEitherSideAndTrees() throws IOException {
        Path valid = tempDir.resolve("valid.txt");
        writeLines(valid, "valid");
        Path missing = tempDir.resolve("missing.txt");
        assertQuietErrorMatchesNormal(valid.toString(), missing.toString(),
                "linehunk: " + missing + ": no such file or directory\n");

        Path directory = tempDir.resolve("directory");
        Files.createDirectories(directory);
        assertQuietErrorMatchesNormal(valid.toString(), directory.toString(),
                "linehunk: " + valid + " and " + directory
                        + " must both be files or both be directories\n");

        Path invalidOld = tempDir.resolve("invalid-old.txt");
        Path invalidNew = tempDir.resolve("invalid-new.txt");
        byte[] malformed = {(byte) 0xc3, (byte) 0x28};
        Files.write(invalidOld, malformed);
        Files.write(invalidNew, malformed);
        assertQuietErrorMatchesNormal(invalidOld.toString(), valid.toString(),
                "linehunk: " + invalidOld + ": not valid UTF-8\n");
        assertQuietErrorMatchesNormal(valid.toString(), invalidNew.toString(),
                "linehunk: " + invalidNew + ": not valid UTF-8\n");
        assertQuietErrorMatchesNormal(invalidOld.toString(), invalidNew.toString(),
                "linehunk: " + invalidOld + ": not valid UTF-8\n");

        Path tooManyOld = tempDir.resolve("too-many-old.txt");
        Path tooManyNew = tempDir.resolve("too-many-new.txt");
        String longInput = String.join("\n", Collections.nCopies(2001, "line")) + "\n";
        Files.writeString(tooManyOld, longInput, StandardCharsets.UTF_8);
        Files.writeString(tooManyNew, longInput, StandardCharsets.UTF_8);
        assertQuietErrorMatchesNormal(tooManyOld.toString(), valid.toString(),
                "linehunk: " + tooManyOld + ": more than 2000 lines\n");
        assertQuietErrorMatchesNormal(valid.toString(), tooManyNew.toString(),
                "linehunk: " + tooManyNew + ": more than 2000 lines\n");

        Path oldTree = tempDir.resolve("old-tree");
        Path newTree = tempDir.resolve("new-tree");
        writeLines(oldTree.resolve("a-change.txt"), "old");
        writeLines(newTree.resolve("a-change.txt"), "new");
        Files.write(oldTree.resolve("z-invalid.txt"), malformed);
        writeLines(newTree.resolve("z-invalid.txt"), "valid");
        assertQuietErrorMatchesNormal(oldTree.toString(), newTree.toString(),
                "linehunk: a/z-invalid.txt: not valid UTF-8\n");
    }

    @Test
    void ordinaryDiffAndHelpRetainExactOutputBytes() throws IOException {
        Path oldFile = tempDir.resolve("a.txt");
        Path newFile = tempDir.resolve("b.txt");
        writeLines(oldFile, "one", "two", "three");
        writeLines(newFile, "one", "TWO", "three");

        Invocation ordinary = run("diff", oldFile.toString(), newFile.toString());
        assertEquals(1, ordinary.status());
        assertEquals("--- " + oldFile + "\n+++ " + newFile + "\n"
                + "@@ -1,3 +1,3 @@\n one\n-two\n+TWO\n three\n", ordinary.stdout());
        assertEquals("", ordinary.stderr());

        Invocation help = run("--help");
        assertEquals(0, help.status());
        assertEquals("Usage: linehunk diff [-U N] [--jobs N] [--stat | --name-only | -q] A B\n"
                + "       linehunk apply [-R] [--check] PATCH DIR\n", help.stdout());
        assertEquals("", help.stderr());
    }

    @Test
    void readmeDocumentsQuietUsageAndItsPlacementInTheOptionsParagraph() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        String firstUsage = "linehunk diff [-U N] [--jobs N] [--stat | --name-only | -q] A B";
        String helpUsage = "Usage: linehunk diff [-U N] [--jobs N] [--stat | --name-only | -q] A B";
        String quietDescription = "`-q` or `--quiet` (diff only) prints nothing at all and reports the result "
                + "in the exit code; it cannot be combined with `--stat` or `--name-only`.";
        String helpSentence = "Use `--help` by itself for the two-line usage message.";

        assertTrue(readme.contains(firstUsage));
        assertTrue(readme.contains(helpUsage));
        assertTrue(readme.contains(quietDescription));
        assertTrue(readme.indexOf(quietDescription) < readme.indexOf(helpSentence));
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

    private void writeLines(Path path, String... lines) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String text = lines.length == 0 ? "" : String.join("\n", lines) + "\n";
        Files.writeString(path, text, StandardCharsets.UTF_8);
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

    private void assertQuietResult(Invocation result, int expectedStatus) {
        assertEquals(expectedStatus, result.status());
        assertEquals("", result.stdout());
        assertEquals("", result.stderr());
    }

    private void assertError(String[] args, String expectedMessage) {
        Invocation result = run(args);
        assertEquals(2, result.status());
        assertEquals("", result.stdout());
        assertEquals("linehunk: " + expectedMessage + "\n", result.stderr());
    }

    private void assertQuietErrorMatchesNormal(String oldPath, String newPath, String expectedError) {
        Invocation normal = run("diff", oldPath, newPath);
        Invocation quiet = run("diff", "-q", oldPath, newPath);
        assertEquals(2, normal.status());
        assertEquals("", normal.stdout());
        assertEquals(expectedError, normal.stderr());
        assertEquals(2, quiet.status());
        assertEquals("", quiet.stdout());
        assertEquals(normal.stderr(), quiet.stderr());
    }

    private record Invocation(int status, String stdout, String stderr) {
    }

    private record DiffCall(String oldPath, String newPath, int context, int jobs) {
    }
}
