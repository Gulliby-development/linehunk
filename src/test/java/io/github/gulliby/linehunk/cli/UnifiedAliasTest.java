package io.github.gulliby.linehunk.cli;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedAliasTest {
    @TempDir
    Path tempDir;

    private final List<String> calls = new ArrayList<>();
    private Path a;
    private Path b;

    @BeforeEach
    void recordCalls() throws IOException {
        calls.clear();
        a = tempDir.resolve("a.txt");
        b = tempDir.resolve("b.txt");
        Files.writeString(a, "one\n", StandardCharsets.UTF_8);
        Files.writeString(b, "two\n", StandardCharsets.UTF_8);
        Cli.setCommandHandler(new Cli.CommandHandler() {
            @Override
            public int diff(String oldArgument, String newArgument, int context, int jobs,
                            PrintStream out, PrintStream err) {
                calls.add("diff context=" + context + " jobs=" + jobs);
                return 0;
            }

            @Override
            public int apply(String patch, String directory, boolean reverse, boolean check,
                             PrintStream out, PrintStream err) {
                calls.add("apply");
                return 0;
            }
        });
    }

    @Test
    void unifiedWithASeparateValueSetsTheContext() {
        Invocation result = run("diff", "--unified", "5", a.toString(), b.toString());

        assertEquals(0, result.status());
        assertEquals("", result.stderr());
        assertEquals(List.of("diff context=5 jobs=4"), calls);
    }

    @Test
    void unifiedWithAnAttachedValueSetsTheContext() {
        Invocation result = run("diff", "--unified=7", a.toString(), b.toString());

        assertEquals(0, result.status());
        assertEquals("", result.stderr());
        assertEquals(List.of("diff context=7 jobs=4"), calls);
    }

    @Test
    void zeroAndNinetyNineAreGoodValuesOfBothForms() {
        run("diff", "--unified", "0", a.toString(), b.toString());
        run("diff", "--unified=0", a.toString(), b.toString());
        run("diff", "--unified", "99", a.toString(), b.toString());
        run("diff", "--unified=99", a.toString(), b.toString());

        assertEquals(List.of("diff context=0 jobs=4", "diff context=0 jobs=4",
                "diff context=99 jobs=4", "diff context=99 jobs=4"), calls);
    }

    @Test
    void aTwoDigitValueWithALeadingZeroIsReadLikeForDashU() {
        run("diff", "-U", "05", a.toString(), b.toString());
        run("diff", "--unified=05", a.toString(), b.toString());

        assertEquals(List.of("diff context=5 jobs=4", "diff context=5 jobs=4"), calls);
    }

    @Test
    void valuesAbove99AreInvalid() {
        Invocation separate = run("diff", "--unified", "100", a.toString(), b.toString());
        Invocation attached = run("diff", "--unified=100", a.toString(), b.toString());

        assertEquals(2, separate.status());
        assertEquals("linehunk: invalid context \"100\"\n", separate.stderr());
        assertEquals(2, attached.status());
        assertEquals("linehunk: invalid context \"100\"\n", attached.stderr());
        assertEquals(List.of(), calls);
    }

    @Test
    void valuesThatAreNotDigitsAreInvalid() {
        assertEquals("linehunk: invalid context \"abc\"\n", run("diff", "--unified=abc", a.toString(), b.toString()).stderr());
        assertEquals("linehunk: invalid context \"-1\"\n", run("diff", "--unified=-1", a.toString(), b.toString()).stderr());
        assertEquals("linehunk: invalid context \"\"\n", run("diff", "--unified=", a.toString(), b.toString()).stderr());
        assertEquals("linehunk: invalid context \"1x\"\n", run("diff", "--unified", "1x", a.toString(), b.toString()).stderr());
        assertEquals(List.of(), calls);
    }

    @Test
    void aMissingValueNamesTheLongOption() {
        Invocation result = run("diff", a.toString(), b.toString(), "--unified");

        assertEquals(2, result.status());
        assertEquals("linehunk: missing value for --unified\n", result.stderr());
        assertEquals(List.of(), calls);
    }

    @Test
    void theSeparateFormTakesTheNextArgumentWhateverItIs() {
        Invocation result = run("diff", "--unified", "--jobs", a.toString(), b.toString());

        assertEquals(2, result.status());
        assertEquals("linehunk: invalid context \"--jobs\"\n", result.stderr());
    }

    @Test
    void theLastOccurrenceOfTheContextWinsAcrossTheThreeSpellings() {
        run("diff", "-U", "7", "--unified", "2", a.toString(), b.toString());
        run("diff", "--unified=2", "-U", "7", a.toString(), b.toString());
        run("diff", "--unified", "1", "--unified=4", a.toString(), b.toString());

        assertEquals(List.of("diff context=2 jobs=4", "diff context=7 jobs=4", "diff context=4 jobs=4"), calls);
    }

    @Test
    void anAttachedValueDoesNotTakeAnOperandAndLeavesTheJobsAlone() {
        Invocation result = run("diff", "--jobs", "2", "--unified=1", a.toString(), b.toString());

        assertEquals(0, result.status());
        assertEquals(List.of("diff context=1 jobs=2"), calls);
    }

    @Test
    void anOptionAmongOperandsIsStillFound() {
        Invocation result = run("diff", a.toString(), "--unified=2", b.toString());

        assertEquals(0, result.status());
        assertEquals(List.of("diff context=2 jobs=4"), calls);
    }

    @Test
    void applyDoesNotKnowTheOption() {
        Invocation separate = run("apply", "--unified", "3", a.toString(), tempDir.toString());
        Invocation attached = run("apply", "--unified=3", a.toString(), tempDir.toString());

        assertEquals(2, separate.status());
        assertEquals("linehunk: unknown option \"--unified\"\n", separate.stderr());
        assertEquals(2, attached.status());
        assertEquals("linehunk: unknown option \"--unified=3\"\n", attached.stderr());
        assertEquals(List.of(), calls);
    }

    @Test
    void theReadmeNamesTheLongForms() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);

        assertTrue(readme.contains("`--unified N` and `--unified=N` are the long forms of `-U N`"));
    }

    private static Invocation run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = Cli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Invocation(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Invocation(int status, String stdout, String stderr) {
    }
}
