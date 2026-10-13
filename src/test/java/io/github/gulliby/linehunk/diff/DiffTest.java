package io.github.gulliby.linehunk.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiffTest {
    private static final long WAIT_SECONDS = 30;

    @TempDir
    Path tempDir;

    @Test
    void linesHandleBomCrLfLoneCrAndFinalNewlineBoundaries() throws Exception {
        String bom = String.valueOf((char) 0xFEFF);
        assertEquals(List.of(), Lines.decode(new byte[0], 10));
        assertEquals(List.of(), Lines.decode(bom.getBytes(StandardCharsets.UTF_8), 10));
        assertEquals(
                List.of("alpha", "beta"),
                decode(bom + "alpha\r\nbeta\n"));
        assertEquals(
                List.of(bom + "x"),
                decode(bom + bom + "x"));

        assertEquals(
                List.of("one", "two\ronly", "\r"),
                decode("one\r\ntwo\ronly\r\n\r"));
        assertEquals(List.of("\r"), decode("\r"));
        assertEquals(List.of("one"), decode("one"));
        assertEquals(List.of("one"), decode("one\n"));
        assertEquals(List.of("one", ""), decode("one\n\n"));
        assertEquals(List.of(""), decode("\n"));
    }

    @Test
    void linesRejectMalformedUtf8AndEnforceTheOrdinaryFileLimitExactly() throws Exception {
        Lines.ReadException invalid = assertThrows(
                Lines.ReadException.class,
                () -> Lines.decode(new byte[] {(byte) 0xC3, 0x28}, Lines.DEFAULT_LIMIT));
        assertEquals(Lines.ReadFailure.INVALID_UTF8, invalid.failure());

        byte[] atLimit = repeatedLines(2_000).getBytes(StandardCharsets.UTF_8);
        assertEquals(2_000, Lines.decode(atLimit, Lines.DEFAULT_LIMIT).size());

        Lines.ReadException overLimit = assertThrows(
                Lines.ReadException.class,
                () -> Lines.decode(repeatedLines(2_001).getBytes(StandardCharsets.UTF_8),
                        Lines.DEFAULT_LIMIT));
        assertEquals(Lines.ReadFailure.TOO_MANY_LINES, overLimit.failure());
    }

    @Test
    void suffixLcsUsesDeletionFirstTieBreakAndKeepsEqualLines() {
        assertEquals(
                List.of(
                        edit(Lcs.Kind.DELETE, "a"),
                        edit(Lcs.Kind.EQUAL, "b"),
                        edit(Lcs.Kind.INSERT, "a")),
                Lcs.diff(List.of("a", "b"), List.of("b", "a")));
        assertEquals(
                List.of(edit(Lcs.Kind.DELETE, "old"), edit(Lcs.Kind.INSERT, "new")),
                Lcs.diff(List.of("old"), List.of("new")));
        assertEquals(
                List.of(edit(Lcs.Kind.EQUAL, "same")),
                Lcs.diff(List.of("same"), List.of("same")));
        assertEquals(List.of(), Hunk.build(Lcs.diff(List.of(), List.of()), 3));
    }

    @Test
    void unifiedRendererMatchesFileWorkedExamplesAndRangeBoundaries() {
        List<String> oldLines = List.of(
                "one", "two", "three", "four", "five", "six",
                "seven", "eight", "nine", "ten", "eleven", "twelve");
        List<String> newLines = List.of(
                "one", "two", "three", "FOUR", "five", "six",
                "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen");

        String defaultContext = """
                --- a.txt
                +++ b.txt
                @@ -1,7 +1,7 @@
                 one
                 two
                 three
                -four
                +FOUR
                 five
                 six
                 seven
                @@ -10,3 +10,4 @@
                 ten
                 eleven
                 twelve
                +thirteen
                """;
        assertEquals(defaultContext, UnifiedDiff.render("a.txt", "b.txt", oldLines, newLines, 3));

        String zeroContext = """
                --- a.txt
                +++ b.txt
                @@ -4,1 +4,1 @@
                -four
                +FOUR
                @@ -12,0 +13,1 @@
                +thirteen
                """;
        assertEquals(zeroContext, UnifiedDiff.render("a.txt", "b.txt", oldLines, newLines, 0));

        assertEquals(
                """
                --- /dev/null
                +++ b.txt
                @@ -0,0 +1,2 @@
                +x
                +y
                """,
                UnifiedDiff.render("/dev/null", "b.txt", List.of(), List.of("x", "y"), 3));
        assertEquals(
                """
                --- a.txt
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -x
                -y
                """,
                UnifiedDiff.render("a.txt", "/dev/null", List.of("x", "y"), List.of(), 3));
        assertEquals(
                """
                --- a.txt
                +++ b.txt
                @@ -1,2 +1,3 @@
                 p
                +r
                 q
                """,
                UnifiedDiff.render("a.txt", "b.txt", List.of("p", "q"), List.of("p", "r", "q"), 1));
        assertEquals("", UnifiedDiff.render("a", "b", List.of("same"), List.of("same"), 3));
    }

    @Test
    void hunkBuilderMergesAtTwiceTheContextAndSeparatesBeyondIt() {
        List<String> oldAtBoundary = List.of("a", "1", "2", "3", "4", "5", "6", "z");
        List<String> newAtBoundary = List.of("A", "1", "2", "3", "4", "5", "6", "Z");
        List<Hunk> merged = Hunk.build(Lcs.diff(oldAtBoundary, newAtBoundary), 3);
        assertEquals(1, merged.size());
        assertEquals(8, merged.get(0).oldCount());
        assertEquals(8, merged.get(0).newCount());

        List<String> oldBeyondBoundary = List.of("a", "1", "2", "3", "4", "5", "6", "7", "z");
        List<String> newBeyondBoundary = List.of("A", "1", "2", "3", "4", "5", "6", "7", "Z");
        List<Hunk> separate = Hunk.build(Lcs.diff(oldBeyondBoundary, newBeyondBoundary), 3);
        assertEquals(2, separate.size());
        assertEquals(1, separate.get(0).oldStart());
        assertEquals(6, separate.get(1).oldStart());
    }

    @Test
    void twentyLineExampleProducesThreeExactContextHunks() {
        List<String> oldLines = numberedLines(20);
        List<String> newLines = new ArrayList<>(oldLines);
        newLines.set(1, "two");
        newLines.add(10, "new");
        newLines.remove(18);

        String expected = """
                --- old
                +++ new
                @@ -1,5 +1,5 @@
                 1
                -2
                +two
                 3
                 4
                 5
                @@ -8,6 +8,7 @@
                 8
                 9
                 10
                +new
                 11
                 12
                 13
                @@ -15,6 +16,5 @@
                 15
                 16
                 17
                -18
                 19
                 20
                """;
        assertEquals(expected, UnifiedDiff.render("old", "new", oldLines, newLines, 3));
    }

    @Test
    void orderedParallelMapReturnsInputOrderAfterReverseCompletion() throws Exception {
        List<CountDownLatch> release = List.of(new CountDownLatch(1), new CountDownLatch(1),
                new CountDownLatch(1));
        List<CountDownLatch> completed = List.of(new CountDownLatch(1), new CountDownLatch(1),
                new CountDownLatch(1));
        CountDownLatch started = new CountDownLatch(3);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<List<Integer>> result = caller.submit(() -> OrderedParallelMap.map(
                    List.of(0, 1, 2),
                    3,
                    item -> {
                        started.countDown();
                        await(release.get(item));
                        completed.get(item).countDown();
                        return item * 10;
                    }));

            await(started);
            release.get(2).countDown();
            await(completed.get(2));
            release.get(1).countDown();
            await(completed.get(1));
            release.get(0).countDown();

            assertEquals(List.of(0, 10, 20), result.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            release.forEach(CountDownLatch::countDown);
            caller.shutdownNow();
        }
    }

    @Test
    void orderedParallelMapSupportsSingleWorkerAndMoreWorkersThanItems() {
        assertEquals(
                List.of("1", "2", "3"),
                OrderedParallelMap.map(List.of(1, 2, 3), 1, Object::toString));
        assertEquals(
                List.of("A", "B"),
                OrderedParallelMap.map(List.of("a", "b"), 5, String::toUpperCase));
        assertEquals(List.of(), OrderedParallelMap.map(List.of(), 4, item -> item));
    }

    @Test
    void orderedParallelMapReportsFirstFailureInItemOrderAfterAllTasksFinish() throws Exception {
        CountDownLatch started = new CountDownLatch(3);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseLater = new CountDownLatch(1);
        CountDownLatch releaseSuccessful = new CountDownLatch(1);
        CountDownLatch laterFailed = new CountDownLatch(1);
        CountDownLatch successfulFinished = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<List<Integer>> result = caller.submit(() -> OrderedParallelMap.map(
                    List.of(0, 1, 2),
                    3,
                    item -> {
                        started.countDown();
                        if (item == 0) {
                            await(releaseFirst);
                            throw new IOException("earlier item failure");
                        }
                        if (item == 1) {
                            try {
                                await(releaseLater);
                                throw new IOException("later item failure");
                            } finally {
                                laterFailed.countDown();
                            }
                        }
                        try {
                            await(releaseSuccessful);
                            return item;
                        } finally {
                            successfulFinished.countDown();
                        }
                    }));

            await(started);
            releaseLater.countDown();
            await(laterFailed);
            releaseSuccessful.countDown();
            await(successfulFinished);
            assertFalse(result.isDone(), "the mapper must wait for the earlier item too");

            releaseFirst.countDown();
            ExecutionException thrown = assertThrows(
                    ExecutionException.class, () -> result.get(WAIT_SECONDS, TimeUnit.SECONDS));
            OrderedParallelMap.ParallelMapException failure = assertInstanceOf(
                    OrderedParallelMap.ParallelMapException.class, thrown.getCause());
            assertEquals(0, failure.itemIndex());
            assertEquals("earlier item failure", failure.getCause().getMessage());
        } finally {
            releaseFirst.countDown();
            releaseLater.countDown();
            releaseSuccessful.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void seededOracleChecksLcsLengthAndForwardReverseHunkApplication() {
        Random random = new Random(42);
        String[] alphabet = {"a", "b", "c", "d"};
        for (int pair = 0; pair < 300; pair++) {
            List<String> oldLines = randomLines(random, alphabet);
            List<String> newLines = randomLines(random, alphabet);
            List<Lcs.Edit> script = Lcs.diff(oldLines, newLines);
            long equalCount = script.stream().filter(edit -> edit.kind() == Lcs.Kind.EQUAL).count();
            assertEquals(naiveLcsLength(oldLines, newLines), equalCount,
                    "LCS length for seeded pair " + pair);

            for (int context : List.of(0, 1, 3)) {
                List<Hunk> hunks = Hunk.build(script, context);
                assertEquals(newLines, applyHunks(oldLines, hunks, false),
                        "forward pair " + pair + " context " + context);
                assertEquals(oldLines, applyHunks(newLines, hunks, true),
                        "reverse pair " + pair + " context " + context);
            }
        }
    }

    @Test
    void treeDiffIgnoresFilesReachedOnlyThroughSymbolicLinkDirectories() throws Exception {
        Path oldRoot = tempDir.resolve("linked-old");
        Path newRoot = tempDir.resolve("linked-new");
        Path linkedTarget = tempDir.resolve("linked-target");
        Files.createDirectories(oldRoot);
        Files.createDirectories(newRoot.resolve("nested"));
        Files.createDirectories(linkedTarget);
        Files.writeString(linkedTarget.resolve("f.txt"), "external\n", StandardCharsets.UTF_8);
        Files.writeString(newRoot.resolve("nested").resolve("f.txt"), "new\n", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(oldRoot.resolve("nested"), linkedTarget);
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            assumeTrue(false, "symbolic links are unavailable: " + ex);
        }

        assertEquals(
                "--- /dev/null\n+++ b/nested/f.txt\n@@ -0,0 +1,1 @@\n+new\n",
                TreeDiff.diff(oldRoot, newRoot, 3, 2));
    }

    @Test
    void treeDiffMatchesDirectoryWorkedExampleForEverySupportedJobCount() throws Exception {
        Path oldRoot = tempDir.resolve("old-tree");
        Path newRoot = tempDir.resolve("new-tree");
        Files.createDirectories(oldRoot);
        Files.createDirectories(newRoot);

        writeLines(oldRoot, Path.of("edit.txt"), List.of("1", "2", "3", "4", "5"), "\n");
        writeLines(newRoot, Path.of("edit.txt"), List.of("1", "2", "three", "4", "5"), "\n");
        writeLines(oldRoot, Path.of("same.txt"), List.of("x", "y"), "\n");
        writeLines(newRoot, Path.of("same.txt"), List.of("x", "y"), "\r\n");
        writeLines(oldRoot, Path.of("gone.txt"), List.of("bye", "now"), "\n");
        writeLines(oldRoot, Path.of("empty-gone.txt"), List.of(), "\n");
        writeLines(newRoot, Path.of("new", "sub.txt"), List.of("hello", "world"), "\n");
        writeLines(newRoot, Path.of("new", "empty.txt"), List.of(), "\n");

        String expected = """
                --- a/edit.txt
                +++ b/edit.txt
                @@ -2,3 +2,3 @@
                 2
                -3
                +three
                 4
                --- a/empty-gone.txt
                +++ /dev/null
                --- a/gone.txt
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -bye
                -now
                --- /dev/null
                +++ b/new/empty.txt
                --- /dev/null
                +++ b/new/sub.txt
                @@ -0,0 +1,2 @@
                +hello
                +world
                """;
        for (int jobs = 1; jobs <= 8; jobs++) {
            assertEquals(expected, TreeDiff.diff(oldRoot, newRoot, 1, jobs), "jobs=" + jobs);
        }
    }

    @Test
    void treeDiffReportsEarliestSortedPathAndChecksSharedFileOldSideFirst() throws Exception {
        Path oldRoot = tempDir.resolve("error-old");
        Path newRoot = tempDir.resolve("error-new");
        Files.createDirectories(oldRoot);
        Files.createDirectories(newRoot);
        byte[] malformed = {(byte) 0xC3, 0x28};
        writeBytes(oldRoot, Path.of("a.txt"), malformed);
        writeBytes(newRoot, Path.of("a.txt"), malformed);
        writeBytes(oldRoot, Path.of("z.txt"), malformed);

        TreeDiff.TreeDiffException failure = assertThrows(
                TreeDiff.TreeDiffException.class,
                () -> TreeDiff.diff(oldRoot, newRoot, 0, 4));
        assertEquals("a/a.txt: not valid UTF-8", failure.getMessage());
    }

    private static List<String> decode(String text) throws IOException {
        return Lines.decode(text.getBytes(StandardCharsets.UTF_8), 100);
    }

    private static Lcs.Edit edit(Lcs.Kind kind, String line) {
        return new Lcs.Edit(kind, line);
    }

    private static String repeatedLines(int count) {
        return String.join("\n", Collections.nCopies(count, "line"));
    }

    private static List<String> numberedLines(int count) {
        List<String> lines = new ArrayList<>(count);
        for (int line = 1; line <= count; line++) {
            lines.add(Integer.toString(line));
        }
        return lines;
    }

    private static List<String> randomLines(Random random, String[] alphabet) {
        int size = random.nextInt(11);
        List<String> lines = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            lines.add(alphabet[random.nextInt(alphabet.length)]);
        }
        return lines;
    }

    private static int naiveLcsLength(List<String> oldLines, List<String> newLines) {
        int[][] lengths = new int[oldLines.size() + 1][newLines.size() + 1];
        for (int oldIndex = oldLines.size() - 1; oldIndex >= 0; oldIndex--) {
            for (int newIndex = newLines.size() - 1; newIndex >= 0; newIndex--) {
                if (oldLines.get(oldIndex).equals(newLines.get(newIndex))) {
                    lengths[oldIndex][newIndex] = lengths[oldIndex + 1][newIndex + 1] + 1;
                } else {
                    lengths[oldIndex][newIndex] = Math.max(
                            lengths[oldIndex + 1][newIndex], lengths[oldIndex][newIndex + 1]);
                }
            }
        }
        return lengths[0][0];
    }

    private static List<String> applyHunks(List<String> source, List<Hunk> hunks, boolean reverse) {
        List<String> result = new ArrayList<>();
        int cursor = 0;
        for (Hunk hunk : hunks) {
            int oldStart = reverse ? hunk.newStart() : hunk.oldStart();
            int oldCount = reverse ? hunk.newCount() : hunk.oldCount();
            int newCount = reverse ? hunk.oldCount() : hunk.newCount();
            int startIndex = oldCount == 0 ? oldStart : oldStart - 1;
            assertTrue(startIndex >= cursor && startIndex <= source.size());
            result.addAll(source.subList(cursor, startIndex));

            int sourceIndex = startIndex;
            int consumedOld = 0;
            int producedNew = 0;
            for (Lcs.Edit edit : hunk.edits()) {
                Lcs.Kind kind = edit.kind();
                if (reverse) {
                    kind = switch (kind) {
                        case DELETE -> Lcs.Kind.INSERT;
                        case INSERT -> Lcs.Kind.DELETE;
                        case EQUAL -> Lcs.Kind.EQUAL;
                    };
                }

                if (kind == Lcs.Kind.EQUAL || kind == Lcs.Kind.DELETE) {
                    assertTrue(sourceIndex < source.size(), "hunk consumes past source end");
                    assertEquals(edit.line(), source.get(sourceIndex));
                    sourceIndex++;
                    consumedOld++;
                }
                if (kind == Lcs.Kind.EQUAL || kind == Lcs.Kind.INSERT) {
                    result.add(edit.line());
                    producedNew++;
                }
            }
            assertEquals(oldCount, consumedOld);
            assertEquals(newCount, producedNew);
            cursor = sourceIndex;
        }
        result.addAll(source.subList(cursor, source.size()));
        return List.copyOf(result);
    }

    private static void writeLines(Path root, Path relative, List<String> lines, String separator)
            throws IOException {
        String content = lines.isEmpty() ? "" : String.join(separator, lines) + separator;
        writeText(root, relative, content);
    }

    private static void writeText(Path root, Path relative, String content) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static void writeBytes(Path root, Path relative, byte[] content) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "timed out waiting for task");
    }
}
