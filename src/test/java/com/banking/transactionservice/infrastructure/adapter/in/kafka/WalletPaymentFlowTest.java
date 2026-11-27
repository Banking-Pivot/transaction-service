package com.banking.transactionservice.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.RejectedStep;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionResponse;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionStatus;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionType;
import com.banking.transactionservice.support.IntegrationTest;
import com.banking.transactionservice.support.TestParticipants;
import com.banking.transactionservice.support.TestParticipants.ReceivedCommand;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The movement of the funds of a wallet payment from the request of Yanki Service to its answer,
 * over a real Kafka. The test asks the way Yanki Service would and answers the commands the way
 * Card Service and Account Service would.
 */
class WalletPaymentFlowTest extends IntegrationTest {

  @Test
  void aPaymentFromACardDebitsItsMainAccountAndYankiServiceIsTold() {
    UUID id = UUID.randomUUID();
    UUID card = UUID.randomUUID();
    UUID main = UUID.randomUUID();

    participants.moveFunds(id, card, null);

    ReceivedCommand question = participants.awaitCommand("RESOLVE_CARD", id);
    assertThat(question.topic()).isEqualTo(TestParticipants.CARD_COMMANDS);
    assertThat(question.key()).isEqualTo(card.toString());
    assertThat(question.correlationId()).isEqualTo("yanki-" + id);
    assertThat(question.field("replyTo")).isEqualTo(TestParticipants.REPLIES);
    assertThat(transaction(id).getStatus()).isEqualTo(TransactionStatus.PENDING_CARD);

    participants.cardResolved(question, main, UUID.randomUUID());

    ReceivedCommand debit = participants.awaitCommand("DEBIT_ACCOUNT", id);
    assertThat(debit.key()).isEqualTo(main.toString());
    assertThat(debit.field("accountId")).isEqualTo(main.toString());
    assertThat(debit.field("operationId")).isNotNull().isNotEqualTo(id.toString());
    assertThat(debit.body()).containsEntry("amount", new BigDecimal("60.00"));
    assertThat(debit.field("currency")).isEqualTo("PEN");
    assertThat(debit.field("description")).isEqualTo("Yanki payment");
    assertThat(debit.correlationId()).isEqualTo("yanki-" + id);

    participants.accountDebited(debit, "0.50", "39.50");

    ReceivedCommand moved = participants.awaitOutcome("FUNDS_MOVED", id);
    assertThat(moved.topic()).isEqualTo(TestParticipants.WALLET_REPLIES);
    assertThat(moved.key()).isEqualTo(id.toString());
    assertThat(moved.correlationId()).isEqualTo("yanki-" + id);
    assertThat(moved.field("sourceAccountId")).isEqualTo(main.toString());
    assertThat(moved.body())
        .containsEntry("amount", new BigDecimal("60.00"))
        .containsEntry("fee", new BigDecimal("0.50"));
    assertThat(Instant.parse(moved.field("occurredAt"))).isBeforeOrEqualTo(Instant.now());
    assertThat(moved.body())
        .containsOnlyKeys(
            "type", "transactionId", "sourceAccountId", "amount", "fee", "occurredAt");

    TransactionResponse completed = awaitStatus(id, TransactionStatus.COMPLETED);
    assertThat(completed.getType()).isEqualTo(TransactionType.WALLET_PAYMENT);
    assertThat(completed.getSourceCardId()).isEqualTo(card);
    assertThat(completed.getDestinationCardId()).isNull();
    assertThat(completed.getAccountId()).isEqualTo(main);
    assertThat(completed.getFee()).isEqualByComparingTo("0.50");
    assertThat(completed.getDescription()).isEqualTo("Yanki payment");
    assertThat(participants.countCommands("CREDIT_ACCOUNT", id, Duration.ofSeconds(1))).isZero();
  }

