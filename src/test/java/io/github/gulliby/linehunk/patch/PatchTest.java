package io.github.gulliby.linehunk.patch;

import io.github.gulliby.linehunk.diff.Lines;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchTest {
    @TempDir
    Path tempDir;

    @Test
    void parserAcceptsModifyCreateDeleteAndEmptyContextForms() throws Exception {
        List<FilePatch> sections = PatchParser.parse(List.of(
                "--- a/nested/file.txt",
                "+++ b/nested/file.txt",
                "@@ -1,1 +1,1 @@",
                "",
                "--- /dev/null",
                "+++ b/empty-create.txt",
                "--- a/empty-delete.txt",
                "+++ /dev/null",
                "--- /dev/null",
                "+++ b/start.txt",
                "@@ -0,0 +1,1 @@",
                "+first"));

        assertEquals(4, sections.size());
        assertEquals(FilePatch.Kind.MODIFY, sections.get(0).kind());
        assertEquals("nested/file.txt", sections.get(0).relativePath());
        assertEquals("", sections.get(0).hunks().get(0).lines().get(0).text());
        assertEquals(' ', sections.get(0).hunks().get(0).lines().get(0).kind());
        assertEquals(FilePatch.Kind.CREATE, sections.get(1).kind());
        assertTrue(sections.get(1).hunks().isEmpty());
        assertEquals(FilePatch.Kind.DELETE, sections.get(2).kind());
        assertTrue(sections.get(2).hunks().isEmpty());
        assertEquals(0, sections.get(3).hunks().get(0).oldStart());
        assertEquals(0, sections.get(3).hunks().get(0).oldCount());
    }

    @Test
    void parserAndApplierAcceptEmptyContextLineReadThroughLines() throws Exception {
        Path file = tempDir.resolve("file.txt");
        Files.writeString(file, "\nold\n", StandardCharsets.UTF_8);
        Path patchFile = tempDir.resolve("empty-context.diff");
        Files.writeString(patchFile, String.join("\n", List.of(
                "--- a/file.txt",
                "+++ b/file.txt",
                "@@ -1,2 +1,2 @@",
                "",
                "-old",
                "+new")), StandardCharsets.UTF_8);

        List<FilePatch> sections = PatchParser.parse(Lines.read(patchFile, Lines.PATCH_LIMIT));
        FilePatch.Line emptyContext = sections.get(0).hunks().get(0).lines().get(0);
        assertEquals(' ', emptyContext.kind());
        assertEquals("", emptyContext.text());

        Applier.apply(tempDir, sections, false, false);
        assertEquals("\nnew\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void parserReportsEverySpecifiedErrorAtItsFirstOffendingLine() {
        List<ParserFailure> failures = List.of(
                new ParserFailure(List.of("@@ -1,1 +1,1 @@"), 1, "expected \"--- \" header"),
                new ParserFailure(List.of("--- a/file.txt"), 2, "expected \"+++ \" header"),
                new ParserFailure(List.of("--- a/file.txt", "+++ b/other.txt"), 1,
                        "unsupported file header"),
                new ParserFailure(List.of("--- a/../file.txt", "+++ b/../file.txt"), 1,
                        "unsafe path"),
                new ParserFailure(List.of("--- a/a//file.txt", "+++ b/a//file.txt"), 1,
                        "unsafe path"),
                new ParserFailure(List.of("--- a/a/./file.txt", "+++ b/a/./file.txt"), 1,
                        "unsafe path"),
                new ParserFailure(List.of("--- a/a\\\\file.txt", "+++ b/a\\\\file.txt"), 1,
                        "unsafe path"),
                new ParserFailure(List.of("--- a/a:file.txt", "+++ b/a:file.txt"), 1,
                        "unsafe path"),
                new ParserFailure(List.of(
                        "--- /dev/null", "+++ b/file.txt",
                        "--- /dev/null", "+++ b/file.txt"), 3, "duplicate file file.txt"),
                new ParserFailure(List.of("--- a/file.txt", "+++ b/file.txt"), 1,
                        "file section has no hunks"),
                new ParserFailure(List.of(
                        "--- a/file.txt", "+++ b/file.txt", "@@ -1 +1 @@"), 3,
                        "malformed hunk header"),
                new ParserFailure(List.of(
                        "--- a/file.txt", "+++ b/file.txt", "@@ -0,0 +0,0 @@"), 3,
                        "empty hunk"),
                new ParserFailure(List.of(
                        "--- a/file.txt", "+++ b/file.txt",
                        "@@ -1,1 +1,1 @@", "?bad"), 4, "unexpected line in hunk"),
                new ParserFailure(List.of(
                        "--- a/file.txt", "+++ b/file.txt",
                        "@@ -1,1 +1,2 @@", "-old", " context"), 5,
                        "hunk has more lines than its header says"),
                new ParserFailure(List.of(
                        "--- a/file.txt", "+++ b/file.txt",
                        "@@ -1,1 +1,1 @@", "-old"), 5,
                        "hunk has fewer lines than its header says"),
                new ParserFailure(List.of(
                        "--- a/file.txt", "+++ b/file.txt",
                        "@@ -1,1 +1,1 @@", "-old", "+new", "+extra"), 6,
                        "unexpected line after hunk"),
                new ParserFailure(List.of(
                        "--- a/file.txt", "+++ b/file.txt",
                        "@@ -0,1 +1,1 @@", "-old", "+new"), 3,
                        "malformed hunk header"),
                new ParserFailure(List.of(
                        "--- a/file.txt", "+++ b/file.txt",
                        "@@ -1,1 +0,1 @@", "-old", "+new"), 3,
                        "malformed hunk header"),
                new ParserFailure(List.of(
                        "--- a/file.txt", "+++ b/file.txt",
                        "@@ -1,1000000000 +1,1 @@"), 3, "malformed hunk header"),
                new ParserFailure(List.of(
                        "--- a/file.txt", "+++ b/file.txt",
                        "@@ -2,1 +2,1 @@", "-x", "+y",
                        "@@ -1,1 +1,1 @@", "-x", "+y"), 6,
                        "hunks out of order or overlapping"));

        for (ParserFailure failure : failures) {
            PatchException error = assertThrows(
                    PatchException.class, () -> PatchParser.parse(failure.lines()),
                    failure.lines().toString());
            assertEquals(failure.line(), error.lineNumber(), failure.lines().toString());
            assertEquals(failure.message(), error.getMessage(), failure.lines().toString());
        }
    }

    @Test
    void emptyPatchHasNoLineNumber() {
        PatchException error = assertThrows(
                PatchException.class, () -> PatchParser.parse(List.of()));
        assertEquals("empty patch", error.getMessage());
        assertFalse(error.hasLineNumber());
    }

    @Test
    void applyAndReverseModifyAtExactLocationsAndNormalizeToLf() throws Exception {
        Path file = tempDir.resolve("file.txt");
        Files.writeString(file, "one\r\ntwo\r\nthree", StandardCharsets.UTF_8);
        List<FilePatch> patch = parse(
                "--- a/file.txt",
                "+++ b/file.txt",
                "@@ -2,1 +2,1 @@",
                "-two",
                "+TWO");

        Applier.Result forward = Applier.apply(tempDir, patch, false, false);
        assertEquals(List.of("patched file.txt"), forward.statusLines());
        assertEquals("one\nTWO\nthree\n", Files.readString(file, StandardCharsets.UTF_8));

        Applier.Result reverse = Applier.apply(tempDir, patch, true, false);
        assertEquals(List.of("patched file.txt"), reverse.statusLines());
        assertEquals("one\ntwo\nthree\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void insertsAtStartAndEndAndReversesExactly() throws Exception {
        Path file = tempDir.resolve("file.txt");
        writeLines(file, "alpha", "omega");
        List<FilePatch> patch = parse(
                "--- a/file.txt",
                "+++ b/file.txt",
                "@@ -0,0 +1,1 @@",
                "+first",
                "@@ -2,0 +4,1 @@",
                "+last");

        Applier.apply(tempDir, patch, false, false);
        assertEquals("first\nalpha\nomega\nlast\n", Files.readString(file, StandardCharsets.UTF_8));
        Applier.apply(tempDir, patch, true, false);
        assertEquals("alpha\nomega\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void createAndDeleteEmptyFilesUseZeroBytesAndReverseKinds() throws Exception {
        List<FilePatch> create = parse("--- /dev/null", "+++ b/empty.txt");
        Applier.Result created = Applier.apply(tempDir, create, false, false);
        Path empty = tempDir.resolve("empty.txt");
        assertEquals(List.of("created empty.txt"), created.statusLines());
        assertTrue(Files.exists(empty));
        assertArrayEquals(new byte[0], Files.readAllBytes(empty));

        Applier.Result reversed = Applier.apply(tempDir, create, true, false);
        assertEquals(List.of("deleted empty.txt"), reversed.statusLines());
        assertFalse(Files.exists(empty));

        Path existingEmpty = tempDir.resolve("existing-empty.txt");
        Files.write(existingEmpty, new byte[0]);
        List<FilePatch> delete = parse("--- a/existing-empty.txt", "+++ /dev/null");
        Applier.apply(tempDir, delete, false, false);
        assertFalse(Files.exists(existingEmpty));
        Applier.apply(tempDir, delete, true, false);
        assertTrue(Files.exists(existingEmpty));
        assertArrayEquals(new byte[0], Files.readAllBytes(existingEmpty));
    }

    @Test
    void mismatchAndDeleteWithRemainingLinesAreRejectedWithoutFuzz() throws Exception {
        Path file = tempDir.resolve("file.txt");
        writeLines(file, "actual", "tail");
        byte[] original = Files.readAllBytes(file);
        List<FilePatch> mismatch = parse(
                "--- a/file.txt",
                "+++ b/file.txt",
                "@@ -1,1 +1,1 @@",
                "-expected",
                "+replacement");

        Applier.ApplyException mismatchError = assertThrows(
                Applier.ApplyException.class,
                () -> Applier.apply(tempDir, mismatch, false, false));
        assertEquals("file.txt: hunk 1 does not apply at line 1", mismatchError.getMessage());
        assertArrayEquals(original, Files.readAllBytes(file));

        List<FilePatch> incompleteDelete = parse(
                "--- a/file.txt",
                "+++ /dev/null",
                "@@ -1,1 +0,0 @@",
                "-actual");
        Applier.ApplyException deleteError = assertThrows(
                Applier.ApplyException.class,
                () -> Applier.apply(tempDir, incompleteDelete, false, false));
        assertEquals("file.txt: file has more lines than the patch deletes", deleteError.getMessage());
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void symbolicLinkAncestorsCannotRedirectCreateModifyOrDeleteOutsideRoot() throws Exception {
        Path root = tempDir.resolve("root");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(root);
        Files.createDirectories(outside);
        Path modifyFile = outside.resolve("modify.txt");
        Path deleteFile = outside.resolve("delete.txt");
        writeLines(modifyFile, "before");
        writeLines(deleteFile, "remove");
        byte[] modifyBefore = Files.readAllBytes(modifyFile);
        byte[] deleteBefore = Files.readAllBytes(deleteFile);
        try {
            Files.createSymbolicLink(root.resolve("create-link"), outside);
            Files.createSymbolicLink(root.resolve("modify-link"), outside);
            Files.createSymbolicLink(root.resolve("delete-link"), outside);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException ex) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links unavailable: " + ex);
        }

        List<FilePatch> create = parse("--- /dev/null", "+++ b/create-link/new.txt");
        Applier.ApplyException createError = assertThrows(
                Applier.ApplyException.class, () -> Applier.apply(root, create, false, false));
        assertEquals("create-link/new.txt: no such file", createError.getMessage());
        assertFalse(Files.exists(outside.resolve("new.txt")));

        List<FilePatch> modify = parse(
                "--- a/modify-link/modify.txt", "+++ b/modify-link/modify.txt",
                "@@ -1,1 +1,1 @@", "-before", "+after");
        Applier.ApplyException modifyError = assertThrows(
                Applier.ApplyException.class, () -> Applier.apply(root, modify, false, false));
        assertEquals("modify-link/modify.txt: no such file", modifyError.getMessage());
        assertArrayEquals(modifyBefore, Files.readAllBytes(modifyFile));

        List<FilePatch> delete = parse(
                "--- a/delete-link/delete.txt", "+++ /dev/null",
                "@@ -1,1 +0,0 @@", "-remove");
        Applier.ApplyException deleteError = assertThrows(
                Applier.ApplyException.class, () -> Applier.apply(root, delete, false, false));
        assertEquals("delete-link/delete.txt: no such file", deleteError.getMessage());
        assertArrayEquals(deleteBefore, Files.readAllBytes(deleteFile));
    }

    @Test
    void checkModeReportsStatusesWithoutChangingFiles() throws Exception {
        Path file = tempDir.resolve("file.txt");
        writeLines(file, "before");
        byte[] original = Files.readAllBytes(file);
        RunResult result = runPatch(
                List.of("--- a/file.txt", "+++ b/file.txt", "@@ -1,1 +1,1 @@", "-before", "+after"),
                false,
                true);

        assertEquals(0, result.exitCode());
        assertEquals("would patch file.txt\n1 file, 1 hunk\n", result.stdout());
        assertEquals("", result.stderr());
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void cliRunAppliesThenRejectsASecondApplicationAndReverseRestores() throws Exception {
        Path file = tempDir.resolve("file.txt");
        writeLines(file, "before");
        List<String> patch = List.of(
                "--- a/file.txt", "+++ b/file.txt", "@@ -1,1 +1,1 @@", "-before", "+after");

        RunResult applied = runPatch(patch, false, false);
        assertEquals(0, applied.exitCode());
        assertEquals("patched file.txt\n1 file, 1 hunk\n", applied.stdout());
        assertEquals("", applied.stderr());
        assertEquals("after\n", Files.readString(file, StandardCharsets.UTF_8));

        RunResult repeated = runPatch(patch, false, false);
        assertEquals(2, repeated.exitCode());
        assertEquals("", repeated.stdout());
        assertEquals("linehunk: file.txt: hunk 1 does not apply at line 1\n", repeated.stderr());

        RunResult reversed = runPatch(patch, true, false);
        assertEquals(0, reversed.exitCode());
        assertEquals("patched file.txt\n1 file, 1 hunk\n", reversed.stdout());
        assertEquals("", reversed.stderr());
        assertEquals("before\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void laterSectionFailureLeavesEveryEarlierFileUnchanged() throws Exception {
        Path first = tempDir.resolve("first.txt");
        Path second = tempDir.resolve("second.txt");
        writeLines(first, "old-first");
        writeLines(second, "different-second");
        byte[] firstBefore = Files.readAllBytes(first);
        byte[] secondBefore = Files.readAllBytes(second);

        RunResult result = runPatch(List.of(
                "--- a/first.txt",
                "+++ b/first.txt",
                "@@ -1,1 +1,1 @@",
                "-old-first",
                "+new-first",
                "--- a/second.txt",
                "+++ b/second.txt",
                "@@ -1,1 +1,1 @@",
                "-expected-second",
                "+new-second"), false, false);

        assertEquals(2, result.exitCode());
        assertEquals("", result.stdout());
        assertEquals("linehunk: second.txt: hunk 1 does not apply at line 1\n", result.stderr());
        assertArrayEquals(firstBefore, Files.readAllBytes(first));
        assertArrayEquals(secondBefore, Files.readAllBytes(second));
    }

    @Test
    void runReportsParserErrorsAndFileStateErrorsWithOneErrorLine() throws Exception {
        RunResult parserError = runPatch(
                List.of("--- a/file.txt", "+++ b/file.txt", "@@ -1 +1 @@"),
                false,
                false);
        assertEquals(2, parserError.exitCode());
        assertEquals("", parserError.stdout());
        assertEquals("linehunk: " + tempDir.resolve("patch.diff") + ":3: malformed hunk header\n",
                parserError.stderr());

        List<FilePatch> create = parse("--- /dev/null", "+++ b/already.txt");
        Files.write(tempDir.resolve("already.txt"), new byte[0]);
        Applier.ApplyException exists = assertThrows(
                Applier.ApplyException.class,
                () -> Applier.apply(tempDir, create, false, false));
        assertEquals("already.txt: already exists", exists.getMessage());

        List<FilePatch> modifyMissing = parse(
                "--- a/missing.txt", "+++ b/missing.txt",
                "@@ -1,1 +1,1 @@", "-old", "+new");
        Applier.ApplyException missing = assertThrows(
                Applier.ApplyException.class,
                () -> Applier.apply(tempDir, modifyMissing, false, false));
        assertEquals("missing.txt: no such file", missing.getMessage());
    }

    private List<FilePatch> parse(String... lines) throws PatchException {
        return PatchParser.parse(List.of(lines));
    }

    private void writeLines(Path path, String... lines) throws Exception {
        String text = lines.length == 0 ? "" : String.join("\n", lines) + "\n";
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private RunResult runPatch(List<String> lines, boolean reverse, boolean check) throws Exception {
        Path patch = tempDir.resolve("patch.diff");
        Files.writeString(patch, String.join("\n", lines), StandardCharsets.UTF_8);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            exitCode = Applier.run(patch.toString(), tempDir.toString(), reverse, check, out, err);
        }
        return new RunResult(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private record ParserFailure(List<String> lines, int line, String message) {
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
    }
}
