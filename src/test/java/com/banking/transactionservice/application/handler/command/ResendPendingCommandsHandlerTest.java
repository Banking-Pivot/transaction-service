package com.banking.transactionservice.application.handler.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.transactionservice.domain.model.ParticipantCommand;
import com.banking.transactionservice.support.InMemoryTransactionStore;
import com.banking.transactionservice.support.RecordingPublisher;
import com.banking.transactionservice.support.Transactions;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResendPendingCommandsHandlerTest {

  private static final UUID OLD_DEBIT = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID OLD_REVERSAL = UUID.fromString("00000000-0000-4000-8000-000000000002");
  private static final UUID RECENT = UUID.fromString("00000000-0000-4000-8000-000000000003");
  private static final UUID FINISHED = UUID.fromString("00000000-0000-4000-8000-000000000004");

  private final InMemoryTransactionStore store = new InMemoryTransactionStore();
  private final RecordingPublisher publisher = new RecordingPublisher();

  @Test
  void onlyTheCommandsThatWaitedTooLongAreSentAgain() {
    store.put(Transactions.pendingDebit(OLD_DEBIT));
    store.put(Transactions.pendingReversal(OLD_REVERSAL));
    store.put(Transactions.pendingDebit(RECENT).commandResent(Transactions.START.plusSeconds(50)));
    store.put(
        Transactions.pendingPayment(FINISHED)
            .paid(new BigDecimal("850.00"), Transactions.START)
            .orElseThrow());

    Long sent = handlerAt(60, 50).execute().block();

    assertThat(sent).isEqualTo(2);
    assertThat(publisher.sent())
        .extracting(sending -> sending.command().transactionId())
        .containsExactly(OLD_DEBIT, OLD_REVERSAL);
    assertThat(publisher.sent().get(0).command())
        .isInstanceOf(ParticipantCommand.DebitAccount.class);
    assertThat(publisher.sent().get(1).command())
        .isInstanceOf(ParticipantCommand.ReverseDebit.class);
    assertThat(store.get(OLD_DEBIT).getCommandAttempts()).isEqualTo(1);
    assertThat(store.get(OLD_DEBIT).getCommandSentAt())
        .isEqualTo(Transactions.START.plusSeconds(60));
    assertThat(store.get(RECENT).getCommandAttempts()).isEqualTo(1);
  }

  @Test
  void aRunSendsAtMostItsBatch() {
    store.put(Transactions.pendingDebit(OLD_DEBIT));
    store.put(Transactions.pendingReversal(OLD_REVERSAL));

    assertThat(handlerAt(60, 1).execute().block()).isEqualTo(1);
    assertThat(handlerAt(60, 1).execute().block()).isEqualTo(1);
    assertThat(handlerAt(60, 1).execute().block()).isZero();
  }

  @Test
  void aTransactionThatMovedInTheMeantimeIsLeftAlone() {
    store.put(Transactions.pendingDebit(OLD_DEBIT));
    store.failNextUpdates(1);

    assertThat(handlerAt(60, 50).execute().block()).isZero();
    assertThat(publisher.sent()).isEmpty();
    assertThat(store.get(OLD_DEBIT).getCommandAttempts()).isZero();
  }

  @Test
  void aCommandThatStillCannotBeSentIsCountedAndTriedAgainLater() {
    store.put(Transactions.pendingDebit(OLD_DEBIT));
    publisher.goDown();

    assertThat(handlerAt(60, 50).execute().block()).isEqualTo(1);
    assertThat(handlerAt(120, 50).execute().block()).isEqualTo(1);
    assertThat(store.get(OLD_DEBIT).getCommandAttempts()).isEqualTo(2);
  }

  private ResendPendingCommandsHandler handlerAt(long secondsAfterStart, int batchSize) {
    return new ResendPendingCommandsHandler(
        store,
        new CommandDispatcher(publisher, publisher),
        Clock.fixed(Transactions.START.plusSeconds(secondsAfterStart), ZoneOffset.UTC),
        Duration.ofSeconds(30),
        batchSize);
  }
}
