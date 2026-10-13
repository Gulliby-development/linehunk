package io.github.gulliby.linehunk.patch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses linehunk unified patches using the format's line-by-line state rules. */
public final class PatchParser {
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -([0-9]{1,9}),([0-9]{1,9}) \\+([0-9]{1,9}),([0-9]{1,9}) @@$");

    private PatchParser() {
    }

    /**
     * Parses logical patch lines, such as those returned by {@code Lines.read(path, Lines.PATCH_LIMIT)}.
     *
     * @throws PatchException when the patch is empty or malformed
     */
    public static List<FilePatch> parse(List<String> lines) throws PatchException {
        Objects.requireNonNull(lines, "lines");
        if (lines.isEmpty()) {
            throw new PatchException(0, "empty patch");
        }

        List<FilePatch> sections = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        int index = 0;

        while (index < lines.size()) {
            int sectionLine = index + 1;
            String oldHeader = lines.get(index);
            if (!oldHeader.startsWith("--- ")) {
                throw error(sectionLine, "expected \"--- \" header");
            }
            String oldPath = oldHeader.substring(4);
            index++;

            if (index >= lines.size()) {
                throw error(lines.size() + 1, "expected \"+++ \" header");
            }
            String newHeader = lines.get(index);
            if (!newHeader.startsWith("+++ ")) {
                throw error(index + 1, "expected \"+++ \" header");
            }
            String newPath = newHeader.substring(4);
            index++;

            Header header = parseHeader(oldPath, newPath, sectionLine);
            if (!seenPaths.add(header.relativePath())) {
                throw error(sectionLine, "duplicate file " + header.relativePath());
            }

            List<FilePatch.Hunk> hunks = new ArrayList<>();
            int previousOldLast = -1;
            int previousNewLast = -1;
            boolean afterCompletedHunk = false;

            while (index < lines.size() && !lines.get(index).startsWith("--- ")) {
                String next = lines.get(index);
                int nextLine = index + 1;
                if (afterCompletedHunk && !next.startsWith("@@")) {
                    throw error(nextLine, "unexpected line after hunk");
                }
                if (!next.startsWith("@@")) {
                    throw error(nextLine, "malformed hunk header");
                }

                ParsedHunk parsed = parseHunk(lines, index, previousOldLast, previousNewLast);
                hunks.add(parsed.hunk());
                previousOldLast = parsed.oldLast();
                previousNewLast = parsed.newLast();
                index = parsed.nextIndex();
                afterCompletedHunk = true;
            }

            if (hunks.isEmpty() && header.kind() == FilePatch.Kind.MODIFY) {
                throw error(sectionLine, "file section has no hunks");
            }
            sections.add(new FilePatch(oldPath, newPath, header.relativePath(),
                    header.kind(), hunks, sectionLine));
        }

        return List.copyOf(sections);
    }

    private static Header parseHeader(String oldPath, String newPath, int line) throws PatchException {
        final String relativePath;
        final FilePatch.Kind kind;
        if (oldPath.equals("/dev/null") && newPath.startsWith("b/")) {
            relativePath = newPath.substring(2);
            kind = FilePatch.Kind.CREATE;
        } else if (oldPath.startsWith("a/") && newPath.equals("/dev/null")) {
            relativePath = oldPath.substring(2);
            kind = FilePatch.Kind.DELETE;
        } else if (oldPath.startsWith("a/") && newPath.startsWith("b/")
                && oldPath.substring(2).equals(newPath.substring(2))) {
            relativePath = oldPath.substring(2);
            kind = FilePatch.Kind.MODIFY;
        } else {
            throw error(line, "unsupported file header");
        }

        if (!isSafeRelativePath(relativePath)) {
            throw error(line, "unsafe path");
        }
        return new Header(relativePath, kind);
    }

    private static boolean isSafeRelativePath(String path) {
        if (path.isEmpty() || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) {
            return false;
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static ParsedHunk parseHunk(
            List<String> lines, int headerIndex, int previousOldLast, int previousNewLast)
            throws PatchException {
        int headerLine = headerIndex + 1;
        Matcher matcher = HUNK_HEADER.matcher(lines.get(headerIndex));
        if (!matcher.matches()) {
            throw error(headerLine, "malformed hunk header");
        }

        int oldStart = Integer.parseInt(matcher.group(1));
        int oldCount = Integer.parseInt(matcher.group(2));
        int newStart = Integer.parseInt(matcher.group(3));
        int newCount = Integer.parseInt(matcher.group(4));
        if (oldCount == 0 && newCount == 0) {
            throw error(headerLine, "empty hunk");
        }
        if ((oldCount > 0 && oldStart < 1) || (newCount > 0 && newStart < 1)) {
            throw error(headerLine, "malformed hunk header");
        }

        int oldFirst = first(oldStart, oldCount);
        int newFirst = first(newStart, newCount);
        int oldLast = last(oldStart, oldCount);
        int newLast = last(newStart, newCount);
        if (oldFirst <= previousOldLast || newFirst <= previousNewLast) {
            throw error(headerLine, "hunks out of order or overlapping");
        }

        List<FilePatch.Line> body = new ArrayList<>();
        int oldConsumed = 0;
        int newConsumed = 0;
        int index = headerIndex + 1;
        while (oldConsumed < oldCount || newConsumed < newCount) {
            if (index >= lines.size()) {
                throw error(lines.size() + 1, "hunk has fewer lines than its header says");
            }

            String text = lines.get(index);
            int patchLine = index + 1;
            char kind = text.isEmpty() ? ' ' : text.charAt(0);
            if (kind != ' ' && kind != '-' && kind != '+') {
                throw error(patchLine, "unexpected line in hunk");
            }

            boolean consumesOld = kind == ' ' || kind == '-';
            boolean consumesNew = kind == ' ' || kind == '+';
            if ((consumesOld && oldConsumed >= oldCount)
                    || (consumesNew && newConsumed >= newCount)) {
                throw error(patchLine, "hunk has more lines than its header says");
            }

            body.add(new FilePatch.Line(kind, text.isEmpty() ? "" : text.substring(1), patchLine));
            if (consumesOld) {
                oldConsumed++;
            }
            if (consumesNew) {
                newConsumed++;
            }
            index++;
        }

        return new ParsedHunk(
                new FilePatch.Hunk(oldStart, oldCount, newStart, newCount, body, headerLine),
                oldLast,
                newLast,
                index);
    }

    private static int first(int start, int count) {
        return count > 0 ? start : start + 1;
    }

    private static int last(int start, int count) {
        return count > 0 ? start + count - 1 : start;
    }

    private static PatchException error(int line, String message) {
        return new PatchException(line, message);
    }

    private record Header(String relativePath, FilePatch.Kind kind) {
    }

    private record ParsedHunk(FilePatch.Hunk hunk, int oldLast, int newLast, int nextIndex) {
    }
}
