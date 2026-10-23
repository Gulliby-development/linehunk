package io.github.gulliby.linehunk.diff;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reads UTF-8 text into logical lines using linehunk's platform-independent rules. */
public final class Lines {
    public static final int DEFAULT_LIMIT = 2_000;
    public static final int PATCH_LIMIT = 100_000;

    private Lines() {
    }

    /** Reads a regular input file using the ordinary-file line limit. */
    public static List<String> read(Path path) throws IOException {
        return read(path, DEFAULT_LIMIT);
    }

    /** Reads all bytes of a stream using the supplied maximum number of logical lines. */
    public static List<String> read(InputStream in, int maxLines) throws IOException {
        Objects.requireNonNull(in, "in");
        return decode(in.readAllBytes(), maxLines);
    }

    /** Reads a file using the supplied maximum number of logical lines. */
    public static List<String> read(Path path, int maxLines) throws IOException {
        Objects.requireNonNull(path, "path");
        return decode(Files.readAllBytes(path), maxLines);
    }

    /**
     * Decodes strict UTF-8 bytes and splits them into logical lines.
     *
     * <p>A CR is removed only when it immediately precedes LF. A CR at EOF is
     * retained. The empty piece after a final LF is not a logical line.</p>
     */
    public static List<String> decode(byte[] bytes, int maxLines) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (maxLines < 0) {
            throw new IllegalArgumentException("maxLines must not be negative");
        }

        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new ReadException(ReadFailure.INVALID_UTF8, ex);
        }

        return split(text, maxLines);
    }

    private static List<String> split(String decoded, int maxLines) throws ReadException {
        String text = decoded;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }

        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int lf = text.indexOf('\n'); lf >= 0; lf = text.indexOf('\n', start)) {
            int end = lf;
            if (end > start && text.charAt(end - 1) == '\r') {
                end--;
            }
            addLine(lines, text.substring(start, end), maxLines);
            start = lf + 1;
        }

        if (start < text.length()) {
            addLine(lines, text.substring(start), maxLines);
        }
        return List.copyOf(lines);
    }

    private static void addLine(List<String> lines, String line, int maxLines) throws ReadException {
        if (lines.size() >= maxLines) {
            throw new ReadException(ReadFailure.TOO_MANY_LINES);
        }
        lines.add(line);
    }

    public enum ReadFailure {
        INVALID_UTF8,
        TOO_MANY_LINES
    }

    /** An input decoding or line-limit failure; filesystem failures remain ordinary IOExceptions. */
    public static final class ReadException extends IOException {
        private final ReadFailure failure;

        public ReadException(ReadFailure failure) {
            super(Objects.requireNonNull(failure, "failure").name());
            this.failure = failure;
        }

        public ReadException(ReadFailure failure, Throwable cause) {
            super(Objects.requireNonNull(failure, "failure").name(), cause);
            this.failure = failure;
        }

        public ReadFailure failure() {
            return failure;
        }
    }
}