  @Test
  void movementsOfWalletFundsAreNotSubjectToTheLimitsOfCardPayments() {
    UUID card = UUID.randomUUID();
    UUID main = UUID.randomUUID();

    // 6000 is over the limit of one card payment, and twice 6000 over the limit of a day.
    for (int movement = 0; movement < 2; movement++) {
      UUID id = UUID.randomUUID();
      participants.moveFunds(id, card, null, "6000.00", "PEN");
      participants.cardResolved(participants.awaitCommand("RESOLVE_CARD", id), main);
      ReceivedCommand debit = participants.awaitCommand("DEBIT_ACCOUNT", id);
      assertThat(debit.body()).containsEntry("amount", new BigDecimal("6000.00"));
      participants.accountDebited(debit, "0.00", "100.00");
      assertThat(participants.awaitOutcome("FUNDS_MOVED", id).field("sourceAccountId"))
          .isEqualTo(main.toString());
    }

    // And what the wallet moved does not use up the day of the card either.
    Map<String, Object> payment = cardPayment(UUID.randomUUID(), card);
    payment.put("amount", new BigDecimal("5000.00"));
    requestCardPayment(payment).expectStatus().isAccepted();
  }

  @Test
  void aPaymentFromACardWithoutFundsIsRefusedAndNoOtherAccountOfTheCardIsTried() {
    UUID id = UUID.randomUUID();
    participants.moveFunds(id, UUID.randomUUID(), null);
    participants.cardResolved(
        participants.awaitCommand("RESOLVE_CARD", id), UUID.randomUUID(), UUID.randomUUID());

    participants.accountDebitRejected(
        participants.awaitCommand("DEBIT_ACCOUNT", id),
        "INSUFFICIENT_FUNDS",
        "The balance does not cover it.");

    ReceivedCommand refused = participants.awaitOutcome("FUNDS_MOVE_REJECTED", id);
    assertThat(refused.topic()).isEqualTo(TestParticipants.WALLET_REPLIES);
    assertThat(refused.key()).isEqualTo(id.toString());
    assertThat(refused.field("code")).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(refused.field("message")).isEqualTo("The balance does not cover it.");
    assertThat(refused.body()).containsOnlyKeys("type", "transactionId", "code", "message");
    TransactionResponse rejected = awaitStatus(id, TransactionStatus.REJECTED);
    assertThat(rejected.getRejection().getStep()).isEqualTo(RejectedStep.DEBIT);
    assertThat(participants.countCommands("DEBIT_ACCOUNT", id, Duration.ofSeconds(1))).isEqualTo(1);
  }

  @Test
  void aPaymentToACardCreditsItsMainAccountAndYankiServiceIsTold() {
    UUID id = UUID.randomUUID();
    UUID card = UUID.randomUUID();
    UUID main = UUID.randomUUID();

    participants.moveFunds(id, null, card);
    participants.cardResolved(participants.awaitCommand("RESOLVE_CARD", id), main);

    ReceivedCommand credit = participants.awaitCommand("CREDIT_ACCOUNT", id);
    assertThat(credit.topic()).isEqualTo(TestParticipants.ACCOUNT_COMMANDS);
    assertThat(credit.key()).isEqualTo(main.toString());
    assertThat(credit.field("accountId")).isEqualTo(main.toString());
    assertThat(credit.field("operationId")).isNotNull().isNotEqualTo(id.toString());
    assertThat(credit.body()).containsEntry("amount", new BigDecimal("60.00"));
    assertThat(credit.field("currency")).isEqualTo("PEN");
    assertThat(credit.field("description")).isEqualTo("Yanki payment");
    assertThat(credit.correlationId()).isEqualTo("yanki-" + id);
    assertThat(credit.body())
        .doesNotContainKeys("cardId", "creditId", "originalOperationId", "replyTo");

    participants.accountCredited(credit, "0.00", "160.00");

    ReceivedCommand moved = participants.awaitOutcome("FUNDS_MOVED", id);
    assertThat(moved.field("destinationAccountId")).isEqualTo(main.toString());
    assertThat(moved.body())
        .containsEntry("fee", new BigDecimal("0.00"))
        .doesNotContainKey("sourceAccountId");
    TransactionResponse completed = awaitStatus(id, TransactionStatus.COMPLETED);
    assertThat(completed.getDestinationCardId()).isEqualTo(card);
    assertThat(completed.getDestinationAccountId()).isEqualTo(main);
    assertThat(completed.getAccountId()).isNull();
    assertThat(completed.getDestinationFee()).isEqualByComparingTo("0.00");
    assertThat(participants.countCommands("DEBIT_ACCOUNT", id, Duration.ofSeconds(1))).isZero();
  }

