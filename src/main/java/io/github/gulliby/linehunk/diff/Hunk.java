package io.github.gulliby.linehunk.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A unified-diff hunk and the edit operations it contains. */
public record Hunk(
        int oldStart,
        int oldCount,
        int newStart,
        int newCount,
        List<Lcs.Edit> edits) {

    public Hunk {
        if (oldStart < 0 || oldCount < 0 || newStart < 0 || newCount < 0) {
            throw new IllegalArgumentException("hunk ranges must not be negative");
        }
        if ((oldCount > 0 && oldStart == 0) || (newCount > 0 && newStart == 0)) {
            throw new IllegalArgumentException("a nonempty hunk range must start at line 1 or later");
        }
        edits = List.copyOf(Objects.requireNonNull(edits, "edits"));
    }

    /**
     * Groups change runs into hunks, adding up to {@code context} equal operations
     * around each group and merging runs separated by at most twice that context.
     */
    public static List<Hunk> build(List<Lcs.Edit> script, int context) {
        Objects.requireNonNull(script, "script");
        if (context < 0) {
            throw new IllegalArgumentException("context must not be negative");
        }

        int size = script.size();
        List<Run> runs = new ArrayList<>();
        int index = 0;
        while (index < size) {
            if (kindAt(script, index) == Lcs.Kind.EQUAL) {
                index++;
                continue;
            }
            int start = index;
            while (index < size && kindAt(script, index) != Lcs.Kind.EQUAL) {
                index++;
            }
            runs.add(new Run(start, index));
        }
        if (runs.isEmpty()) {
            return List.of();
        }

        int[] oldPrefix = new int[size + 1];
        int[] newPrefix = new int[size + 1];
        for (int i = 0; i < size; i++) {
            Lcs.Kind kind = kindAt(script, i);
            oldPrefix[i + 1] = oldPrefix[i] + (kind == Lcs.Kind.INSERT ? 0 : 1);
            newPrefix[i + 1] = newPrefix[i] + (kind == Lcs.Kind.DELETE ? 0 : 1);
        }

        List<Hunk> hunks = new ArrayList<>();
        int groupStart = runs.get(0).start;
        int groupEnd = runs.get(0).end;
        for (int runIndex = 1; runIndex < runs.size(); runIndex++) {
            Run next = runs.get(runIndex);
            int equalGap = next.start - groupEnd;
            if (equalGap <= 2 * context) {
                groupEnd = next.end;
            } else {
                hunks.add(create(script, oldPrefix, newPrefix, groupStart, groupEnd, context));
                groupStart = next.start;
                groupEnd = next.end;
            }
        }
        hunks.add(create(script, oldPrefix, newPrefix, groupStart, groupEnd, context));
        return List.copyOf(hunks);
    }

    private static Hunk create(
            List<Lcs.Edit> script,
            int[] oldPrefix,
            int[] newPrefix,
            int changeStart,
            int changeEnd,
            int context) {
        int start = changeStart;
        int before = 0;
        while (start > 0 && before < context && kindAt(script, start - 1) == Lcs.Kind.EQUAL) {
            start--;
            before++;
        }

        int end = changeEnd;
        int after = 0;
        while (end < script.size() && after < context && kindAt(script, end) == Lcs.Kind.EQUAL) {
            end++;
            after++;
        }

        int oldCount = oldPrefix[end] - oldPrefix[start];
        int newCount = newPrefix[end] - newPrefix[start];
        int oldStart = oldCount == 0 ? oldPrefix[start] : oldPrefix[start] + 1;
        int newStart = newCount == 0 ? newPrefix[start] : newPrefix[start] + 1;
        return new Hunk(
                oldStart,
                oldCount,
                newStart,
                newCount,
                script.subList(start, end));
    }

    private static Lcs.Kind kindAt(List<Lcs.Edit> script, int index) {
        return Objects.requireNonNull(script.get(index), "edit").kind();
    }

    private record Run(int start, int end) {
    }
}
