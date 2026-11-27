package com.banking.transactionservice.infrastructure.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.transactionservice.application.exception.TransactionAlreadyExistsException;
import com.banking.transactionservice.application.exception.TransactionConcurrentModificationException;
import com.banking.transactionservice.application.port.out.TransactionStorePort;
import com.banking.transactionservice.application.query.ListTransactionsQuery;
import com.banking.transactionservice.application.query.PageRequest;
import com.banking.transactionservice.domain.model.Transaction;
import com.banking.transactionservice.domain.model.enums.RejectedStep;
import com.banking.transactionservice.domain.model.enums.TransactionStatus;
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

  @Test
  void aCardPaymentComesBackAsItWasStoredWhileItTriesTheAccountsOfTheCard() {
    UUID id = UUID.randomUUID();
    Transaction requested = unique(Transactions.cardPayment(id));
    store.insert(requested).block();
    assertThat(store.findById(id).block()).usingRecursiveComparison().isEqualTo(requested);

    Transaction first =
        requested
            .cardResolved(
                Transactions.CARD,
                Transactions.ACCOUNT,
                List.of(Transactions.ACCOUNT, Transactions.SECOND_ACCOUNT),
                Transactions.START.plusSeconds(1))
            .orElseThrow();
    store.update(first, requested.getVersion()).block();
    assertThat(store.findById(id).block()).usingRecursiveComparison().isEqualTo(first);

    Transaction second =
        first
            .debitRejected("INSUFFICIENT_FUNDS", "No funds.", Transactions.START.plusSeconds(2))
            .orElseThrow();
    store.update(second, first.getVersion()).block();
    Transaction stored = store.findById(id).block();
    assertThat(stored).usingRecursiveComparison().isEqualTo(second);
    assertThat(stored.getAccountId()).isEqualTo(Transactions.SECOND_ACCOUNT);
    assertThat(stored.getCandidateAccountIds())
        .containsExactly(Transactions.ACCOUNT, Transactions.SECOND_ACCOUNT);

    Transaction completed =
        second
            .debited(BigDecimal.ZERO.setScale(2), new BigDecimal("20.00"), Transactions.START)
            .orElseThrow();
    store.update(completed, second.getVersion()).block();
    assertThat(store.findById(id).block()).usingRecursiveComparison().isEqualTo(completed);
  }

  @Test
  void aWalletPaymentComesBackAsItWasStoredThroughEveryStep() {
    UUID id = UUID.randomUUID();
    Transaction requested =
        unique(Transactions.walletPayment(id, Transactions.CARD, Transactions.OTHER_CARD));
    store.insert(requested).block();
    Transaction oneKnown =
        requested
            .cardResolved(
                Transactions.OTHER_CARD,
                Transactions.DESTINATION,
                List.of(Transactions.DESTINATION),
                Transactions.START.plusSeconds(1))
            .orElseThrow();
    store.update(oneKnown, requested.getVersion()).block();
    assertThat(store.findById(id).block()).usingRecursiveComparison().isEqualTo(oneKnown);

    Transaction debited =
        oneKnown
            .cardResolved(
                Transactions.CARD,
                Transactions.ACCOUNT,
                List.of(Transactions.ACCOUNT),
                Transactions.START.plusSeconds(2))
            .flatMap(
                next ->
                    next.debited(
                        new BigDecimal("0.50"),
                        new BigDecimal("39.50"),
                        Transactions.START.plusSeconds(3)))
            .orElseThrow();
    store.update(debited, oneKnown.getVersion()).block();
    Transaction completed =
        debited.credited(new BigDecimal("0.25"), Transactions.START.plusSeconds(4)).orElseThrow();
    store.update(completed, debited.getVersion()).block();

    Transaction stored = store.findById(id).block();
    assertThat(stored).usingRecursiveComparison().isEqualTo(completed);
    assertThat(stored.outcome()).isEqualTo(completed.outcome());
  }

  @Test
  void aCardFindsTheTransactionsItIsOnEitherSideOfAndTheStatusStillNarrowsThem() {
    UUID card = UUID.randomUUID();
    Transaction paying =
        unique(Transactions.cardPayment(UUID.randomUUID()).toBuilder().sourceCardId(card).build());
    Transaction receiving = unique(Transactions.walletPayment(UUID.randomUUID(), null, card));
    Transaction refused =
        unique(Transactions.walletPayment(UUID.randomUUID(), null, card))
            .cardRejected(card, "CARD_NOT_ACTIVE", "Blocked.", Transactions.START.plusSeconds(1))
            .orElseThrow();
    Transaction another = unique(Transactions.cardPayment(UUID.randomUUID()));
    for (Transaction transaction : List.of(paying, receiving, refused, another)) {
      store.insert(transaction).block();
    }

    assertThat(ids(new ListTransactionsQuery(PageRequest.of(0, 20), null, null, card, null)))
        .containsExactlyInAnyOrder(paying.getId(), receiving.getId(), refused.getId());
    assertThat(
            ids(
                new ListTransactionsQuery(
                    PageRequest.of(0, 20), null, null, card, TransactionStatus.PENDING_CARD)))
        .containsExactlyInAnyOrder(paying.getId(), receiving.getId());
    assertThat(
            ids(
                new ListTransactionsQuery(
                    PageRequest.of(0, 20), null, null, card, TransactionStatus.REJECTED)))
        .containsExactly(refused.getId());
    assertThat(
            ids(
                new ListTransactionsQuery(
                    PageRequest.of(0, 20), Transactions.ACCOUNT, null, card, null)))
        .isEmpty();
  }

  @Test
  void theLatestMovementsOfACardAreItsCompletedTransactionsNewestFirst() {
    UUID card = UUID.randomUUID();
    Transaction oldest = completedPayment(card, 10);
    Transaction newest = completedPayment(card, 30);
    Transaction middle =
        unique(Transactions.walletPayment(UUID.randomUUID(), null, card))
            .cardResolved(
                card,
                Transactions.DESTINATION,
                List.of(Transactions.DESTINATION),
                Transactions.START.plusSeconds(1))
            .flatMap(next -> next.credited(BigDecimal.ZERO, Transactions.START.plusSeconds(20)))
            .orElseThrow();
    Transaction pending =
        unique(Transactions.cardPayment(UUID.randomUUID()).toBuilder().sourceCardId(card).build());
    Transaction ofAnotherCard = completedPayment(UUID.randomUUID(), 40);
    for (Transaction transaction : List.of(oldest, newest, middle, pending, ofAnotherCard)) {
      store.insert(transaction).block();
    }

    assertThat(
            store.findLatestCompletedByCard(card, 10).map(Transaction::getId).collectList().block())
        .containsExactly(newest.getId(), middle.getId(), oldest.getId());
    assertThat(
            store.findLatestCompletedByCard(card, 2).map(Transaction::getId).collectList().block())
        .containsExactly(newest.getId(), middle.getId());
    assertThat(store.findLatestCompletedByCard(UUID.randomUUID(), 10).collectList().block())
        .isEmpty();
  }

  @Test
  void theCountedCardPaymentsAreTheLiveOnesOfTheCardRequestedInTheWindow() {
    UUID card = UUID.randomUUID();
    Instant from = Transactions.START;
    Instant until = from.plusSeconds(3600);
    Transaction atTheStart = cardPaymentAt(card, "100.00", from);
    Transaction atTheEnd = cardPaymentAt(card, "200.50", until.minusMillis(1));
    Transaction completed = cardPaymentAt(card, "300.00", from.plusSeconds(10));
    Transaction rejected = cardPaymentAt(card, "400.00", from.plusSeconds(20));
    Transaction reversed = cardPaymentAt(card, "450.00", from.plusSeconds(25));
    Transaction before = cardPaymentAt(card, "500.00", from.minusMillis(1));
    Transaction after = cardPaymentAt(card, "600.00", until);
    Transaction ofAnotherCard = cardPaymentAt(UUID.randomUUID(), "700.00", from.plusSeconds(30));
    Transaction walletFromTheCard =
        unique(Transactions.walletPayment(UUID.randomUUID(), card, null));
    Transaction walletToTheCard = unique(Transactions.walletPayment(UUID.randomUUID(), null, card));
    for (Transaction transaction :
        List.of(
            atTheStart,
            atTheEnd,
            completed,
            rejected,
            reversed,
            before,
            after,
            ofAnotherCard,
            walletFromTheCard,
            walletToTheCard,
            fresh(UUID.randomUUID()))) {
      store.insert(transaction).block();
    }
    Transaction resolved =
        completed
            .cardResolved(card, Transactions.ACCOUNT, List.of(Transactions.ACCOUNT), from)
            .orElseThrow();
    store.update(resolved, completed.getVersion()).block();
    store
        .update(
            resolved.debited(BigDecimal.ZERO, BigDecimal.TEN, from).orElseThrow(),
            resolved.getVersion())
        .block();
    store
        .update(
            rejected.cardRejected(card, "CARD_NOT_ACTIVE", "Blocked.", from).orElseThrow(),
            rejected.getVersion())
        .block();
    // A card payment never reaches REVERSED by itself; the rule leaves the status out anyway.
    databaseClient
        .sql(
            "UPDATE transactions SET status = 'REVERSED', account_id = :account,"
                + " rejection_step = 'DEBIT', rejection_code = 'X', rejection_message = 'X'"
                + " WHERE id = :id")
        .bind("account", Transactions.ACCOUNT)
        .bind("id", reversed.getId())
        .then()
        .block();

    List<Transaction> counted =
        store.findCountedCardPayments(card, from, until).collectList().block();

    assertThat(counted)
        .extracting(Transaction::getId)
        .containsExactlyInAnyOrder(atTheStart.getId(), atTheEnd.getId(), completed.getId());
    assertThat(
            counted.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("600.50");
    assertThat(store.findCountedCardPayments(UUID.randomUUID(), from, until).collectList().block())
        .isEmpty();
  }

  private static Transaction cardPaymentAt(UUID cardId, String amount, Instant requestedAt) {
    return Transactions.cardPayment(UUID.randomUUID(), cardId, amount, requestedAt);
  }

  private List<UUID> ids(ListTransactionsQuery query) {
    return store.findPage(query).block().items().stream().map(Transaction::getId).toList();
  }

  private static Transaction completedPayment(UUID cardId, long secondsAfterStart) {
    return unique(
            Transactions.cardPayment(UUID.randomUUID()).toBuilder().sourceCardId(cardId).build())
        .cardResolved(
            cardId,
            Transactions.ACCOUNT,
            List.of(Transactions.ACCOUNT),
            Transactions.START.plusSeconds(1))
        .flatMap(
            next ->
                next.debited(
                    BigDecimal.ZERO,
                    BigDecimal.TEN,
                    Transactions.START.plusSeconds(secondsAfterStart)))
        .orElseThrow();
  }

  private static Transaction unique(Transaction transaction) {
    return transaction.toBuilder().reversalOperationId(UUID.randomUUID()).build();
  }

  // Each test needs its own reversal operationId: the column is unique.
  private static Transaction fresh(UUID id) {
    return Transactions.pendingDebit(id).toBuilder().reversalOperationId(UUID.randomUUID()).build();
  }
}
