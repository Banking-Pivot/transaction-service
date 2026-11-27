package com.banking.transactionservice.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.banking.transactionservice.infrastructure.config.TransactionProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class PendingCommandSchedulerTest {

  private static final TransactionProperties EVERY_20_MS =
      new TransactionProperties(
          ZoneId.of("America/Lima"),
          new TransactionProperties.Limits(new BigDecimal("5000.00"), new BigDecimal("10000.00")),
          new TransactionProperties.Messaging(
              "account",
              "credit",
              "card",
              "replies",
              "commands",
              "wallet-replies",
              Duration.ofSeconds(1),
              Duration.ofSeconds(1),
              Duration.ofMillis(20),
              Duration.ofSeconds(30),
              50));

  @Test
  void theReSendRunsAgainAndAgainUntilTheServiceStops() {
    AtomicInteger runs = new AtomicInteger();
    PendingCommandScheduler scheduler =
        new PendingCommandScheduler(
            () -> Mono.fromSupplier(() -> (long) runs.incrementAndGet()), EVERY_20_MS);

    scheduler.start();
    scheduler.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() >= 3);
    scheduler.stop();
    scheduler.stop();
    int afterStop = runs.get();

    await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(() -> true);
    assertThat(runs.get()).isLessThanOrEqualTo(afterStop + 1);
  }

  @Test
  void aRunThatFailsDoesNotStopTheNextOnes() {
    AtomicInteger runs = new AtomicInteger();
    PendingCommandScheduler scheduler =
        new PendingCommandScheduler(
            () ->
                runs.incrementAndGet() == 1
                    ? Mono.error(new IllegalStateException("database down"))
                    : Mono.just(0L),
            EVERY_20_MS);

    scheduler.start();
    try {
      await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() >= 3);
    } finally {
      scheduler.stop();
    }
  }
}