  @Test
  void aPaymentToACardThatCannotBeCreditedIsRefusedAndThereIsNothingToReturn() {
    UUID id = UUID.randomUUID();
    participants.moveFunds(id, null, UUID.randomUUID());
    participants.cardResolved(participants.awaitCommand("RESOLVE_CARD", id), UUID.randomUUID());

    participants.accountCreditRejected(
        participants.awaitCommand("CREDIT_ACCOUNT", id),
        "ACCOUNT_NOT_ACTIVE",
        "The account is closed.");

    assertThat(participants.awaitOutcome("FUNDS_MOVE_REJECTED", id).field("code"))
        .isEqualTo("ACCOUNT_NOT_ACTIVE");
    TransactionResponse rejected = awaitStatus(id, TransactionStatus.REJECTED);
    assertThat(rejected.getRejection().getStep()).isEqualTo(RejectedStep.CREDIT);
    assertThat(participants.countCommands("REVERSE_DEBIT", id, Duration.ofSeconds(1))).isZero();
  }

  @Test
  void aPaymentBetweenTwoCardsDebitsOneMainAccountThenCreditsTheOther() {
    UUID id = UUID.randomUUID();
    UUID payer = UUID.randomUUID();
    UUID payee = UUID.randomUUID();
    UUID source = UUID.randomUUID();
    UUID destination = UUID.randomUUID();

    participants.moveFunds(id, payer, payee);

    ReceivedCommand aboutPayee = question(id, payee);
    ReceivedCommand aboutPayer = question(id, payer);
    assertThat(aboutPayee.key()).isEqualTo(payee.toString());
    assertThat(aboutPayer.key()).isEqualTo(payer.toString());

    // One card is known: nothing moves until the other is.
    participants.cardResolved(aboutPayee, destination);
    assertThat(participants.countCommands("DEBIT_ACCOUNT", id, Duration.ofSeconds(2))).isZero();
    assertThat(participants.countCommands("RESOLVE_CARD", id, Duration.ZERO)).isEqualTo(2);
    TransactionResponse waiting = transaction(id);
    assertThat(waiting.getStatus()).isEqualTo(TransactionStatus.PENDING_CARD);
    assertThat(waiting.getDestinationAccountId()).isEqualTo(destination);

    participants.cardResolved(aboutPayer, source, UUID.randomUUID());

    ReceivedCommand debit = participants.awaitCommand("DEBIT_ACCOUNT", id);
    assertThat(debit.field("accountId")).isEqualTo(source.toString());
    assertThat(participants.countCommands("CREDIT_ACCOUNT", id, Duration.ofSeconds(1))).isZero();
    participants.accountDebited(debit, "0.50", "39.50");

    ReceivedCommand credit = participants.awaitCommand("CREDIT_ACCOUNT", id);
    assertThat(credit.field("accountId")).isEqualTo(destination.toString());
    assertThat(credit.field("operationId")).isNotEqualTo(debit.field("operationId"));
    assertThat(awaitStatus(id, TransactionStatus.PENDING_CREDIT).getFee())
        .isEqualByComparingTo("0.50");
    participants.accountCredited(credit, "0.25", "160.00");

    ReceivedCommand moved = participants.awaitOutcome("FUNDS_MOVED", id);
    assertThat(moved.field("sourceAccountId")).isEqualTo(source.toString());
    assertThat(moved.field("destinationAccountId")).isEqualTo(destination.toString());
    assertThat(moved.body())
        .containsEntry("amount", new BigDecimal("60.00"))
        // What both accounts paid.
        .containsEntry("fee", new BigDecimal("0.75"));
    TransactionResponse completed = awaitStatus(id, TransactionStatus.COMPLETED);
    assertThat(completed.getFee()).isEqualByComparingTo("0.50");
    assertThat(completed.getDestinationFee()).isEqualByComparingTo("0.25");
    assertThat(column(id, "version")).isEqualTo(4);
    assertThat(participants.countCommands("FUNDS_MOVED", id, Duration.ofSeconds(1))).isEqualTo(1);
  }

