package io.github.gulliby.linehunk.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds a deterministic shortest edit script from the suffix-LCS table. */
public final class Lcs {
    private Lcs() {
    }

    /** The operation to perform on a line while transforming the old input into the new input. */
    public enum Kind {
        EQUAL,
        DELETE,
        INSERT
    }

    /** One line operation in an edit script. */
    public record Edit(Kind kind, String line) {
        public Edit {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(line, "line");
        }
    }

    /**
     * Returns the deletion-first edit script for two logical line lists.
     *
     * <p>When deleting and inserting would preserve the same LCS length, deletion
     * is selected. The returned list is immutable.</p>
     */
    public static List<Edit> diff(List<String> oldLines, List<String> newLines) {
        Objects.requireNonNull(oldLines, "oldLines");
        Objects.requireNonNull(newLines, "newLines");

        int oldSize = oldLines.size();
        int newSize = newLines.size();
        int[][] suffixLengths = new int[oldSize + 1][newSize + 1];

        for (int oldIndex = oldSize - 1; oldIndex >= 0; oldIndex--) {
            String oldLine = Objects.requireNonNull(oldLines.get(oldIndex), "old line");
            for (int newIndex = newSize - 1; newIndex >= 0; newIndex--) {
                String newLine = Objects.requireNonNull(newLines.get(newIndex), "new line");
                if (oldLine.equals(newLine)) {
                    suffixLengths[oldIndex][newIndex] = suffixLengths[oldIndex + 1][newIndex + 1] + 1;
                } else {
                    suffixLengths[oldIndex][newIndex] = Math.max(
                            suffixLengths[oldIndex + 1][newIndex],
                            suffixLengths[oldIndex][newIndex + 1]);
                }
            }
        }

        List<Edit> edits = new ArrayList<>(oldSize + newSize);
        int oldIndex = 0;
        int newIndex = 0;
        while (oldIndex < oldSize || newIndex < newSize) {
            if (oldIndex < oldSize && newIndex < newSize
                    && oldLines.get(oldIndex).equals(newLines.get(newIndex))) {
                edits.add(new Edit(Kind.EQUAL, oldLines.get(oldIndex)));
                oldIndex++;
                newIndex++;
            } else if (oldIndex < oldSize && (newIndex == newSize
                    || suffixLengths[oldIndex + 1][newIndex] >= suffixLengths[oldIndex][newIndex + 1])) {
                edits.add(new Edit(Kind.DELETE, oldLines.get(oldIndex)));
                oldIndex++;
            } else {
                edits.add(new Edit(Kind.INSERT, newLines.get(newIndex)));
                newIndex++;
            }
        }
        return List.copyOf(edits);
    }
}
