package com.banking.transactionservice.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.transactionservice.domain.exception.RuleViolation;
import com.banking.transactionservice.domain.exception.TransactionRuleViolationException;
import com.banking.transactionservice.domain.model.enums.CurrencyCode;
import com.banking.transactionservice.domain.model.enums.RejectedStep;
import com.banking.transactionservice.domain.model.enums.TransactionStatus;
import com.banking.transactionservice.domain.model.enums.TransactionType;
import com.banking.transactionservice.domain.model.vo.FundsMovementOutcome;
import com.banking.transactionservice.domain.model.vo.ParticipantCommand;
import com.banking.transactionservice.domain.model.vo.ParticipantReply;
import com.banking.transactionservice.domain.model.vo.Rejection;
import com.banking.transactionservice.support.Transactions;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The steps of a payment with a debit card and of a movement of wallet funds. */
class CardAndWalletTransactionTest {

  private static final UUID ID = UUID.fromString("7b9d1f3a-5c7e-4a2b-8d4f-6a8c0e2b4d6f");
  private static final Instant LATER = Transactions.START.plusSeconds(10);
  private static final BigDecimal FEE = new BigDecimal("0.50");
  private static final BigDecimal BALANCE = new BigDecimal("39.50");

  // ----- card payment

  @Test
  void aCardPaymentStartsByAskingWhichAccountsTheCardUses() {
    Transaction payment = Transactions.cardPayment(ID);

    assertThat(payment.getType()).isEqualTo(TransactionType.CARD_PAYMENT);
    assertThat(payment.getStatus()).isEqualTo(TransactionStatus.PENDING_CARD);
    assertThat(payment.getSourceCardId()).isEqualTo(Transactions.CARD);
    assertThat(payment.getAccountId()).isNull();
    assertThat(payment.getCandidateAccountIds()).isEmpty();
    assertThat(payment.nextCommands())
        .containsExactly(new ParticipantCommand.ResolveCard(ID, Transactions.CARD));
    assertThat(payment.outcome()).isEmpty();
  }

  @Test
  void aResolvedCardLeadsToTheDebitOfItsMainAccountUnderAKeyOfItsOwn() {
    Transaction waiting = Transactions.cardPaymentPendingDebit(ID);

    assertThat(waiting.getStatus()).isEqualTo(TransactionStatus.PENDING_DEBIT);
    assertThat(waiting.getAccountId()).isEqualTo(Transactions.ACCOUNT);
    assertThat(waiting.getCandidateAccountIds())
        .containsExactly(
            Transactions.ACCOUNT, Transactions.SECOND_ACCOUNT, Transactions.THIRD_ACCOUNT);
    assertThat(waiting.getVersion()).isEqualTo(1);
    assertThat(waiting.nextCommands())
        .containsExactly(
            new ParticipantCommand.DebitAccount(
                ID,
                derived("debit", Transactions.ACCOUNT),
                Transactions.ACCOUNT,
                new BigDecimal("80.00"),
                CurrencyCode.PEN,
                "Grocery store"));
    assertThat(derived("debit", Transactions.ACCOUNT)).isNotEqualTo(ID);
  }

  @Test
  void theMainAccountIsTriedFirstWhereverTheAnswerListsIt() {
    Transaction listedLast =
        Transactions.cardPayment(ID)
            .cardResolved(
                Transactions.CARD,
                Transactions.ACCOUNT,
                List.of(Transactions.SECOND_ACCOUNT, Transactions.ACCOUNT),
                LATER)
            .orElseThrow();
    Transaction notListed =
        Transactions.cardPayment(ID)
            .cardResolved(Transactions.CARD, Transactions.ACCOUNT, null, LATER)
            .orElseThrow();

    assertThat(listedLast.getCandidateAccountIds())
        .containsExactly(Transactions.ACCOUNT, Transactions.SECOND_ACCOUNT);
    assertThat(notListed.getCandidateAccountIds()).containsExactly(Transactions.ACCOUNT);
  }

  @Test
  void anAnswerAboutAnotherCardOrOutOfTurnChangesNothing() {
    Transaction payment = Transactions.cardPayment(ID);
    Transaction resolved = Transactions.cardPaymentPendingDebit(ID);

    assertThat(
            payment.cardResolved(
                Transactions.OTHER_CARD,
                Transactions.ACCOUNT,
                List.of(Transactions.ACCOUNT),
                LATER))
        .isEmpty();
    assertThat(payment.cardRejected(Transactions.OTHER_CARD, "CARD_NOT_FOUND", "No card.", LATER))
        .isEmpty();
    assertThat(
            resolved.cardResolved(
                Transactions.CARD,
                Transactions.SECOND_ACCOUNT,
                List.of(Transactions.SECOND_ACCOUNT),
                LATER))
        .isEmpty();
    assertThat(resolved.cardRejected(Transactions.CARD, "CARD_NOT_ACTIVE", "Blocked.", LATER))
        .isEmpty();
    assertThat(payment.debited(FEE, BALANCE, LATER)).isEmpty();
    assertThat(payment.credited(FEE, LATER)).isEmpty();
    assertThat(payment.creditRejected("ACCOUNT_CLOSED", "Closed.", LATER)).isEmpty();
  }

