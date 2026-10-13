package io.github.gulliby.linehunk.patch;

import java.util.List;
import java.util.Objects;

/** An immutable file section from a parsed linehunk patch. */
public record FilePatch(
        String oldPath,
        String newPath,
        String relativePath,
        Kind kind,
        List<Hunk> hunks,
        int headerLine) {

    public FilePatch {
        Objects.requireNonNull(oldPath, "oldPath");
        Objects.requireNonNull(newPath, "newPath");
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(kind, "kind");
        hunks = List.copyOf(Objects.requireNonNull(hunks, "hunks"));
        if (headerLine < 1) {
            throw new IllegalArgumentException("headerLine must be positive");
        }
    }

    public enum Kind {
        MODIFY,
        CREATE,
        DELETE
    }

    /** An immutable hunk and its ordered body operations. */
    public record Hunk(
            int oldStart,
            int oldCount,
            int newStart,
            int newCount,
            List<Line> lines,
            int headerLine) {

        public Hunk {
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
            if (oldStart < 0 || oldCount < 0 || newStart < 0 || newCount < 0 || headerLine < 1) {
                throw new IllegalArgumentException("invalid hunk coordinates");
            }
        }
    }

    /** A context, deletion, or insertion line; text excludes the leading patch marker. */
    public record Line(char kind, String text, int patchLine) {
        public Line {
            Objects.requireNonNull(text, "text");
            if (kind != ' ' && kind != '-' && kind != '+') {
                throw new IllegalArgumentException("invalid patch line kind");
            }
            if (patchLine < 1) {
                throw new IllegalArgumentException("patchLine must be positive");
            }
        }
    }
}