  @Test
  void aCreditThatIsRefusedReturnsTheDebitBeforeYankiServiceIsTold() {
    UUID id = UUID.randomUUID();
    UUID source = UUID.randomUUID();
    ReceivedCommand debit = debitedBetweenTwoCards(id, source);

    participants.accountCreditRejected(
        participants.awaitCommand("CREDIT_ACCOUNT", id),
        "ACCOUNT_NOT_ACTIVE",
        "The account is closed.");

    ReceivedCommand reversal = participants.awaitCommand("REVERSE_DEBIT", id);
    assertThat(reversal.topic()).isEqualTo(TestParticipants.ACCOUNT_COMMANDS);
    assertThat(reversal.key()).isEqualTo(source.toString());
    assertThat(reversal.field("accountId")).isEqualTo(source.toString());
    assertThat(reversal.field("originalOperationId")).isEqualTo(debit.field("operationId"));
    assertThat(reversal.field("operationId"))
        .isNotEqualTo(debit.field("operationId"))
        .isNotEqualTo(id.toString());
    assertThat(reversal.field("description")).isEqualTo("Wallet payment not applied");
    TransactionResponse waiting = awaitStatus(id, TransactionStatus.PENDING_REVERSAL);
    assertThat(waiting.getRejection().getStep()).isEqualTo(RejectedStep.CREDIT);
    // The money is not back yet: Yanki Service keeps waiting.
    assertThat(participants.countCommands("FUNDS_MOVE_REJECTED", id, Duration.ofSeconds(1)))
        .isZero();

    participants.debitReversed(id, "100.00");

    ReceivedCommand refused = participants.awaitOutcome("FUNDS_MOVE_REJECTED", id);
    assertThat(refused.field("code")).isEqualTo("ACCOUNT_NOT_ACTIVE");
    assertThat(refused.field("message")).isEqualTo("The account is closed.");
    TransactionResponse reversed = awaitStatus(id, TransactionStatus.REVERSED);
    assertThat(reversed.getAccountBalanceAfter()).isEqualByComparingTo("100.00");
    assertThat(reversed.getDestinationFee()).isNull();
    assertThat(participants.countCommands("FUNDS_MOVED", id, Duration.ofSeconds(1))).isZero();
  }

  @Test
  void aDebitThatCannotBeReturnedIsToldToYankiServiceAsSuch() {
    UUID id = UUID.randomUUID();
    debitedBetweenTwoCards(id, UUID.randomUUID());
    participants.accountCreditRejected(
        participants.awaitCommand("CREDIT_ACCOUNT", id),
        "ACCOUNT_NOT_ACTIVE",
        "The account is closed.");
    participants.awaitCommand("REVERSE_DEBIT", id);

    participants.debitReversalRejected(id, "ACCOUNT_NOT_FOUND", "The account does not exist.");

    ReceivedCommand refused = participants.awaitOutcome("FUNDS_MOVE_REJECTED", id);
    assertThat(refused.field("code")).isEqualTo("REVERSAL_FAILED");
    assertThat(refused.field("message")).isNotBlank();
    TransactionResponse failed = awaitStatus(id, TransactionStatus.REVERSAL_FAILED);
    assertThat(failed.getRejection().getCode()).isEqualTo("ACCOUNT_NOT_ACTIVE");
    assertThat(failed.getReversalFailure().getCode()).isEqualTo("ACCOUNT_NOT_FOUND");
  }