  @Test
  void aCardThatIsRefusedEndsThePaymentBeforeAnyMoneyMoves() {
    Transaction rejected =
        Transactions.cardPayment(ID)
            .cardRejected(Transactions.CARD, "CARD_NOT_ACTIVE", "The card is blocked.", LATER)
            .orElseThrow();

    assertThat(rejected.getStatus()).isEqualTo(TransactionStatus.REJECTED);
    assertThat(rejected.getRejection())
        .isEqualTo(new Rejection(RejectedStep.CARD, "CARD_NOT_ACTIVE", "The card is blocked."));
    assertThat(rejected.nextCommands()).isEmpty();
    assertThat(rejected.outcome()).isEmpty();
  }

  @Test
  void aDebitedCardPaymentIsComplete() {
    Transaction completed =
        Transactions.cardPaymentPendingDebit(ID).debited(FEE, BALANCE, LATER).orElseThrow();

    assertThat(completed.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    assertThat(completed.getFee()).isEqualByComparingTo(FEE);
    assertThat(completed.getAccountBalanceAfter()).isEqualByComparingTo(BALANCE);
    assertThat(completed.nextCommands()).isEmpty();
    assertThat(completed.outcome()).isEmpty();
  }

  @Test
  void anAccountWithoutFundsMakesThePaymentTryTheNextOneUntilNoneIsLeft() {
    Transaction first = Transactions.cardPaymentPendingDebit(ID);

    Transaction second =
        first.debitRejected("INSUFFICIENT_FUNDS", "No funds.", LATER).orElseThrow();
    assertThat(second.getStatus()).isEqualTo(TransactionStatus.PENDING_DEBIT);
    assertThat(second.getAccountId()).isEqualTo(Transactions.SECOND_ACCOUNT);
    assertThat(second.getRejection()).isNull();
    assertThat(second.getVersion()).isEqualTo(first.getVersion() + 1);
    assertThat(second.getCommandSentAt()).isEqualTo(LATER);
    assertThat(second.advancedFrom(first)).isTrue();
    assertThat(second.nextCommands())
        .containsExactly(
            new ParticipantCommand.DebitAccount(
                ID,
                derived("debit", Transactions.SECOND_ACCOUNT),
                Transactions.SECOND_ACCOUNT,
                new BigDecimal("80.00"),
                CurrencyCode.PEN,
                "Grocery store"));

    Transaction third =
        second.debitRejected("INSUFFICIENT_FUNDS", "No funds.", LATER).orElseThrow();
    assertThat(third.getAccountId()).isEqualTo(Transactions.THIRD_ACCOUNT);

    Transaction rejected =
        third.debitRejected("INSUFFICIENT_FUNDS", "No funds.", LATER).orElseThrow();
    assertThat(rejected.getStatus()).isEqualTo(TransactionStatus.REJECTED);
    assertThat(rejected.getAccountId()).isEqualTo(Transactions.THIRD_ACCOUNT);
    assertThat(rejected.getRejection())
        .isEqualTo(new Rejection(RejectedStep.DEBIT, "INSUFFICIENT_FUNDS", "No funds."));
  }

  @Test
  void anyOtherRefusalOfTheDebitEndsThePaymentAtOnce() {
    Transaction rejected =
        Transactions.cardPaymentPendingDebit(ID)
            .debitRejected("ACCOUNT_NOT_ACTIVE", "The account is closed.", LATER)
            .orElseThrow();

    assertThat(rejected.getStatus()).isEqualTo(TransactionStatus.REJECTED);
    assertThat(rejected.getAccountId()).isEqualTo(Transactions.ACCOUNT);
    assertThat(rejected.getRejection().code()).isEqualTo("ACCOUNT_NOT_ACTIVE");
  }

  @Test
  void aLateAnswerAboutAnEarlierAccountDoesNotMoveThePayment() {
    Transaction second =
        Transactions.cardPaymentPendingDebit(ID)
            .debitRejected("INSUFFICIENT_FUNDS", "No funds.", LATER)
            .orElseThrow();
    UUID earlier = derived("debit", Transactions.ACCOUNT);
    UUID current = derived("debit", Transactions.SECOND_ACCOUNT);

    assertThat(second.waitsForDebit(earlier)).isFalse();
    assertThat(second.waitsForDebit(current)).isTrue();
    assertThat(second.waitsForDebit(null)).isTrue();
    assertThat(
            new ParticipantReply.AccountDebitRejected(
                    ID, earlier, "INSUFFICIENT_FUNDS", "No funds.")
                .applyTo(second, LATER))
        .isEmpty();
    assertThat(
            new ParticipantReply.AccountDebited(ID, earlier, FEE, BALANCE).applyTo(second, LATER))
        .isEmpty();
    assertThat(
            new ParticipantReply.AccountDebited(ID, current, FEE, BALANCE)
                .applyTo(second, LATER)
                .orElseThrow()
                .getStatus())
        .isEqualTo(TransactionStatus.COMPLETED);
    assertThat(Transactions.cardPayment(ID).waitsForDebit(null)).isFalse();
  }

  // ----- wallet payment

  @Test
  void aWalletPaymentNeedsACard() {
    assertThatThrownBy(() -> Transactions.walletPayment(ID, null, null))
        .isInstanceOfSatisfying(
            TransactionRuleViolationException.class,
            violation -> assertThat(violation.violation()).isEqualTo(RuleViolation.CARD_REQUIRED));
  }

  @Test
  void aWalletPaymentFromACardDebitsItsMainAccountAndIsDone() {
    Transaction requested = Transactions.walletPayment(ID, Transactions.CARD, null);
    assertThat(requested.getType()).isEqualTo(TransactionType.WALLET_PAYMENT);
    assertThat(requested.nextCommands())
        .containsExactly(new ParticipantCommand.ResolveCard(ID, Transactions.CARD));

    Transaction waiting =
        requested
            .cardResolved(
                Transactions.CARD,
                Transactions.ACCOUNT,
                List.of(Transactions.ACCOUNT, Transactions.SECOND_ACCOUNT),
                LATER)
            .orElseThrow();
    assertThat(waiting.getStatus()).isEqualTo(TransactionStatus.PENDING_DEBIT);
    // Only the main account counts for a wallet.
    assertThat(waiting.getCandidateAccountIds()).isEmpty();
    assertThat(waiting.outcome()).isEmpty();

    Transaction completed = waiting.debited(FEE, BALANCE, LATER.plusSeconds(1)).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    assertThat(completed.outcome())
        .contains(
            new FundsMovementOutcome.Moved(
                ID,
                Transactions.ACCOUNT,
                null,
                new BigDecimal("60.00"),
                FEE,
                LATER.plusSeconds(1)));
  }

  @Test
  void aWalletPaymentFromACardWithoutFundsIsRejectedWithoutTryingOtherAccounts() {
    Transaction rejected =
        Transactions.walletPayment(ID, Transactions.CARD, null)
            .cardResolved(
                Transactions.CARD,
                Transactions.ACCOUNT,
                List.of(Transactions.ACCOUNT, Transactions.SECOND_ACCOUNT),
                LATER)
            .flatMap(next -> next.debitRejected("INSUFFICIENT_FUNDS", "No funds.", LATER))
            .orElseThrow();

    assertThat(rejected.getStatus()).isEqualTo(TransactionStatus.REJECTED);
    assertThat(rejected.outcome())
        .contains(new FundsMovementOutcome.Rejected(ID, "INSUFFICIENT_FUNDS", "No funds."));
  }

  @Test
  void aWalletPaymentToACardCreditsItsMainAccountAndIsDone() {
    Transaction waiting =
        Transactions.walletPayment(ID, null, Transactions.OTHER_CARD)
            .cardResolved(
                Transactions.OTHER_CARD,
                Transactions.DESTINATION,
                List.of(Transactions.DESTINATION),
                LATER)
            .orElseThrow();

    assertThat(waiting.getStatus()).isEqualTo(TransactionStatus.PENDING_CREDIT);
    assertThat(waiting.getAccountId()).isNull();
    assertThat(waiting.getDestinationAccountId()).isEqualTo(Transactions.DESTINATION);
    assertThat(waiting.nextCommands())
        .containsExactly(
            new ParticipantCommand.CreditAccount(
                ID,
                derived("credit", Transactions.DESTINATION),
                Transactions.DESTINATION,
                new BigDecimal("60.00"),
                CurrencyCode.PEN,
                "Yanki payment"));

    Transaction completed = waiting.credited(null, LATER).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    assertThat(completed.outcome())
        .contains(
            new FundsMovementOutcome.Moved(
                ID,
                null,
                Transactions.DESTINATION,
                new BigDecimal("60.00"),
                new BigDecimal("0.00"),
                LATER));

    Transaction rejected =
        waiting.creditRejected("ACCOUNT_NOT_ACTIVE", "The account is closed.", LATER).orElseThrow();
    assertThat(rejected.getStatus()).isEqualTo(TransactionStatus.REJECTED);
    assertThat(rejected.getRejection().step()).isEqualTo(RejectedStep.CREDIT);
    assertThat(rejected.nextCommands()).isEmpty();
    assertThat(rejected.outcome())
        .contains(
            new FundsMovementOutcome.Rejected(ID, "ACCOUNT_NOT_ACTIVE", "The account is closed."));
  }

  @Test
  void aWalletPaymentBetweenTwoCardsWaitsForBothBeforeItMovesMoney() {
    Transaction requested =
        Transactions.walletPayment(ID, Transactions.CARD, Transactions.OTHER_CARD);
    assertThat(requested.nextCommands())
        .containsExactly(
            new ParticipantCommand.ResolveCard(ID, Transactions.CARD),
            new ParticipantCommand.ResolveCard(ID, Transactions.OTHER_CARD));

    Transaction oneKnown =
        requested
            .cardResolved(
                Transactions.OTHER_CARD,
                Transactions.DESTINATION,
                List.of(Transactions.DESTINATION),
                LATER)
            .orElseThrow();
    assertThat(oneKnown.getStatus()).isEqualTo(TransactionStatus.PENDING_CARD);
    assertThat(oneKnown.advancedFrom(requested)).isFalse();
    assertThat(oneKnown.getVersion()).isEqualTo(1);
    // The command about the other card is the one still waiting, since when it was sent.
    assertThat(oneKnown.getCommandSentAt()).isEqualTo(Transactions.START);
    assertThat(oneKnown.nextCommands())
        .containsExactly(new ParticipantCommand.ResolveCard(ID, Transactions.CARD));
    assertThat(
            oneKnown.cardResolved(
                Transactions.OTHER_CARD,
                Transactions.DESTINATION,
                List.of(Transactions.DESTINATION),
                LATER))
        .isEmpty();

    Transaction bothKnown =
        oneKnown
            .cardResolved(
                Transactions.CARD, Transactions.ACCOUNT, List.of(Transactions.ACCOUNT), LATER)
            .orElseThrow();
    assertThat(bothKnown.getStatus()).isEqualTo(TransactionStatus.PENDING_DEBIT);
    assertThat(bothKnown.advancedFrom(oneKnown)).isTrue();
    assertThat(bothKnown.getCommandSentAt()).isEqualTo(LATER);

    Transaction debited = bothKnown.debited(FEE, BALANCE, LATER).orElseThrow();
    assertThat(debited.getStatus()).isEqualTo(TransactionStatus.PENDING_CREDIT);

    Transaction completed =
        debited.credited(new BigDecimal("0.25"), LATER.plusSeconds(2)).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    assertThat(completed.getDestinationFee()).isEqualByComparingTo("0.25");
    assertThat(completed.outcome())
        .contains(
            new FundsMovementOutcome.Moved(
                ID,
                Transactions.ACCOUNT,
                Transactions.DESTINATION,
                new BigDecimal("60.00"),
                new BigDecimal("0.75"),
                LATER.plusSeconds(2)));
  }

  @Test
  void aCardRefusedInAWalletPaymentEndsItEvenWhenTheOtherCardIsKnown() {
    Transaction rejected =
        Transactions.walletPayment(ID, Transactions.CARD, Transactions.OTHER_CARD)
            .cardResolved(
                Transactions.CARD, Transactions.ACCOUNT, List.of(Transactions.ACCOUNT), LATER)
            .flatMap(
                next ->
                    next.cardRejected(
                        Transactions.OTHER_CARD,
                        "CARD_NOT_FOUND",
                        "The card does not exist.",
                        LATER))
            .orElseThrow();

    assertThat(rejected.getStatus()).isEqualTo(TransactionStatus.REJECTED);
    assertThat(rejected.outcome())
        .contains(
            new FundsMovementOutcome.Rejected(ID, "CARD_NOT_FOUND", "The card does not exist."));
  }

  @Test
  void aCreditThatIsRefusedAfterTheDebitSendsTheMoneyBack() {
    Transaction waiting =
        Transactions.walletPendingCredit(ID)
            .creditRejected("ACCOUNT_NOT_ACTIVE", "The account is closed.", LATER)
            .orElseThrow();

    assertThat(waiting.getStatus()).isEqualTo(TransactionStatus.PENDING_REVERSAL);
    assertThat(waiting.getRejection().step()).isEqualTo(RejectedStep.CREDIT);
    assertThat(waiting.outcome()).isEmpty();
    assertThat(waiting.nextCommands())
        .containsExactly(
            new ParticipantCommand.ReverseDebit(
                ID,
                Transactions.REVERSAL,
                derived("debit", Transactions.ACCOUNT),
                Transactions.ACCOUNT,
                Transaction.WALLET_REVERSAL_DESCRIPTION));

    Transaction reversed = waiting.reversed(new BigDecimal("100.00"), LATER).orElseThrow();
    assertThat(reversed.getStatus()).isEqualTo(TransactionStatus.REVERSED);
    assertThat(reversed.outcome())
        .contains(
            new FundsMovementOutcome.Rejected(ID, "ACCOUNT_NOT_ACTIVE", "The account is closed."));

    Transaction failed =
        waiting.reversalRejected("ACCOUNT_NOT_FOUND", "The account is gone.", LATER).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(TransactionStatus.REVERSAL_FAILED);
    assertThat(failed.outcome().orElseThrow())
        .isInstanceOfSatisfying(
            FundsMovementOutcome.Rejected.class,
            outcome ->
                assertThat(outcome.code())
                    .isEqualTo(FundsMovementOutcome.Rejected.REVERSAL_FAILED));
  }

  @Test
  void theSameCardOnBothSidesIsAskedAboutOnce() {
    Transaction requested = Transactions.walletPayment(ID, Transactions.CARD, Transactions.CARD);
    assertThat(requested.nextCommands())
        .containsExactly(new ParticipantCommand.ResolveCard(ID, Transactions.CARD));

    Transaction resolved =
        requested
            .cardResolved(
                Transactions.CARD, Transactions.ACCOUNT, List.of(Transactions.ACCOUNT), LATER)
            .orElseThrow();
    assertThat(resolved.getStatus()).isEqualTo(TransactionStatus.PENDING_DEBIT);
    assertThat(resolved.getDestinationAccountId()).isEqualTo(Transactions.ACCOUNT);
  }

  // ----- what is asked

  @Test
  void aRequestIsTheSameOnlyWhenItsKindAndItsCardsAre() {
    Transaction card = Transactions.cardPayment(ID);
    Transaction resolved = Transactions.cardPaymentPendingDebit(ID);
    Transaction wallet = Transactions.walletPayment(ID, Transactions.CARD, null);

    // The account a card payment found out is not part of what was asked.
    assertThat(resolved.sameRequestAs(card)).isTrue();
    assertThat(card.sameRequestAs(wallet)).isFalse();
    assertThat(card.sameRequestAs(card.toBuilder().sourceCardId(Transactions.OTHER_CARD).build()))
        .isFalse();
    assertThat(wallet.sameRequestAs(Transactions.walletPayment(ID, Transactions.CARD, null)))
        .isTrue();
    assertThat(
            wallet.sameRequestAs(
                Transactions.walletPayment(ID, Transactions.CARD, Transactions.OTHER_CARD)))
        .isFalse();
    assertThat(Transactions.pendingDebit(ID).sameRequestAs(card)).isFalse();
  }

  @Test
  void aDescriptionLongerThanTheMovementsKeepIsCut() {
    Transaction wallet =
        Transaction.walletPayment(
            ID,
            Transactions.CARD,
            null,
            BigDecimal.TEN,
            CurrencyCode.USD,
            "x".repeat(200),
            Transactions.REVERSAL,
            "corr-1",
            Transactions.START);

    assertThat(wallet.getDescription()).hasSize(Transaction.MAX_DESCRIPTION);
  }

  @Test
  void aCurrencyIsReadFromItsCodeOrRefused() {
    assertThat(CurrencyCode.of("USD")).isEqualTo(CurrencyCode.USD);
    assertThatThrownBy(() -> CurrencyCode.of("EUR"))
        .isInstanceOfSatisfying(
            TransactionRuleViolationException.class,
            violation ->
                assertThat(violation.violation()).isEqualTo(RuleViolation.INVALID_CURRENCY));
    assertThatThrownBy(() -> CurrencyCode.of(null))
        .isInstanceOf(TransactionRuleViolationException.class);
  }

  private static UUID derived(String step, UUID account) {
    return UUID.nameUUIDFromBytes(
        (ID + ":" + step + ":" + account).getBytes(StandardCharsets.UTF_8));
  }
}
