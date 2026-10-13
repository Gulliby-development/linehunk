package io.github.gulliby.linehunk.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Runs independent work in parallel while preserving input order in its results. */
public final class OrderedParallelMap {
    private OrderedParallelMap() {
    }

    /**
     * Maps {@code items} using a fixed-size worker pool and returns results in item order.
     * All submitted work is allowed to finish before a failure is reported; if multiple
     * tasks fail, the failure belonging to the earliest input item is reported.
     *
     * @param items input items
     * @param jobs number of worker threads; must be positive
     * @param mapper work to perform for each item
     * @param <I> input item type
     * @param <O> result type
     * @return immutable results in input order
     * @throws ParallelMapException when a task fails with a checked exception or the
     *         calling thread is interrupted
     * @throws RuntimeException the earliest task's runtime failure, unchanged
     * @throws Error the earliest task's error, unchanged
     */
    public static <I, O> List<O> map(
            List<? extends I> items,
            int jobs,
            Mapper<? super I, ? extends O> mapper) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(mapper, "mapper");
        if (jobs < 1) {
            throw new IllegalArgumentException("jobs must be positive");
        }
        if (items.isEmpty()) {
            return List.of();
        }

        List<? extends I> input = new ArrayList<>(items);
        ExecutorService executor = Executors.newFixedThreadPool(jobs);
        List<Future<O>> futures = new ArrayList<>(input.size());
        for (I item : input) {
            futures.add(executor.submit(() -> mapper.apply(item)));
        }
        executor.shutdown();

        List<O> results = new ArrayList<>(input.size());
        Throwable firstFailure = null;
        int firstFailureIndex = -1;
        boolean interrupted = false;

        for (int index = 0; index < futures.size(); index++) {
            Future<O> future = futures.get(index);
            O result = null;
            boolean completed = false;
            while (!completed) {
                try {
                    result = future.get();
                    completed = true;
                } catch (InterruptedException ex) {
                    // Keep collecting every task result before restoring interruption.
                    interrupted = true;
                } catch (ExecutionException ex) {
                    if (firstFailure == null) {
                        firstFailure = ex.getCause() == null ? ex : ex.getCause();
                        firstFailureIndex = index;
                    }
                    completed = true;
                } catch (CancellationException ex) {
                    if (firstFailure == null) {
                        firstFailure = ex;
                        firstFailureIndex = index;
                    }
                    completed = true;
                }
            }
            results.add(result);
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
            if (firstFailure == null) {
                firstFailure = new InterruptedException("interrupted while waiting for parallel tasks");
                firstFailureIndex = -1;
            }
        }
        if (firstFailure instanceof Error error) {
            throw error;
        }
        if (firstFailure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (firstFailure != null) {
            throw new ParallelMapException(firstFailureIndex, firstFailure);
        }
        return List.copyOf(results);
    }

    /** Work submitted for one item; checked exceptions are reported in input order. */
    @FunctionalInterface
    public interface Mapper<I, O> {
        O apply(I item) throws Exception;
    }

    /** A checked task failure, annotated with the input position that produced it. */
    public static final class ParallelMapException extends RuntimeException {
        private final int itemIndex;

        public ParallelMapException(int itemIndex, Throwable cause) {
            super(itemIndex < 0
                    ? "parallel map interrupted"
                    : "parallel task failed at item index " + itemIndex, cause);
            this.itemIndex = itemIndex;
        }

        /** Zero-based input index, or -1 when the caller was interrupted. */
        public int itemIndex() {
            return itemIndex;
        }
    }
}