  @Test
  void aCardThatCardServiceRefusesEndsTheMovementBeforeAnyMoneyMoves() {
    UUID id = UUID.randomUUID();
    UUID payer = UUID.randomUUID();
    UUID payee = UUID.randomUUID();
    participants.moveFunds(id, payer, payee);
    participants.cardResolved(question(id, payer), UUID.randomUUID());

    participants.cardResolutionRejected(
        question(id, payee), "CARD_NOT_FOUND", "The card does not exist.");

    ReceivedCommand refused = participants.awaitOutcome("FUNDS_MOVE_REJECTED", id);
    assertThat(refused.field("code")).isEqualTo("CARD_NOT_FOUND");
    assertThat(refused.field("message")).isEqualTo("The card does not exist.");
    TransactionResponse rejected = awaitStatus(id, TransactionStatus.REJECTED);
    assertThat(rejected.getRejection().getStep()).isEqualTo(RejectedStep.CARD);
    assertThat(participants.countCommands("DEBIT_ACCOUNT", id, Duration.ofSeconds(1))).isZero();
  }

  @Test
  void aRepeatedRequestStartsNothingNewAndIsAnsweredOnlyOnceTheMovementEnded() {
    UUID id = UUID.randomUUID();
    UUID card = UUID.randomUUID();

    participants.moveFunds(id, card, null);
    participants.moveFunds(id, card, null);

    // In progress: the repeat neither asks Card Service again nor gets an answer.
    ReceivedCommand question = participants.awaitCommand("RESOLVE_CARD", id);
    assertThat(participants.countCommands("RESOLVE_CARD", id, Duration.ofSeconds(2))).isEqualTo(1);
    assertThat(participants.countCommands("FUNDS_MOVED", id, Duration.ZERO)).isZero();
    assertThat(participants.countCommands("FUNDS_MOVE_REJECTED", id, Duration.ZERO)).isZero();

    participants.cardResolved(question, UUID.randomUUID());
    participants.accountDebited(participants.awaitCommand("DEBIT_ACCOUNT", id), "0.50", "39.50");
    ReceivedCommand moved = participants.awaitOutcome("FUNDS_MOVED", id);
    long version = column(id, "version");

    // Ended: the repeat gets the same answer again, and no money moves.
    participants.moveFunds(id, card, null);

    ReceivedCommand again = participants.awaitOutcome("FUNDS_MOVED", id, 2);
    assertThat(again.text()).isEqualTo(moved.text());
    assertThat(again.key()).isEqualTo(moved.key());
    assertThat(column(id, "version")).isEqualTo(version);
    assertThat(participants.countCommands("DEBIT_ACCOUNT", id, Duration.ofSeconds(1))).isEqualTo(1);
    assertThat(participants.countCommands("RESOLVE_CARD", id, Duration.ZERO)).isEqualTo(1);
  }

  @Test
  void theSameIdWithOtherDataIsAnsweredAsAConflictAndTheFirstMovementGoesOn() {
    UUID id = UUID.randomUUID();
    UUID card = UUID.randomUUID();
    participants.moveFunds(id, card, null);
    ReceivedCommand question = participants.awaitCommand("RESOLVE_CARD", id);

    participants.moveFunds(id, card, null, "61.00", "PEN");

    ReceivedCommand conflict = participants.awaitOutcome("FUNDS_MOVE_REJECTED", id);
    assertThat(conflict.field("code")).isEqualTo("OPERATION_ID_CONFLICT");
    assertThat(conflict.key()).isEqualTo(id.toString());
    assertThat(transaction(id).getStatus()).isEqualTo(TransactionStatus.PENDING_CARD);
    assertThat(transaction(id).getAmount()).isEqualByComparingTo("60.00");

    participants.cardResolved(question, UUID.randomUUID());
    participants.accountDebited(participants.awaitCommand("DEBIT_ACCOUNT", id), "0.00", "40.00");
    assertThat(participants.awaitOutcome("FUNDS_MOVED", id).body())
        .containsEntry("amount", new BigDecimal("60.00"));
  }

