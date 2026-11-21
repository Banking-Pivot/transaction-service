package com.banking.transactionservice.infrastructure.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.transactionservice.application.exception.TransactionAlreadyExistsException;
import com.banking.transactionservice.application.exception.TransactionConcurrentModificationException;
import com.banking.transactionservice.application.port.out.TransactionStorePort;
import com.banking.transactionservice.domain.model.RejectedStep;
import com.banking.transactionservice.domain.model.Transaction;
import com.banking.transactionservice.domain.model.TransactionStatus;
import com.banking.transactionservice.support.IntegrationTest;
import com.banking.transactionservice.support.Transactions;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** The store of transactions against a real PostgreSQL. */
class PostgresTransactionAdapterTest extends IntegrationTest {

  @Autowired private TransactionStorePort store;

  @Test
  void aTransactionComesBackAsItWasStoredThroughEveryStep() {
    UUID id = UUID.randomUUID();
    Transaction requested = fresh(id);
    store.insert(requested).block();

    assertThat(store.findById(id).block()).usingRecursiveComparison().isEqualTo(requested);

    Transaction debited =
        requested
            .debited(
                new BigDecimal("1.50"), new BigDecimal("348.50"), Transactions.START.plusSeconds(1))
            .orElseThrow();
    store.update(debited, requested.getVersion()).block();
    assertThat(store.findById(id).block()).usingRecursiveComparison().isEqualTo(debited);

    Transaction waitingForReversal =
        debited
            .paymentRejected(
                "PAYMENT_EXCEEDS_DEBT",
                "The payment exceeds the debt.",
                Transactions.START.plusSeconds(2))
            .orElseThrow();
    store.update(waitingForReversal, debited.getVersion()).block();
    Transaction failed =
        waitingForReversal
            .reversalRejected(
                "ACCOUNT_CLOSED", "The account is closed.", Transactions.START.plusSeconds(3))
            .orElseThrow();
    store.update(failed, waitingForReversal.getVersion()).block();

    Transaction stored = store.findById(id).block();
    assertThat(stored).usingRecursiveComparison().isEqualTo(failed);
    assertThat(stored.getStatus()).isEqualTo(TransactionStatus.REVERSAL_FAILED);
    assertThat(stored.getRejection().step()).isEqualTo(RejectedStep.PAYMENT);
    assertThat(stored.getReversalFailure().code()).isEqualTo("ACCOUNT_CLOSED");
    assertThat(stored.getFee()).isEqualByComparingTo("1.50");
  }

  @Test
  void anIdIsStoredOnlyOnce() {
    UUID id = UUID.randomUUID();
    store.insert(fresh(id)).block();

    assertThatThrownBy(() -> store.insert(fresh(id)).block())
        .isInstanceOf(TransactionAlreadyExistsException.class);
  }

  @Test
  void aFailureThatIsNotARepeatedIdIsNotMistakenForOne() {
    UUID first = UUID.randomUUID();
    store.insert(fresh(first)).block();
    Transaction sameReversalKey =
        fresh(UUID.randomUUID()).toBuilder()
            .reversalOperationId(store.findById(first).block().getReversalOperationId())
            .build();

    assertThatThrownBy(() -> store.insert(sameReversalKey).block())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void anUpdateNeedsTheVersionTheTransactionWasReadWith() {
    UUID id = UUID.randomUUID();
    Transaction requested = fresh(id);
    store.insert(requested).block();
    Transaction resent = requested.commandResent(Transactions.START.plusSeconds(40));
    store.update(resent, requested.getVersion()).block();
    Transaction rejected =
        requested
            .debitRejected("INSUFFICIENT_FUNDS", "No funds.", Transactions.START.plusSeconds(41))
            .orElseThrow();

    assertThatThrownBy(() -> store.update(rejected, requested.getVersion()).block())
        .isInstanceOf(TransactionConcurrentModificationException.class);
    assertThatThrownBy(() -> store.update(fresh(UUID.randomUUID()), 0).block())
        .isInstanceOf(TransactionConcurrentModificationException.class);

    Transaction stored = store.findById(id).block();
    assertThat(stored.getStatus()).isEqualTo(TransactionStatus.PENDING_DEBIT);
    assertThat(stored.getCommandAttempts()).isEqualTo(1);
    assertThat(stored.getRejection()).isNull();
  }

  @Test
  void onlyTransactionsThatStillWaitAndWaitedLongEnoughAreFoundOldestFirst() {
    UUID oldest = UUID.randomUUID();
    UUID old = UUID.randomUUID();
    UUID recent = UUID.randomUUID();
    UUID finished = UUID.randomUUID();
    store.insert(fresh(old).commandResent(Transactions.START.plusSeconds(10))).block();
    store.insert(fresh(oldest)).block();
    store.insert(fresh(recent).commandResent(Transactions.START.plusSeconds(100))).block();
    Transaction done = fresh(finished);
    store.insert(done).block();
    store
        .update(
            done.debitRejected("INSUFFICIENT_FUNDS", "No funds.", Transactions.START).orElseThrow(),
            done.getVersion())
        .block();
    Instant limit = Transactions.START.plusSeconds(60);

    List<UUID> waiting =
        store.findWaitingSince(limit, 50).map(Transaction::getId).collectList().block();
    List<UUID> firstOnly =
        store.findWaitingSince(limit, 1).map(Transaction::getId).collectList().block();

    assertThat(waiting).containsExactly(oldest, old);
    assertThat(firstOnly).containsExactly(oldest);
    assertThat(store.findById(UUID.randomUUID()).blockOptional()).isEmpty();
  }

  // Each test needs its own reversal operationId: the column is unique.
  private static Transaction fresh(UUID id) {
    return Transactions.pendingDebit(id).toBuilder().reversalOperationId(UUID.randomUUID()).build();
  }
}
