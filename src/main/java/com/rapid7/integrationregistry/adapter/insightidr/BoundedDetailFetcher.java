package com.rapid7.integrationregistry.adapter.insightidr;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Generic bounded-concurrency fan-out helper. Runs a {@code call} over every item on a
 * virtual-thread-per-task executor, capping the number in flight at {@code concurrency} via a
 * {@link Semaphore}. Results preserve input order. This is a pure concurrency primitive: the
 * per-call timeout and exception classification live in the caller's {@code call} lambda (the IDR
 * adapter's detail RestClient carries the per-detail read timeout; search uses the per-adapter
 * timeout).
 *
 * <p>Stateless and thread-safe — registered as a singleton bean and shared across fetches.
 */
public class BoundedDetailFetcher {

  /**
   * Apply {@code call} to each item with at most {@code concurrency} concurrent invocations.
   *
   * <p>The {@link Semaphore} bounds in-flight <em>work</em>, not the number of submitted tasks: all
   * items are submitted up front, so memory scales with {@code items.size()} (one virtual thread +
   * one {@code Future} each). That is fine at MVP scale (tens of event sources per org); if the
   * deferred bulk-detail path (RFC-001 §InsightIDR endpoint detail — Phase 1.5) ever fans out over
   * thousands of sources, batch the submission to cap the thread/Future count.
   *
   * @return results in the same order as {@code items}
   * @throws IllegalArgumentException if {@code concurrency < 1}
   * @throws RuntimeException wrapping the first failing call's cause
   */
  public <T, R> List<R> fetchAll(List<T> items, int concurrency, Function<T, R> call) {
    if (concurrency < 1) {
      throw new IllegalArgumentException("concurrency must be >= 1, was " + concurrency);
    }
    if (items.isEmpty()) {
      return List.of();
    }
    Semaphore permits = new Semaphore(concurrency);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<R>> futures = new ArrayList<>(items.size());
      for (T item : items) {
        futures.add(executor.submit(guarded(permits, () -> call.apply(item))));
      }
      return collect(futures);
    }
  }

  private static <R> Callable<R> guarded(Semaphore permits, Callable<R> task) {
    return () -> {
      try {
        permits.acquire();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while acquiring permit for detail fetch", e);
      }
      try {
        return task.call();
      } finally {
        permits.release();
      }
    };
  }

  private static <R> List<R> collect(List<Future<R>> futures) {
    List<R> results = new ArrayList<>(futures.size());
    for (Future<R> future : futures) {
      results.add(await(future));
    }
    return results;
  }

  private static <R> R await(Future<R> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while fetching event-source detail", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw new IllegalStateException("Event-source detail fetch failed", cause);
    }
  }
}