  @Test
  void aRequestThatCannotBeAcceptedIsAnsweredAndNothingIsStored() {
    UUID noCard = UUID.randomUUID();
    UUID euros = UUID.randomUUID();
    UUID nothing = UUID.randomUUID();

    participants.moveFunds(noCard, null, null);
    participants.moveFunds(euros, UUID.randomUUID(), null, "60.00", "EUR");
    participants.moveFunds(nothing, UUID.randomUUID(), null, "0.00", "PEN");

    for (UUID id : new UUID[] {noCard, euros, nothing}) {
      ReceivedCommand refused = participants.awaitOutcome("FUNDS_MOVE_REJECTED", id);
      assertThat(refused.field("code")).isEqualTo("INVALID_REQUEST");
      assertThat(refused.field("message")).isNotBlank();
      assertThat(refused.correlationId()).isEqualTo("yanki-" + id);
      webTestClient
          .get()
          .uri("/api/v1/transactions/{id}", id)
          .exchange()
          .expectStatus()
          .isNotFound();
    }
    assertThat(participants.countCommands("RESOLVE_CARD", euros, Duration.ofSeconds(1))).isZero();
  }

  @Test
  void requestsThatCannotBeReadAreSkippedAndTheNextOneIsHandled() {
    UUID id = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    String key = id.toString();

    participants.sendTo(TestParticipants.TRANSACTION_COMMANDS, key, "this is not JSON", null);
    participants.sendTo(TestParticipants.TRANSACTION_COMMANDS, key, null, null);
    participants.sendTo(TestParticipants.TRANSACTION_COMMANDS, key, "[]", null);
    participants.sendTo(
        TestParticipants.TRANSACTION_COMMANDS,
        key,
        "{\"type\":\"MOVE_FUNDS\",\"amount\":60.00,\"currency\":\"PEN\"}",
        null);
    participants.sendTo(
        TestParticipants.TRANSACTION_COMMANDS,
        key,
        "{\"type\":\"MOVE_FUNDS\",\"transactionId\":\"not-a-uuid\"}",
        null);
    participants.sendTo(
        TestParticipants.TRANSACTION_COMMANDS,
        key,
        "{\"type\":\"MOVE_EVERYTHING\",\"transactionId\":\""
            + stranger
            + "\",\"sourceCardId\":\""
            + UUID.randomUUID()
            + "\",\"amount\":60.00,\"currency\":\"PEN\"}",
        null);

    // The listener is still alive and reads what comes after.
    participants.moveFunds(id, UUID.randomUUID(), null);

    ReceivedCommand question = participants.awaitCommand("RESOLVE_CARD", id);
    // A request without a usable correlation identifier gets one; this one brought its own.
    assertThat(question.correlationId()).isEqualTo("yanki-" + id);
    webTestClient
        .get()
        .uri("/api/v1/transactions/{id}", stranger)
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThat(participants.countCommands("FUNDS_MOVE_REJECTED", stranger, Duration.ofSeconds(1)))
        .isZero();
  }

  // The question about one card of a movement between two; both are asked at once.
  private ReceivedCommand question(UUID transactionId, UUID cardId) {
    ReceivedCommand first = participants.awaitCommand("RESOLVE_CARD", transactionId, 1);
    ReceivedCommand second = participants.awaitCommand("RESOLVE_CARD", transactionId, 2);
    return cardId.toString().equals(first.field("cardId")) ? first : second;
  }

  // A movement between two cards whose debit was done; returns the command of that debit.
  private ReceivedCommand debitedBetweenTwoCards(UUID transactionId, UUID sourceAccountId) {
    UUID payer = UUID.randomUUID();
    UUID payee = UUID.randomUUID();
    participants.moveFunds(transactionId, payer, payee);
    participants.cardResolved(question(transactionId, payer), sourceAccountId);
    participants.cardResolved(question(transactionId, payee), UUID.randomUUID());
    ReceivedCommand debit = participants.awaitCommand("DEBIT_ACCOUNT", transactionId);
    participants.accountDebited(debit, "0.50", "39.50");
    return debit;
  }
}
