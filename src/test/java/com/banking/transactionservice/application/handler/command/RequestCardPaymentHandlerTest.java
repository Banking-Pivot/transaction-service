package com.banking.transactionservice.application.handler.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.transactionservice.application.command.RequestCardPaymentCommand;
import com.banking.transactionservice.application.exception.OperationIdConflictException;
import com.banking.transactionservice.application.model.RequestedTransaction;
import com.banking.transactionservice.domain.exception.RuleViolation;
import com.banking.transactionservice.domain.exception.TransactionRuleViolationException;
import com.banking.transactionservice.domain.model.CardPaymentLimits;
import com.banking.transactionservice.domain.model.CurrencyCode;
import com.banking.transactionservice.domain.model.ParticipantCommand;
import com.banking.transactionservice.domain.model.Transaction;
import com.banking.transactionservice.domain.model.TransactionStatus;
import com.banking.transactionservice.domain.model.TransactionType;
import com.banking.transactionservice.support.InMemoryTransactionStore;
import com.banking.transactionservice.support.RecordingPublisher;
import com.banking.transactionservice.support.Transactions;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestCardPaymentHandlerTest {

  private static final UUID ID = UUID.fromString("7b9d1f3a-5c7e-4a2b-8d4f-6a8c0e2b4d6f");

  private final InMemoryTransactionStore store = new InMemoryTransactionStore();
  private final RecordingPublisher publisher = new RecordingPublisher();
  private final RequestCardPaymentHandler handler =
      new RequestCardPaymentHandler(
          store,
          new CommandDispatcher(publisher, publisher),
          new CardPaymentLimits(
              new BigDecimal("5000.00"), new BigDecimal("10000.00"), ZoneId.of("America/Lima")),
          Clock.fixed(Transactions.START, ZoneOffset.UTC),
          () -> Transactions.REVERSAL);

  @Test
  void aNewRequestIsStoredAndTheCardIsAskedAboutWithItsCorrelationId() {
    RequestedTransaction result = handler.execute(command("80.00", "corr-7")).block();

    assertThat(result.replayed()).isFalse();
    assertThat(result.transaction().getType()).isEqualTo(TransactionType.CARD_PAYMENT);
    assertThat(result.transaction().getStatus()).isEqualTo(TransactionStatus.PENDING_CARD);
    assertThat(store.get(ID).getSourceCardId()).isEqualTo(Transactions.CARD);
    assertThat(publisher.commands())
        .containsExactly(new ParticipantCommand.ResolveCard(ID, Transactions.CARD));
    assertThat(publisher.sent().get(0).correlationId()).isEqualTo("corr-7");
    assertThat(publisher.outcomes()).isEmpty();
  }

  @Test
  void aRepeatWithTheSameDataReturnsTheStoredTransactionAndSendsNothing() {
    store.put(Transactions.cardPaymentPendingDebit(ID));

    RequestedTransaction result = handler.execute(command("80.00", "corr-8")).block();

    assertThat(result.replayed()).isTrue();
    assertThat(result.transaction().getStatus()).isEqualTo(TransactionStatus.PENDING_DEBIT);
    assertThat(result.transaction().getCorrelationId()).isEqualTo("corr-1");
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void theSameOperationIdWithOtherDataOrOfACreditPaymentIsAConflict() {
    store.put(Transactions.cardPayment(ID));
    assertThatThrownBy(() -> handler.execute(command("81.00", "corr-9")).block())
        .isInstanceOf(OperationIdConflictException.class);

    store.put(Transactions.pendingDebit(ID));
    assertThatThrownBy(() -> handler.execute(command("150.00", "corr-9")).block())
        .isInstanceOf(OperationIdConflictException.class);
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void anAmountTheDomainRejectsStoresNothing() {
    assertThatThrownBy(() -> handler.execute(command("0.001", "corr-13")).block())
        .isInstanceOf(TransactionRuleViolationException.class);
    assertThat(store.get(ID)).isNull();
  }

  @Test
  void aPaymentOfExactlyTheSingleLimitIsAcceptedAndOneCentMoreIsRefused() {
    assertThatThrownBy(() -> handler.execute(command("5000.01", "corr-14")).block())
        .isInstanceOfSatisfying(
            TransactionRuleViolationException.class,
            refused ->
                assertThat(refused.violation())
                    .isEqualTo(RuleViolation.CARD_PAYMENT_LIMIT_EXCEEDED));
    assertThat(store.get(ID)).isNull();
    assertThat(publisher.sent()).isEmpty();

    assertThat(handler.execute(command("5000.00", "corr-14")).block().replayed()).isFalse();
    assertThat(publisher.commands()).hasSize(1);
  }

  @Test
  void theDayOfTheCardCanBeFilledExactly() {
    store.put(today("5000.00"));
    store.put(today("4920.00"));

    assertThat(handler.execute(command("80.00", "corr-15")).block().replayed()).isFalse();

    assertThat(store.get(ID)).isNotNull();
    assertThat(publisher.commands()).hasSize(1);
  }

  @Test
  void aPaymentThatTakesTheCardOverItsDailyLimitIsRefusedAndNothingIsStoredOrSent() {
    store.put(today("5000.00"));
    // Being processed or completed, a payment counts all the same.
    store.put(
        today("4920.01")
            .cardResolved(
                Transactions.CARD,
                Transactions.ACCOUNT,
                List.of(Transactions.ACCOUNT),
                Transactions.START.plusSeconds(1))
            .flatMap(next -> next.debited(BigDecimal.ZERO, BigDecimal.TEN, Transactions.START))
            .orElseThrow());

    assertThatThrownBy(() -> handler.execute(command("80.00", "corr-16")).block())
        .isInstanceOfSatisfying(
            TransactionRuleViolationException.class,
            refused ->
                assertThat(refused.violation()).isEqualTo(RuleViolation.CARD_DAILY_LIMIT_EXCEEDED));
    assertThat(store.get(ID)).isNull();
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void onlyTodaysLivePaymentsOfTheSameCardCountAgainstTheDailyLimit() {
    // Refused by Card Service: no money moved.
    store.put(
        today("5000.00")
            .cardRejected(Transactions.CARD, "CARD_NOT_ACTIVE", "Blocked.", Transactions.START)
            .orElseThrow());
    store.put(today("5000.00").toBuilder().status(TransactionStatus.REVERSED).build());
    // The business day is the one of Lima: it started at 05:00 UTC and ends at 05:00 UTC.
    store.put(payment(Transactions.CARD, "5000.00", Instant.parse("2026-11-20T04:59:59Z")));
    store.put(payment(Transactions.CARD, "5000.00", Instant.parse("2026-11-21T05:00:00Z")));
    store.put(payment(Transactions.OTHER_CARD, "5000.00", Transactions.START));
    // What Yanki Service moves with the card has its own limits in the wallet.
    store.put(Transactions.walletPayment(UUID.randomUUID(), Transactions.CARD, null));
    store.put(Transactions.walletPayment(UUID.randomUUID(), null, Transactions.CARD));
    store.put(Transactions.pendingDebit(UUID.randomUUID()));
    store.put(payment(Transactions.CARD, "5000.00", Instant.parse("2026-11-20T05:00:00Z")));
    store.put(payment(Transactions.CARD, "4920.00", Instant.parse("2026-11-21T04:59:59Z")));

    assertThat(handler.execute(command("80.00", "corr-17")).block().replayed()).isFalse();
    assertThat(publisher.commands()).hasSize(1);
  }

  @Test
  void aRepeatIsAnsweredWithoutCheckingTheLimitsAgain() {
    // Recorded when the limits allowed it; today the card is full and the amount is over the limit.
    store.put(Transactions.cardPayment(ID, Transactions.CARD, "6000.00", Transactions.START));
    store.put(today("5000.00"));

    RequestedTransaction result = handler.execute(command("6000.00", "corr-18")).block();

    assertThat(result.replayed()).isTrue();
    assertThat(result.transaction().getAmount()).isEqualByComparingTo("6000.00");
    assertThat(publisher.sent()).isEmpty();
  }

  private static Transaction today(String amount) {
    return payment(Transactions.CARD, amount, Transactions.START);
  }

  private static Transaction payment(UUID cardId, String amount, Instant requestedAt) {
    return Transactions.cardPayment(UUID.randomUUID(), cardId, amount, requestedAt);
  }

  private static RequestCardPaymentCommand command(String amount, String correlationId) {
    return new RequestCardPaymentCommand(
        ID,
        Transactions.CARD,
        new BigDecimal(amount),
        CurrencyCode.PEN,
        "Grocery store",
        correlationId);
  }
}
