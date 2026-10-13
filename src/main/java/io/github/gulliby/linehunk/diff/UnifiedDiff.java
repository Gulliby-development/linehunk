package io.github.gulliby.linehunk.diff;

import java.util.List;
import java.util.Objects;

/** Renders line lists as deterministic unified-diff text using explicit LF endings. */
public final class UnifiedDiff {
    private UnifiedDiff() {
    }

    /**
     * Renders a file diff. Identical inputs produce an empty string; changed inputs
     * include the supplied paths verbatim in the two file headers.
     */
    public static String render(
            String oldPath,
            String newPath,
            List<String> oldLines,
            List<String> newLines,
            int context) {
        Objects.requireNonNull(oldPath, "oldPath");
        Objects.requireNonNull(newPath, "newPath");
        Objects.requireNonNull(oldLines, "oldLines");
        Objects.requireNonNull(newLines, "newLines");
        if (context < 0) {
            throw new IllegalArgumentException("context must not be negative");
        }

        List<Hunk> hunks = Hunk.build(Lcs.diff(oldLines, newLines), context);
        if (hunks.isEmpty()) {
            return "";
        }

        StringBuilder output = new StringBuilder();
        output.append("--- ").append(oldPath).append('\n');
        output.append("+++ ").append(newPath).append('\n');
        for (Hunk hunk : hunks) {
            appendHunk(output, hunk);
        }
        return output.toString();
    }

    /** Renders a newly created path, including the empty-file header-only form. */
    public static String renderAdded(String path, List<String> lines) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(lines, "lines");
        if (lines.isEmpty()) {
            return "--- /dev/null\n+++ " + path + "\n";
        }
        return render("/dev/null", path, List.of(), lines, 0);
    }

    /** Renders a deleted path, including the empty-file header-only form. */
    public static String renderDeleted(String path, List<String> lines) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(lines, "lines");
        if (lines.isEmpty()) {
            return "--- " + path + "\n+++ /dev/null\n";
        }
        return render(path, "/dev/null", lines, List.of(), 0);
    }

    private static void appendHunk(StringBuilder output, Hunk hunk) {
        output.append("@@ -")
                .append(hunk.oldStart()).append(',').append(hunk.oldCount())
                .append(" +")
                .append(hunk.newStart()).append(',').append(hunk.newCount())
                .append(" @@\n");

        for (Lcs.Edit edit : hunk.edits()) {
            switch (edit.kind()) {
                case EQUAL -> output.append(' ').append(edit.line()).append('\n');
                case DELETE -> output.append('-').append(edit.line()).append('\n');
                case INSERT -> output.append('+').append(edit.line()).append('\n');
            }
        }
    }
}
