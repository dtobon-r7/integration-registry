package com.rapid7.integrationregistry.adapter.insightidr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BoundedDetailFetcherTest {

  private final BoundedDetailFetcher fetcher = new BoundedDetailFetcher();

  @Test
  void fetchAll_shouldApplyCallToEveryItem_andPreserveOrder() {
    List<Integer> items = List.of(1, 2, 3, 4, 5);
    List<Integer> result = fetcher.fetchAll(items, 2, x -> x * 10);
    assertThat(result).containsExactly(10, 20, 30, 40, 50);
  }

  @Test
  void fetchAll_shouldReturnEmpty_whenItemsEmpty() {
    assertThat(fetcher.fetchAll(List.of(), 4, x -> x)).isEmpty();
  }

  @Test
  void fetchAll_shouldNeverExceedConcurrencyLimit() throws Exception {
    int concurrency = 5;
    int total = 50;
    AtomicInteger inFlight = new AtomicInteger(0);
    AtomicInteger maxObserved = new AtomicInteger(0);
    List<Integer> items = IntStream.range(0, total).boxed().toList();

    List<Integer> result =
        fetcher.fetchAll(
            items,
            concurrency,
            x -> {
              int current = inFlight.incrementAndGet();
              maxObserved.accumulateAndGet(current, Math::max);
              try {
                Thread.sleep(20); // hold the slot so concurrent tasks overlap
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              inFlight.decrementAndGet();
              return x;
            });

    assertThat(result).hasSize(total);
    assertThat(maxObserved.get()).isLessThanOrEqualTo(concurrency);
  }

  @Test
  void fetchAll_shouldThrow_whenCallThrows() {
    List<Integer> items = List.of(1, 2, 3);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                fetcher.fetchAll(
                    items,
                    2,
                    x -> {
                      if (x == 2) {
                        throw new IllegalStateException("boom");
                      }
                      return x;
                    }))
        .isInstanceOf(RuntimeException.class);
  }
}
