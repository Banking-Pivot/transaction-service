package com.banking.transactionservice.application.handler.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.transactionservice.application.exception.TransactionConcurrentModificationException;
import com.banking.transactionservice.domain.model.ParticipantCommand;
import com.banking.transactionservice.domain.model.ParticipantReply;
import com.banking.transactionservice.domain.model.TransactionStatus;
import com.banking.transactionservice.support.InMemoryTransactionStore;
import com.banking.transactionservice.support.RecordingPublisher;
import com.banking.transactionservice.support.Transactions;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApplyParticipantReplyHandlerTest {

  private static final UUID ID = UUID.fromString("4a6c8e0b-2d4f-4b1a-9c3e-5f7a9b1c3d5e");
  private static final ParticipantReply DEBITED =
      new ParticipantReply.AccountDebited(ID, new BigDecimal("1.50"), new BigDecimal("348.50"));

  private final InMemoryTransactionStore store = new InMemoryTransactionStore();
  private final RecordingPublisher publisher = new RecordingPublisher();
  private final ApplyParticipantReplyHandler handler =
      new ApplyParticipantReplyHandler(
          store,
          new CommandDispatcher(publisher, publisher),
          Clock.fixed(Transactions.START.plusSeconds(5), ZoneOffset.UTC));

  @Test
  void anAnswerMovesTheTransactionAndTheNextCommandGoesOut() {
    store.put(Transactions.pendingDebit(ID));

    handler.execute(DEBITED).block();

    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.PENDING_PAYMENT);
    assertThat(store.get(ID).getUpdatedAt()).isEqualTo(Transactions.START.plusSeconds(5));
    assertThat(publisher.last()).isInstanceOf(ParticipantCommand.PayCredit.class);
    assertThat(publisher.sent().get(0).correlationId()).isEqualTo("corr-1");
  }

  @Test
  void anAnswerThatEndsTheTransactionSendsNothing() {
    store.put(Transactions.pendingPayment(ID));

    handler.execute(new ParticipantReply.CreditPaid(ID, new BigDecimal("850.00"))).block();

    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void aRepeatedAnswerChangesNothing() {
    store.put(Transactions.pendingPayment(ID));

    handler.execute(DEBITED).block();

    assertThat(store.get(ID).getVersion()).isEqualTo(1);
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void anAnswerForATransactionThatDoesNotExistIsIgnored() {
    handler.execute(DEBITED).block();

    assertThat(store.get(ID)).isNull();
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void anAnswerThatCollidesWithAnotherChangeIsAppliedAgain() {
    store.put(Transactions.pendingDebit(ID));
    store.failNextUpdates(2);

    handler.execute(DEBITED).block();

    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.PENDING_PAYMENT);
    assertThat(publisher.sent()).hasSize(1);
  }

  @Test
  void anAnswerThatKeepsCollidingFailsSoItIsDeliveredAgain() {
    store.put(Transactions.pendingDebit(ID));
    store.failNextUpdates(10);

    assertThatThrownBy(() -> handler.execute(DEBITED).block())
        .isInstanceOf(TransactionConcurrentModificationException.class);
    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.PENDING_DEBIT);
  }

  @Test
  void theTransactionMovesEvenWhenTheNextCommandCannotBeSentYet() {
    store.put(Transactions.pendingDebit(ID));
    publisher.goDown();

    handler.execute(DEBITED).block();

    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.PENDING_PAYMENT);
  }
}
