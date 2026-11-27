package com.banking.transactionservice.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.transactionservice.domain.exception.RuleViolation;
import com.banking.transactionservice.domain.exception.TransactionRuleViolationException;
import com.banking.transactionservice.domain.model.enums.CurrencyCode;
import com.banking.transactionservice.domain.model.enums.RejectedStep;
import com.banking.transactionservice.domain.model.enums.TransactionStatus;
import com.banking.transactionservice.domain.model.enums.TransactionType;
import com.banking.transactionservice.domain.model.vo.ParticipantCommand;
import com.banking.transactionservice.domain.model.vo.ParticipantReply;
import com.banking.transactionservice.domain.model.vo.Rejection;
import com.banking.transactionservice.domain.model.vo.ReversalFailure;
import com.banking.transactionservice.support.Transactions;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransactionTest {

  private static final UUID ID = UUID.fromString("4a6c8e0b-2d4f-4b1a-9c3e-5f7a9b1c3d5e");
  private static final Instant LATER = Transactions.START.plusSeconds(60);

  @Test
  void aNewPaymentWaitsForTheDebitAndAsksAccountServiceForIt() {
    Transaction transaction = Transactions.pendingDebit(ID);

    assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.PENDING_DEBIT);
    assertThat(transaction.getType()).isEqualTo(TransactionType.CREDIT_PAYMENT);
    assertThat(transaction.getVersion()).isZero();
    assertThat(transaction.getCommandAttempts()).isZero();
    assertThat(transaction.getCreatedAt()).isEqualTo(Transactions.START);
    assertThat(transaction.nextCommands())
        .contains(
            new ParticipantCommand.DebitAccount(
                ID,
                ID,
                Transactions.ACCOUNT,
                new BigDecimal("150.00"),
                CurrencyCode.PEN,
                "November installment"));
  }

  @Test
  void theAmountIsKeptWithTwoDecimalsAndABlankDescriptionIsNone() {
    Transaction transaction =
        Transaction.creditPayment(
            ID,
            Transactions.ACCOUNT,
            Transactions.CREDIT,
            new BigDecimal("150"),
            CurrencyCode.USD,
            "   ",
            Transactions.REVERSAL,
            "corr-1",
            Transactions.START);

    assertThat(transaction.getAmount()).isEqualTo(new BigDecimal("150.00"));
    assertThat(transaction.getDescription()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1.00", "0.001", "1000000000000.00"})
  void anAmountThatCannotBeMovedIsRejected(String amount) {
    assertThatThrownBy(() -> payment(new BigDecimal(amount)))
        .isInstanceOf(TransactionRuleViolationException.class)
        .extracting(failure -> ((TransactionRuleViolationException) failure).violation())
        .isEqualTo(RuleViolation.INVALID_AMOUNT);
  }

  @Test
  void aMissingAmountIsRejected() {
    assertThatThrownBy(() -> payment(null)).isInstanceOf(TransactionRuleViolationException.class);
  }

  @Test
  void aDebitedAccountLeadsToThePaymentAndThePaymentCompletesTheTransaction() {
    Transaction debited =
        Transactions.pendingDebit(ID)
            .debited(new BigDecimal("1.50"), new BigDecimal("348.50"), LATER)
            .orElseThrow();

    assertThat(debited.getStatus()).isEqualTo(TransactionStatus.PENDING_PAYMENT);
    assertThat(debited.getFee()).isEqualTo(new BigDecimal("1.50"));
    assertThat(debited.getAccountBalanceAfter()).isEqualTo(new BigDecimal("348.50"));
    assertThat(debited.getVersion()).isEqualTo(1);
    assertThat(debited.getUpdatedAt()).isEqualTo(LATER);
    assertThat(debited.getCommandSentAt()).isEqualTo(LATER);
    assertThat(debited.nextCommands())
        .contains(
            new ParticipantCommand.PayCredit(
                ID,
                ID,
                Transactions.CREDIT,
                new BigDecimal("150.00"),
                CurrencyCode.PEN,
                "November installment"));

    Transaction paid = debited.paid(new BigDecimal("850.00"), LATER.plusSeconds(1)).orElseThrow();

    assertThat(paid.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    assertThat(paid.getDebtAfter()).isEqualTo(new BigDecimal("850.00"));
    assertThat(paid.getRejection()).isNull();
    assertThat(paid.getVersion()).isEqualTo(2);
    assertThat(paid.nextCommands()).isEmpty();
  }

  @Test
  void aRejectedDebitEndsTheTransactionWithoutMovingMoney() {
    Transaction rejected =
        Transactions.pendingDebit(ID)
            .debitRejected("INSUFFICIENT_FUNDS", "The balance does not cover it.", LATER)
            .orElseThrow();

    assertThat(rejected.getStatus()).isEqualTo(TransactionStatus.REJECTED);
    assertThat(rejected.getRejection())
        .isEqualTo(
            new Rejection(
                RejectedStep.DEBIT, "INSUFFICIENT_FUNDS", "The balance does not cover it."));
    assertThat(rejected.getFee()).isNull();
    assertThat(rejected.nextCommands()).isEmpty();
  }

  @Test
  void aRejectedPaymentAsksForTheDebitBackAndTheReversalEndsTheTransaction() {
    Transaction waiting = Transactions.pendingReversal(ID);

    assertThat(waiting.getStatus()).isEqualTo(TransactionStatus.PENDING_REVERSAL);
    assertThat(waiting.getRejection().step()).isEqualTo(RejectedStep.PAYMENT);
    assertThat(waiting.getRejection().code()).isEqualTo("PAYMENT_EXCEEDS_DEBT");
    assertThat(waiting.nextCommands())
        .contains(
            new ParticipantCommand.ReverseDebit(
                ID,
                Transactions.REVERSAL,
                ID,
                Transactions.ACCOUNT,
                Transaction.REVERSAL_DESCRIPTION));

    Transaction reversed = waiting.reversed(new BigDecimal("500.00"), LATER).orElseThrow();

    assertThat(reversed.getStatus()).isEqualTo(TransactionStatus.REVERSED);
    assertThat(reversed.getAccountBalanceAfter()).isEqualTo(new BigDecimal("500.00"));
    assertThat(reversed.getFee()).isEqualTo(new BigDecimal("1.50"));
    assertThat(reversed.getRejection().step()).isEqualTo(RejectedStep.PAYMENT);
    assertThat(reversed.nextCommands()).isEmpty();
  }

  @Test
  void aReversalThatCannotBeDoneIsLeftForTheBank() {
    Transaction failed =
        Transactions.pendingReversal(ID)
            .reversalRejected("ACCOUNT_CLOSED", "The account is closed.", LATER)
            .orElseThrow();

    assertThat(failed.getStatus()).isEqualTo(TransactionStatus.REVERSAL_FAILED);
    assertThat(failed.getReversalFailure())
        .isEqualTo(new ReversalFailure("ACCOUNT_CLOSED", "The account is closed."));
    assertThat(failed.getRejection().code()).isEqualTo("PAYMENT_EXCEEDS_DEBT");
    assertThat(failed.nextCommands()).isEmpty();
  }

  @Test
  void anAnswerTheTransactionIsNotWaitingForChangesNothing() {
    Transaction waitingForDebit = Transactions.pendingDebit(ID);
    Transaction waitingForPayment = Transactions.pendingPayment(ID);
    Transaction completed = waitingForPayment.paid(new BigDecimal("850.00"), LATER).orElseThrow();

    assertThat(waitingForDebit.paid(BigDecimal.TEN, LATER)).isEmpty();
    assertThat(waitingForDebit.paymentRejected("X", "x", LATER)).isEmpty();
    assertThat(waitingForDebit.reversed(BigDecimal.TEN, LATER)).isEmpty();
    assertThat(waitingForDebit.reversalRejected("X", "x", LATER)).isEmpty();
    assertThat(waitingForPayment.debited(BigDecimal.ONE, BigDecimal.TEN, LATER)).isEmpty();
    assertThat(waitingForPayment.debitRejected("X", "x", LATER)).isEmpty();
    assertThat(completed.paid(BigDecimal.TEN, LATER)).isEmpty();
    assertThat(completed.paymentRejected("X", "x", LATER)).isEmpty();
  }

  @Test
  void everyKindOfAnswerMovesTheStepItBelongsTo() {
    List<ParticipantReply> answers =
        List.of(
            new ParticipantReply.AccountDebited(ID, BigDecimal.ONE, BigDecimal.TEN),
            new ParticipantReply.AccountDebitRejected(ID, "X", "x"),
            new ParticipantReply.CreditPaid(ID, BigDecimal.TEN),
            new ParticipantReply.CreditPaymentRejected(ID, "X", "x"),
            new ParticipantReply.DebitReversed(ID, BigDecimal.TEN),
            new ParticipantReply.DebitReversalRejected(ID, "X", "x"));
    List<Transaction> waiting =
        List.of(
            Transactions.pendingDebit(ID),
            Transactions.pendingDebit(ID),
            Transactions.pendingPayment(ID),
            Transactions.pendingPayment(ID),
            Transactions.pendingReversal(ID),
            Transactions.pendingReversal(ID));
    List<TransactionStatus> expected =
        List.of(
            TransactionStatus.PENDING_PAYMENT,
            TransactionStatus.REJECTED,
            TransactionStatus.COMPLETED,
            TransactionStatus.PENDING_REVERSAL,
            TransactionStatus.REVERSED,
            TransactionStatus.REVERSAL_FAILED);

    for (int i = 0; i < answers.size(); i++) {
      Optional<Transaction> moved = answers.get(i).applyTo(waiting.get(i), LATER);

      assertThat(moved).map(Transaction::getStatus).contains(expected.get(i));
      assertThat(answers.get(i).transactionId()).isEqualTo(ID);
    }
  }

  @Test
  void aCommandSentAgainIsCountedAndANewStepStartsItsOwnCount() {
    Transaction resent = Transactions.pendingDebit(ID).commandResent(LATER).commandResent(LATER);

    assertThat(resent.getCommandAttempts()).isEqualTo(2);
    assertThat(resent.getCommandSentAt()).isEqualTo(LATER);
    assertThat(resent.getVersion()).isEqualTo(2);
    assertThat(resent.getUpdatedAt()).isEqualTo(Transactions.START);
    assertThat(resent.nextCommands()).isEqualTo(Transactions.pendingDebit(ID).nextCommands());

    Transaction debited =
        resent.debited(BigDecimal.ZERO, BigDecimal.TEN, LATER.plusSeconds(5)).orElseThrow();

    assertThat(debited.getCommandAttempts()).isZero();
  }

  @Test
  void twoRequestsAreTheSameOnlyWhenEveryFieldOfTheRequestMatches() {
    Transaction original = Transactions.pendingDebit(ID);

    assertThat(original.sameRequestAs(Transactions.pendingPayment(ID))).isTrue();
    assertThat(original.sameRequestAs(original.toBuilder().amount(BigDecimal.TEN).build()))
        .isFalse();
    assertThat(original.sameRequestAs(original.toBuilder().currency(CurrencyCode.USD).build()))
        .isFalse();
    assertThat(original.sameRequestAs(original.toBuilder().accountId(UUID.randomUUID()).build()))
        .isFalse();
    assertThat(original.sameRequestAs(original.toBuilder().creditId(UUID.randomUUID()).build()))
        .isFalse();
    assertThat(original.sameRequestAs(original.toBuilder().description(null).build())).isFalse();
  }

  @Test
  void aRejectionAlwaysSaysSomethingAndFitsWhatIsStored() {
    Rejection empty = new Rejection(RejectedStep.DEBIT, null, "  ");
    Rejection wordy = new Rejection(RejectedStep.PAYMENT, "C".repeat(80), "m".repeat(400));

    assertThat(empty.code()).isEqualTo("UNKNOWN");
    assertThat(empty.message()).isEqualTo("No reason was given.");
    assertThat(wordy.code()).hasSize(60);
    assertThat(wordy.message()).hasSize(300);
    assertThat(new ReversalFailure(null, null).code()).isEqualTo("UNKNOWN");
  }

  @Test
  void onlyThePendingStatusesWaitForAService() {
    assertThat(TransactionStatus.waiting())
        .containsExactly(
            TransactionStatus.PENDING_CARD,
            TransactionStatus.PENDING_DEBIT,
            TransactionStatus.PENDING_CREDIT,
            TransactionStatus.PENDING_PAYMENT,
            TransactionStatus.PENDING_REVERSAL)
        .noneMatch(TransactionStatus::isFinal);
    assertThat(TransactionStatus.values())
        .filteredOn(status -> !TransactionStatus.waiting().contains(status))
        .allMatch(TransactionStatus::isFinal);
  }

  private static Transaction payment(BigDecimal amount) {
    return Transaction.creditPayment(
        ID,
        Transactions.ACCOUNT,
        Transactions.CREDIT,
        amount,
        CurrencyCode.PEN,
        null,
        Transactions.REVERSAL,
        "corr-1",
        Transactions.START);
  }
}
