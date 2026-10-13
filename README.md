<p align="center"><img src="docs/logo.svg" width="96" alt="" /></p>

# linehunk

Unified diff and patch apply for UTF-8 text files and directory trees, in pure Java, with the same output on every operating system.

[![CI](https://github.com/Gulliby-development/linehunk/actions/workflows/ci.yml/badge.svg)](https://github.com/Gulliby-development/linehunk/actions/workflows/ci.yml) [![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

linehunk is a small Java 21 command-line utility for comparing UTF-8 text files or directory trees and applying its unified patches. It is intended for developers and build scripts that need repeatable diffs and patch application without relying on platform-specific `diff` or `patch` programs.

## Why

Build scripts and tools often need a repeatable diff and patch step without depending on whichever `diff` or `patch` happens to be installed. linehunk produces deterministic unified diffs and applies them with exact matching, identically on Windows, macOS and Linux.

## Build

Install a JDK 21 and Maven, then build and run the automated tests:

```sh
mvn -B -q verify
```

Run the CLI from the built classes:

```sh
java -cp target/classes io.github.gulliby.linehunk.cli.Main diff a.txt b.txt
```

The project has no runtime dependencies beyond the Java standard library.

## Usage

```text
linehunk diff [-U N] [--jobs N] A B
linehunk apply [-R] [--check] PATCH DIR
```

Options may appear among operands. `-U N` selects a context size from 0 through 99 (default 3); `--jobs N` selects 1 through 8 workers for directory diffs (default 4). The last occurrence of a repeated option takes effect. Use `--help` by itself for the two-line usage message.

A JDK 21 can also launch the source directly for a quick help check:

```sh
java --source 21 src/main/java/io/github/gulliby/linehunk/cli/Main.java --help
```

It prints:

```text
Usage: linehunk diff [-U N] [--jobs N] A B
       linehunk apply [-R] [--check] PATCH DIR
```

For file diffs, both operands must be files; for tree diffs, both must be directories. A file diff uses the operand strings in its headers. A tree diff uses sorted relative paths with `a/` and `b/` prefixes. Identical files and trees produce no diff output.

To apply a patch, give its patch file and destination directory. Use `--check` to validate and report the actions without changing files, or `-R` to reverse the patch. A successful apply reports one action per file followed by a file and hunk count.

### File example

The first worked example compares a twelve-line file containing `one` through `twelve` with a file where `four` is changed to `FOUR` and `thirteen` is appended. Its default-context diff is:

```diff
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
```

With `-U 0`, the change groups have no context:

```diff
--- a.txt
+++ b.txt
@@ -4,1 +4,1 @@
-four
+FOUR
@@ -12,0 +13,1 @@
+thirteen
```

### Directory example

Suppose tree A contains `edit.txt` with lines 1–5, unchanged `same.txt`, `gone.txt` with `bye` and `now`, and an empty `empty-gone.txt`. Tree B changes line 3 of `edit.txt` to `three`, keeps `same.txt` equivalent (even if its line endings are CRLF), and adds `new/sub.txt` with `hello` and `world` plus an empty `new/empty.txt`. With one context line, the deterministic tree diff is:

```diff
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
```

The same output is produced for each worker count from 1 through 8. Applying that patch to a copy of tree A reports the following actions; `--check` uses the same lines prefixed with `would` instead:

```text
patched edit.txt
deleted empty-gone.txt
deleted gone.txt
created new/empty.txt
created new/sub.txt
5 files, 3 hunks
```

The result matches tree B. Applying the same patch again fails at the first mismatching hunk. Applying it in reverse restores tree A.

## The algorithm in plain words

Files are decoded as strict UTF-8. One leading BOM is ignored. Text is split on LF; a CR immediately before LF is removed, while a CR at end of input without LF is retained. A final line break does not create an extra logical line, and an empty file has zero lines. Thus CRLF and LF files with the same text compare equally.

The edit script uses a suffix longest-common-subsequence table. When equally long choices are possible, it deletes from the old file first. Consecutive changes form runs. With context size N, nearby runs are combined when at most 2N unchanged lines separate them; each hunk includes up to N unchanged lines on either side. Hunk ranges always print both start and count, including a count of one. A zero-count side starts at the line before the insertion or deletion position, including line zero at the start of a file.

Directory traversal considers regular files, ignores symbolic links, normalizes relative names to `/`, and sorts names with Java's string ordering. Per-file work may run concurrently, but results and errors are returned in that sorted order.

## Patch format

Patches contain ordered file sections with `--- OLD` and `+++ NEW` headers, followed by zero or more hunks of the form `@@ -a,la +b,lb @@`. A section can modify a matching `a/REL` / `b/REL` path, create a file from `/dev/null`, or delete one to `/dev/null`. Relative paths must be safe, and duplicate file paths are rejected. Empty-file create/delete sections have no hunks.

Patch application is exact: context and removed lines must match at the specified old-side position. There is no fuzz or offset search. The parser does not accept the conventional `\ No newline at end of file` marker. Patch files are limited to 100,000 logical lines; ordinary input files are limited to 2,000 lines.

Successful writes use UTF-8 without a BOM and normalize line endings to LF. Each resulting line is followed by LF; a zero-line result is written as a zero-byte file. This adds a final LF to nonempty output when needed. Creates make parent directories; deletes remove only the target file. Validation completes before any files are changed.

## Exit codes

- `0`: success. A diff has no differences, or an apply completed (or passed `--check`).
- `1`: a diff found differences and printed them.
- `2`: an error occurred. Standard output is empty and standard error contains one `linehunk: MESSAGE` line.

All emitted lines use LF endings on every operating system.

## Development

Run the full test suite and Maven verification with:

```sh
mvn -B -q verify
```

Tests use JUnit Jupiter and exercise line handling, diff rendering, deterministic tree processing, patch parsing and application, CLI behavior, and the documented examples. No external diff or patch executable is needed.

## License

MIT, see [LICENSE](LICENSE).

---

Built with BAKED, an open-source coding agent (bench loop round 11, BAKED ac1b1287).
