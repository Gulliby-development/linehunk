package io.github.gulliby.linehunk.diff;

import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Produces deterministic unified diffs for two directory trees. */
public final class TreeDiff {
    private TreeDiff() {
    }

    /**
     * Diffs regular files below two directory roots. Relative names are sorted by
     * {@link String#compareTo(String)}, symbolic links are ignored, and work may be
     * performed concurrently without changing section order.
     *
     * @param oldRoot source tree
     * @param newRoot target tree
     * @param context number of context lines around changes
     * @param jobs number of parallel workers
     * @return concatenated unified-diff sections, or an empty string when unchanged
     * @throws TreeDiffException when a tree cannot be enumerated or an input file
     *         cannot be read using the line rules
     */
    public static String diff(Path oldRoot, Path newRoot, int context, int jobs)
            throws TreeDiffException {
        Objects.requireNonNull(oldRoot, "oldRoot");
        Objects.requireNonNull(newRoot, "newRoot");
        if (context < 0) {
            throw new IllegalArgumentException("context must not be negative");
        }
        if (jobs < 1) {
            throw new IllegalArgumentException("jobs must be positive");
        }

        TreeSet<String> oldNames = new TreeSet<>(regularFileNames(oldRoot));
        TreeSet<String> newNames = new TreeSet<>(regularFileNames(newRoot));
        TreeSet<String> names = new TreeSet<>(oldNames);
        names.addAll(newNames);
        List<String> orderedNames = List.copyOf(names);

        final List<String> sections;
        try {
            sections = OrderedParallelMap.map(orderedNames, jobs,
                    name -> diffOne(oldRoot, newRoot, name,
                            oldNames.contains(name), newNames.contains(name), context));
        } catch (OrderedParallelMap.ParallelMapException ex) {
            if (ex.getCause() instanceof TreeReadFailure failure) {
                throw new TreeDiffException(failure.getMessage(), failure.getCause());
            }
            throw ex;
        }

        StringBuilder output = new StringBuilder();
        for (String section : sections) {
            output.append(section);
        }
        return output.toString();
    }

    private static List<String> regularFileNames(Path root) throws TreeDiffException {
        TreeSet<String> names = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> !path.equals(root))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> relativeName(root.relativize(path)))
                    .forEach(names::add);
        } catch (IOException | SecurityException ex) {
            throw new TreeDiffException(root + ": cannot read", ex);
        }
        return new ArrayList<>(names);
    }

    private static String relativeName(Path relative) {
        StringBuilder name = new StringBuilder();
        for (Path segment : relative) {
            if (!name.isEmpty()) {
                name.append('/');
            }
            name.append(segment);
        }
        return name.toString();
    }

    private static String diffOne(
            Path oldRoot, Path newRoot, String name, boolean inOld, boolean inNew, int context)
            throws TreeReadFailure {
        Path oldFile = oldRoot.resolve(name);
        Path newFile = newRoot.resolve(name);

        if (inOld && inNew) {
            List<String> oldLines = readLines(oldFile, "a/" + name);
            List<String> newLines = readLines(newFile, "b/" + name);
            return UnifiedDiff.render("a/" + name, "b/" + name, oldLines, newLines, context);
        }
        if (inOld) {
            return UnifiedDiff.renderDeleted("a/" + name, readLines(oldFile, "a/" + name));
        }
        if (inNew) {
            return UnifiedDiff.renderAdded("b/" + name, readLines(newFile, "b/" + name));
        }
        return "";
    }

    private static List<String> readLines(Path path, String displayPath) throws TreeReadFailure {
        try {
            return Lines.read(path);
        } catch (Lines.ReadException ex) {
            String reason = switch (ex.failure()) {
                case INVALID_UTF8 -> "not valid UTF-8";
                case TOO_MANY_LINES -> "more than 2000 lines";
            };
            throw new TreeReadFailure(displayPath + ": " + reason, ex);
        } catch (IOException | SecurityException ex) {
            throw new TreeReadFailure(displayPath + ": cannot read", ex);
        }
    }

    /** A tree traversal or file read error with the diagnostic expected by the CLI. */
    public static final class TreeDiffException extends IOException {
        public TreeDiffException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class TreeReadFailure extends Exception {
        private TreeReadFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
