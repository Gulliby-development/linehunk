package io.github.gulliby.linehunk.patch;

import io.github.gulliby.linehunk.diff.Lines;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Validates and applies parsed linehunk patches. */
public final class Applier {
    private Applier() {
    }

    /**
     * Validates every section before changing the filesystem, then applies it unless check mode is
     * enabled. The returned lines contain the successful per-file statuses and summary.
     */
    public static Result apply(Path directory, List<FilePatch> sections, boolean reverse, boolean check)
            throws ApplyException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(sections, "sections");

        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new ApplyException("DIR: not a directory");
        }
        if (sections.isEmpty()) {
            throw new ApplyException("empty patch");
        }

        List<PendingChange> pending = new ArrayList<>(sections.size());
        int hunkCount = 0;
        for (FilePatch section : sections) {
            Objects.requireNonNull(section, "section");
            String relative = section.relativePath();
            Path target = resolveTarget(root, relative);
            FilePatch.Kind kind = effectiveKind(section.kind(), reverse);
            List<String> original;
            if (kind == FilePatch.Kind.CREATE) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ApplyException(relative + ": already exists");
                }
                original = List.of();
            } else {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ApplyException(relative + ": no such file");
                }
                original = readTarget(target, relative);
            }

            List<String> result = applyHunks(relative, original, section.hunks(), reverse);
            if (kind == FilePatch.Kind.DELETE && !result.isEmpty()) {
                throw new ApplyException(relative + ": file has more lines than the patch deletes");
            }
            pending.add(new PendingChange(relative, target, kind, result));
            hunkCount += section.hunks().size();
        }

        if (!check) {
            for (PendingChange change : pending) {
                writeChange(change);
            }
        }

        List<String> statuses = new ArrayList<>(pending.size());
        for (PendingChange change : pending) {
            String status = switch (change.kind()) {
                case CREATE -> check ? "would create " : "created ";
                case DELETE -> check ? "would delete " : "deleted ";
                case MODIFY -> check ? "would patch " : "patched ";
            };
            statuses.add(status + change.relativePath());
        }

        return new Result(statuses, sections.size(), hunkCount);
    }

    /**
     * CLI-facing helper that reads and parses the patch file, applies it, and writes explicit-LF
     * status/error output. Directory validation intentionally precedes patch-file reading.
     */
    public static int run(
            String patchArgument,
            String directoryArgument,
            boolean reverse,
            boolean check,
            PrintStream out,
            PrintStream err) {
        return run(patchArgument, directoryArgument, reverse, check, System.in, out, err);
    }

    /**
     * Like {@link #run(String, String, boolean, boolean, PrintStream, PrintStream)}, reading the
     * patch from {@code in} when the patch argument is {@code -}; messages then name it
     * {@code <stdin>}.
     */
    public static int run(
            String patchArgument,
            String directoryArgument,
            boolean reverse,
            boolean check,
            InputStream in,
            PrintStream out,
            PrintStream err) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        final boolean fromStdin = "-".equals(patchArgument);
        final String patchLabel = fromStdin ? "<stdin>" : patchArgument;

        final Path directory;
        try {
            directory = Path.of(directoryArgument);
        } catch (InvalidPathException ex) {
            return reportError(err, directoryArgument + ": not a directory");
        }
        if (!Files.isDirectory(directory)) {
            return reportError(err, directoryArgument + ": not a directory");
        }

        final List<String> patchLines;
        try {
            if (fromStdin) {
                patchLines = Lines.read(in, Lines.PATCH_LIMIT);
            } else {
                patchLines = Lines.read(Path.of(patchArgument), Lines.PATCH_LIMIT);
            }
        } catch (InvalidPathException ex) {
            return reportError(err, patchLabel + ": cannot read");
        } catch (Lines.ReadException ex) {
            return reportError(err, patchLabel + ": " + readFailure(ex));
        } catch (IOException | SecurityException ex) {
            return reportError(err, patchLabel + ": cannot read");
        }

        final List<FilePatch> sections;
        try {
            sections = PatchParser.parse(patchLines);
        } catch (PatchException ex) {
            String message = patchLabel + ":";
            if (ex.hasLineNumber()) {
                message += ex.lineNumber() + ": ";
            } else {
                message += " ";
            }
            return reportError(err, message + ex.getMessage());
        }

        final Result result;
        try {
            result = apply(directory, sections, reverse, check);
        } catch (ApplyException ex) {
            return reportError(err, ex.getMessage());
        }
        for (String line : result.statusLines()) {
            writeLine(out, line);
        }
        writeLine(out, result.summaryLine());
        return 0;
    }

    private static Path resolveTarget(Path root, String relative) throws ApplyException {
        if (!isSafeRelativePath(relative)) {
            throw new ApplyException(relative + ": no such file");
        }
        try {
            Path target = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
            if (!target.startsWith(root)) {
                throw new ApplyException(relative + ": no such file");
            }

            // Lexical normalization does not contain a path that traverses a symlink.
            // Check every existing directory below the chosen root without following links.
            Path ancestor = root;
            String[] segments = relative.split("/", -1);
            for (int i = 0; i < segments.length - 1; i++) {
                ancestor = ancestor.resolve(segments[i]);
                if (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                    break;
                }
                if (Files.isSymbolicLink(ancestor)
                        || !Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ApplyException(relative + ": no such file");
                }
            }
            return target;
        } catch (InvalidPathException ex) {
            throw new ApplyException(relative + ": no such file");
        }
    }

    private static boolean isSafeRelativePath(String relative) {
        if (relative.isEmpty() || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0) {
            return false;
        }
        String[] segments = relative.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static FilePatch.Kind effectiveKind(FilePatch.Kind kind, boolean reverse) {
        if (!reverse) {
            return kind;
        }
        return switch (kind) {
            case CREATE -> FilePatch.Kind.DELETE;
            case DELETE -> FilePatch.Kind.CREATE;
            case MODIFY -> FilePatch.Kind.MODIFY;
        };
    }

    private static List<String> readTarget(Path target, String relative) throws ApplyException {
        try {
            return Lines.read(target);
        } catch (Lines.ReadException ex) {
            throw new ApplyException(relative + ": " + readFailure(ex));
        } catch (IOException | SecurityException ex) {
            throw new ApplyException(relative + ": cannot read");
        }
    }

    private static String readFailure(Lines.ReadException exception) {
        return switch (exception.failure()) {
            case INVALID_UTF8 -> "not valid UTF-8";
            case TOO_MANY_LINES -> "more than 2000 lines";
        };
    }

    private static List<String> applyHunks(
            String relative, List<String> original, List<FilePatch.Hunk> hunks, boolean reverse)
            throws ApplyException {
        List<String> result = new ArrayList<>();
        int cursor = 0;
        for (int hunkIndex = 0; hunkIndex < hunks.size(); hunkIndex++) {
            FilePatch.Hunk hunk = hunks.get(hunkIndex);
            int oldStart = reverse ? hunk.newStart() : hunk.oldStart();
            int oldCount = reverse ? hunk.newCount() : hunk.oldCount();
            int startIndex = oldCount > 0 ? oldStart - 1 : oldStart;
            int failureLine = oldCount > 0 ? oldStart : oldStart + 1;

            if (startIndex < cursor || startIndex > original.size()
                    || oldCount > original.size() - startIndex) {
                throw hunkFailure(relative, hunkIndex, failureLine);
            }

            result.addAll(original.subList(cursor, startIndex));
            int sourceIndex = startIndex;
            int consumedOld = 0;
            int consumedNew = 0;
            for (FilePatch.Line line : hunk.lines()) {
                char kind = reverse ? reverseKind(line.kind()) : line.kind();
                boolean consumesOld = kind == ' ' || kind == '-';
                boolean consumesNew = kind == ' ' || kind == '+';

                if (consumesOld) {
                    if (sourceIndex >= original.size()
                            || !original.get(sourceIndex).equals(line.text())) {
                        throw hunkFailure(relative, hunkIndex, failureLine);
                    }
                    sourceIndex++;
                    consumedOld++;
                }
                if (consumesNew) {
                    result.add(line.text());
                    consumedNew++;
                }
            }

            int expectedNew = reverse ? hunk.oldCount() : hunk.newCount();
            if (consumedOld != oldCount || consumedNew != expectedNew) {
                throw hunkFailure(relative, hunkIndex, failureLine);
            }
            cursor = startIndex + oldCount;
        }
        result.addAll(original.subList(cursor, original.size()));
        return List.copyOf(result);
    }

    private static char reverseKind(char kind) {
        return switch (kind) {
            case '+' -> '-';
            case '-' -> '+';
            default -> kind;
        };
    }

    private static ApplyException hunkFailure(String relative, int hunkIndex, int line) {
        return new ApplyException(relative + ": hunk " + (hunkIndex + 1)
                + " does not apply at line " + line);
    }

    private static void writeChange(PendingChange change) throws ApplyException {
        try {
            if (change.kind() == FilePatch.Kind.DELETE) {
                Files.delete(change.target());
                return;
            }
            if (change.kind() == FilePatch.Kind.CREATE) {
                Path parent = change.target().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(change.target(), encodeLines(change.lines()),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } else {
                Files.write(change.target(), encodeLines(change.lines()),
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }
        } catch (IOException | SecurityException ex) {
            throw new ApplyException(change.relativePath() + ": cannot write");
        }
    }

    private static byte[] encodeLines(List<String> lines) {
        if (lines.isEmpty()) {
            return new byte[0];
        }
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void writeLine(PrintStream stream, String line) {
        stream.print(line);
        stream.print('\n');
    }

    private static int reportError(PrintStream err, String message) {
        writeLine(err, "linehunk: " + message);
        return 2;
    }

    private record PendingChange(
            String relativePath, Path target, FilePatch.Kind kind, List<String> lines) {
        private PendingChange {
            lines = List.copyOf(lines);
        }
    }

    /** Successful application statuses. */
    public record Result(List<String> statusLines, int fileCount, int hunkCount) {
        public Result {
            statusLines = List.copyOf(Objects.requireNonNull(statusLines, "statusLines"));
        }

        public String summaryLine() {
            String fileLabel = fileCount == 1 ? "file" : "files";
            String hunkLabel = hunkCount == 1 ? "hunk" : "hunks";
            return fileCount + " " + fileLabel + ", " + hunkCount + " " + hunkLabel;
        }
    }

    /** An application validation or filesystem error with the required CLI message. */
    public static final class ApplyException extends Exception {
        private static final long serialVersionUID = 1L;

        public ApplyException(String message) {
            super(message);
        }
    }
}
