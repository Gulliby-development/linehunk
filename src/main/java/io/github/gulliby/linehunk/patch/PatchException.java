package io.github.gulliby.linehunk.patch;

/** A patch-format error, with a 1-based patch line when one is applicable. */
public final class PatchException extends Exception {
    private static final long serialVersionUID = 1L;

    private final int lineNumber;

    public PatchException(int lineNumber, String message) {
        super(message);
        if (lineNumber < 0) {
            throw new IllegalArgumentException("lineNumber must not be negative");
        }
        this.lineNumber = lineNumber;
    }

    /** Returns the 1-based patch line, or zero for errors without a line number. */
    public int lineNumber() {
        return lineNumber;
    }

    public boolean hasLineNumber() {
        return lineNumber != 0;
    }
}
