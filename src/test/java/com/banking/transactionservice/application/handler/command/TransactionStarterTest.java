package com.banking.transactionservice.application.handler.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.transactionservice.application.model.RequestedTransaction;
import com.banking.transactionservice.domain.exception.RuleViolation;
import com.banking.transactionservice.domain.exception.TransactionRuleViolationException;
import com.banking.transactionservice.domain.model.Transaction;
import com.banking.transactionservice.domain.model.vo.ParticipantCommand;
import com.banking.transactionservice.support.InMemoryTransactionStore;
import com.banking.transactionservice.support.RecordingPublisher;
import com.banking.transactionservice.support.Transactions;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class TransactionStarterTest {

  private static final UUID ID = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");

  private final InMemoryTransactionStore store = new InMemoryTransactionStore();
  private final RecordingPublisher publisher = new RecordingPublisher();
  private final TransactionStarter starter =
      new TransactionStarter(store, new CommandDispatcher(publisher, publisher));
  private final AtomicInteger admissions = new AtomicInteger();
  private final Mono<Void> admitted = Mono.fromRunnable(admissions::incrementAndGet);

  @Test
  void anAdmittedTransactionIsStoredAndItsFirstCommandIsSent() {
    Transaction requested = Transactions.cardPayment(ID);

    RequestedTransaction result = starter.start(requested, admitted).block();

    assertThat(result.replayed()).isFalse();
    assertThat(admissions).hasValue(1);
    assertThat(store.get(ID)).isSameAs(requested);
    assertThat(publisher.commands())
        .containsExactly(new ParticipantCommand.ResolveCard(ID, Transactions.CARD));
  }

  @Test
  void aTransactionThatIsNotAdmittedIsNeitherStoredNorSent() {
    Mono<Void> refused =
        Mono.error(
            new TransactionRuleViolationException(
                RuleViolation.CARD_DAILY_LIMIT_EXCEEDED, "Over the limit."));

    assertThatThrownBy(() -> starter.start(Transactions.cardPayment(ID), refused).block())
        .isInstanceOf(TransactionRuleViolationException.class);

    assertThat(store.get(ID)).isNull();
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void aRecordedIdIsAnsweredWithoutAskingForAdmissionAgain() {
    store.put(Transactions.cardPaymentPendingDebit(ID));

    RequestedTransaction result = starter.start(Transactions.cardPayment(ID), admitted).block();

    assertThat(result.replayed()).isTrue();
    assertThat(admissions).hasValue(0);
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void withoutRulesOfAdmissionTheTransactionSimplyStarts() {
    RequestedTransaction result = starter.start(Transactions.pendingDebit(ID)).block();

    assertThat(result.replayed()).isFalse();
    assertThat(store.get(ID)).isNotNull();
    assertThat(publisher.commands()).hasSize(1);
  }
}
