package io.github.gulliby.linehunk.patch;

import io.github.gulliby.linehunk.cli.Cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StdinPatchTest {
    private static final String PATCH = "--- a/a.txt\n+++ b/a.txt\n@@ -1,2 +1,2 @@\n one\n-two\n+TWO\n";

    @TempDir
    Path tempDir;

    @Test
    void patchFromStreamIsApplied() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        InputStream in = new ByteArrayInputStream(PATCH.getBytes(StandardCharsets.UTF_8));

        int status = Applier.run("-", tempDir.toString(), false, false, in,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, status);
        assertEquals("patched a.txt\n1 file, 1 hunk\n", out.toString(StandardCharsets.UTF_8));
        assertEquals("", err.toString(StandardCharsets.UTF_8));
        assertEquals("one\nTWO\n", Files.readString(tempDir.resolve("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void checkAndReverseWorkWithTheStream() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int status = Applier.run("-", tempDir.toString(), false, true,
                new ByteArrayInputStream(PATCH.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, status);
        assertEquals("would patch a.txt\n1 file, 1 hunk\n", out.toString(StandardCharsets.UTF_8));
        assertEquals("one\ntwo\n", Files.readString(tempDir.resolve("a.txt"), StandardCharsets.UTF_8));

        Files.writeString(tempDir.resolve("a.txt"), "one\nTWO\n", StandardCharsets.UTF_8);
        ByteArrayOutputStream reversed = new ByteArrayOutputStream();

        status = Applier.run("-", tempDir.toString(), true, false,
                new ByteArrayInputStream(PATCH.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(reversed, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, status);
        assertEquals("patched a.txt\n1 file, 1 hunk\n", reversed.toString(StandardCharsets.UTF_8));
        assertEquals("one\ntwo\n", Files.readString(tempDir.resolve("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void invalidUtf8OnTheStreamIsNamedStdin() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int status = Applier.run("-", tempDir.toString(), false, false,
                new ByteArrayInputStream(new byte[] {0x2D, (byte) 0xFF, 0x0A}),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, status);
        assertEquals("", out.toString(StandardCharsets.UTF_8));
        assertEquals("linehunk: <stdin>: not valid UTF-8\n", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void parseErrorsNameStdinAndTheLine() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int status = Applier.run("-", tempDir.toString(), false, false,
                new ByteArrayInputStream("--- a/a.txt\nnot a header\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, status);
        assertEquals("", out.toString(StandardCharsets.UTF_8));
        assertEquals("linehunk: <stdin>:2: expected \"+++ \" header\n", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void emptyStreamIsAnEmptyPatch() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int status = Applier.run("-", tempDir.toString(), false, false,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, status);
        assertEquals("linehunk: <stdin>: empty patch\n", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void directoryIsCheckedBeforeTheStreamIsRead() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayInputStream in = new ByteArrayInputStream(PATCH.getBytes(StandardCharsets.UTF_8));

        int status = Applier.run("-", tempDir.resolve("missing").toString(), false, false, in,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, status);
        assertEquals("linehunk: " + tempDir.resolve("missing") + ": not a directory\n",
                err.toString(StandardCharsets.UTF_8));
        assertEquals(PATCH.length(), in.available());
    }

    @Test
    void aFileNamedDashIsStillReachableByAPath() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
        Path patchFile = tempDir.resolve("-");
        Files.writeString(patchFile, PATCH, StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int status = Applier.run(patchFile.toString(), tempDir.toString(), false, true,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, status);
        assertEquals("would patch a.txt\n1 file, 1 hunk\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theCommandLineReadsStandardInputForDash() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        InputStream saved = System.in;
        Cli.setCommandHandler(new Cli.CommandHandler() {
            @Override
            public int diff(String a, String b, int context, int jobs, PrintStream o, PrintStream e) {
                return 2;
            }

            @Override
            public int apply(String patch, String directory, boolean reverse, boolean check,
                             PrintStream o, PrintStream e) {
                return Applier.run(patch, directory, reverse, check, o, e);
            }
        });
        try {
            System.setIn(new ByteArrayInputStream(PATCH.getBytes(StandardCharsets.UTF_8)));

            int status = Cli.run(new String[] {"apply", "-", tempDir.toString()},
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8));

            assertEquals(0, status);
            assertEquals("patched a.txt\n1 file, 1 hunk\n", out.toString(StandardCharsets.UTF_8));
            assertEquals("one\nTWO\n", Files.readString(tempDir.resolve("a.txt"), StandardCharsets.UTF_8));
        } finally {
            System.setIn(saved);
        }
    }
}
