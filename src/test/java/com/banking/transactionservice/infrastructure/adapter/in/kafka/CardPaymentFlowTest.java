package com.banking.transactionservice.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.RejectedStep;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionResponse;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionStatus;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionType;
import com.banking.transactionservice.infrastructure.correlation.CorrelationId;
import com.banking.transactionservice.support.IntegrationTest;
import com.banking.transactionservice.support.TestParticipants;
import com.banking.transactionservice.support.TestParticipants.ReceivedCommand;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * A payment with a debit card from the request to its end, over a real Kafka. The test answers the
 * commands the way Card Service and Account Service would.
 */
class CardPaymentFlowTest extends IntegrationTest {

  private static final String RECEIVED = "card-payment-2026.11_20-A";

  @Test
  void aCardPaymentAsksForTheAccountsOfTheCardThenDebitsTheMainOneAndCompletes() {
    UUID id = UUID.randomUUID();
    UUID card = UUID.randomUUID();
    UUID main = UUID.randomUUID();
    UUID other = UUID.randomUUID();

    webTestClient
        .post()
        .uri("/api/v1/transactions/card-payments")
        .header(CorrelationId.HEADER, RECEIVED)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(cardPayment(id, card))
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals("Location", "/api/v1/transactions/" + id)
        .expectHeader()
        .doesNotExist("Idempotent-Replayed")
        .expectBody()
        .jsonPath("$.id")
        .isEqualTo(id.toString())
        .jsonPath("$.type")
        .isEqualTo("CARD_PAYMENT")
        .jsonPath("$.status")
        .isEqualTo("PENDING_CARD")
        .jsonPath("$.sourceCardId")
        .isEqualTo(card.toString())
        .jsonPath("$.accountId")
        .doesNotExist()
        .jsonPath("$.creditId")
        .doesNotExist()
        .jsonPath("$.destinationCardId")
        .doesNotExist();

    ReceivedCommand question = participants.awaitCommand("RESOLVE_CARD", id);
    assertThat(question.topic()).isEqualTo(TestParticipants.CARD_COMMANDS);
    assertThat(question.key()).isEqualTo(card.toString());
    assertThat(question.correlationId()).isEqualTo(RECEIVED);
    assertThat(question.field("cardId")).isEqualTo(card.toString());
    assertThat(question.field("replyTo")).isEqualTo(TestParticipants.REPLIES);
    assertThat(question.body()).containsOnlyKeys("type", "transactionId", "cardId", "replyTo");

    participants.cardResolved(question, main, other);

    ReceivedCommand debit = participants.awaitCommand("DEBIT_ACCOUNT", id);
    assertThat(debit.topic()).isEqualTo(TestParticipants.ACCOUNT_COMMANDS);
    assertThat(debit.key()).isEqualTo(main.toString());
    // The identifier of the request, not the one the answer of Card Service came with.
    assertThat(debit.correlationId()).isEqualTo(RECEIVED);
    assertThat(debit.field("accountId")).isEqualTo(main.toString());
    assertThat(debit.field("operationId")).isNotNull().isNotEqualTo(id.toString());
    assertThat(debit.body()).containsEntry("amount", new BigDecimal("80.00"));
    assertThat(debit.field("currency")).isEqualTo("PEN");
    assertThat(debit.field("description")).isEqualTo("Grocery store");
    assertThat(debit.body())
        .doesNotContainKeys("cardId", "creditId", "originalOperationId", "replyTo");
    assertThat(transaction(id).getAccountId()).isEqualTo(main);

    participants.accountDebited(debit, "1.00", "19.00");

    TransactionResponse completed = awaitStatus(id, TransactionStatus.COMPLETED);
    assertThat(completed.getType()).isEqualTo(TransactionType.CARD_PAYMENT);
    assertThat(completed.getSourceCardId()).isEqualTo(card);
    assertThat(completed.getAccountId()).isEqualTo(main);
    assertThat(completed.getFee()).isEqualByComparingTo("1.00");
    assertThat(completed.getAccountBalanceAfter()).isEqualByComparingTo("19.00");
    assertThat(completed.getDebtAfter()).isNull();
    assertThat(completed.getRejection()).isNull();
    assertThat(column(id, "version")).isEqualTo(2);
    assertThat(participants.countCommands("DEBIT_ACCOUNT", id, Duration.ofSeconds(1))).isEqualTo(1);
    // Nobody asked through Kafka: there is nobody to tell.
    assertThat(participants.countCommands("FUNDS_MOVED", id, Duration.ZERO)).isZero();
  }

  @Test
  void anAccountWithoutFundsMakesThePaymentFallToTheNextAccountOfTheCard() {
    UUID card = UUID.randomUUID();
    UUID main = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID third = UUID.randomUUID();
    UUID id = requestedCardPayment(card);
    participants.cardResolved(participants.awaitCommand("RESOLVE_CARD", id), main, second, third);
    ReceivedCommand first = participants.awaitCommand("DEBIT_ACCOUNT", id);

    participants.accountDebitRejected(
        first, "INSUFFICIENT_FUNDS", "The balance does not cover it.");

    ReceivedCommand next = participants.awaitCommand("DEBIT_ACCOUNT", id, 2);
    assertThat(next.key()).isEqualTo(second.toString());
    assertThat(next.field("accountId")).isEqualTo(second.toString());
    assertThat(next.field("operationId")).isNotEqualTo(first.field("operationId"));
    assertThat(next.body()).containsEntry("amount", new BigDecimal("80.00"));
    assertThat(next.correlationId()).isEqualTo(first.correlationId());
    TransactionResponse trying = transaction(id);
    assertThat(trying.getStatus()).isEqualTo(TransactionStatus.PENDING_DEBIT);
    assertThat(trying.getAccountId()).isEqualTo(second);
    assertThat(trying.getRejection()).isNull();

    // The main account answers again, late: it does not push the payment to the third account.
    participants.accountDebitRejected(
        first, "INSUFFICIENT_FUNDS", "The balance does not cover it.");
    assertThat(participants.countCommands("DEBIT_ACCOUNT", id, Duration.ofSeconds(2))).isEqualTo(2);
    assertThat(transaction(id).getAccountId()).isEqualTo(second);

    participants.accountDebited(next, "0.00", "20.00");

    TransactionResponse completed = awaitStatus(id, TransactionStatus.COMPLETED);
    assertThat(completed.getAccountId()).isEqualTo(second);
    assertThat(completed.getAccountBalanceAfter()).isEqualByComparingTo("20.00");
    assertThat(completed.getRejection()).isNull();
    assertThat(participants.countCommands("DEBIT_ACCOUNT", id, Duration.ofSeconds(1))).isEqualTo(2);
  }

  @Test
  void aCardWhoseAccountsHaveNoFundsIsRejectedAfterTryingThemAllInOrder() {
    UUID card = UUID.randomUUID();
    UUID main = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID id = requestedCardPayment(card);
    // Card Service lists the linked accounts; the main one goes first wherever it is listed.
    participants.cardResolved(participants.awaitCommand("RESOLVE_CARD", id), main, second, main);

    ReceivedCommand first = participants.awaitCommand("DEBIT_ACCOUNT", id);
    assertThat(first.field("accountId")).isEqualTo(main.toString());
    participants.accountDebitRejected(first, "INSUFFICIENT_FUNDS", "No funds in the main account.");
    ReceivedCommand last = participants.awaitCommand("DEBIT_ACCOUNT", id, 2);
    assertThat(last.field("accountId")).isEqualTo(second.toString());
    participants.accountDebitRejected(
        last, "INSUFFICIENT_FUNDS", "No funds in the second account.");

    TransactionResponse rejected = awaitStatus(id, TransactionStatus.REJECTED);
    assertThat(rejected.getRejection().getStep()).isEqualTo(RejectedStep.DEBIT);
    assertThat(rejected.getRejection().getCode()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(rejected.getRejection().getMessage()).isEqualTo("No funds in the second account.");
    assertThat(rejected.getAccountId()).isEqualTo(second);
    assertThat(rejected.getFee()).isNull();
    assertThat(participants.countCommands("DEBIT_ACCOUNT", id, Duration.ofSeconds(1))).isEqualTo(2);
  }

  @Test
  void aRefusalThatIsNotAboutFundsEndsThePaymentWithoutTryingAnotherAccount() {
    UUID card = UUID.randomUUID();
    UUID main = UUID.randomUUID();
    UUID id = requestedCardPayment(card);
    participants.cardResolved(
        participants.awaitCommand("RESOLVE_CARD", id), main, UUID.randomUUID());

    participants.accountDebitRejected(
        participants.awaitCommand("DEBIT_ACCOUNT", id),
        "ACCOUNT_NOT_ACTIVE",
        "The account is closed.");

    TransactionResponse rejected = awaitStatus(id, TransactionStatus.REJECTED);
    assertThat(rejected.getRejection().getStep()).isEqualTo(RejectedStep.DEBIT);
    assertThat(rejected.getRejection().getCode()).isEqualTo("ACCOUNT_NOT_ACTIVE");
    assertThat(rejected.getAccountId()).isEqualTo(main);
    assertThat(participants.countCommands("DEBIT_ACCOUNT", id, Duration.ofSeconds(1))).isEqualTo(1);
  }

  @Test
  void aCardThatCardServiceRefusesEndsThePaymentAndNoAccountIsAsked() {
    UUID id = requestedCardPayment(UUID.randomUUID());

    participants.cardResolutionRejected(
        participants.awaitCommand("RESOLVE_CARD", id), "CARD_NOT_ACTIVE", "The card is blocked.");

    TransactionResponse rejected = awaitStatus(id, TransactionStatus.REJECTED);
    assertThat(rejected.getRejection().getStep()).isEqualTo(RejectedStep.CARD);
    assertThat(rejected.getRejection().getCode()).isEqualTo("CARD_NOT_ACTIVE");
    assertThat(rejected.getRejection().getMessage()).isEqualTo("The card is blocked.");
    assertThat(rejected.getAccountId()).isNull();
    assertThat(participants.countCommands("DEBIT_ACCOUNT", id, Duration.ofSeconds(1))).isZero();
  }

  @Test
  void aResolvedCardThatCarriesTheDocumentOfItsHolderIsUsedLikeAnyOther() {
    UUID card = UUID.randomUUID();
    UUID main = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID id = requestedCardPayment(card);
    participants.awaitCommand("RESOLVE_CARD", id);

    // What Card Service publishes since it keeps the document of the holder of the card.
    participants.send(
        id.toString(),
        "{\"type\":\"CARD_RESOLVED\",\"transactionId\":\""
            + id
            + "\",\"cardId\":\""
            + card
            + "\",\"customerId\":\""
            + UUID.randomUUID()
            + "\",\"mainAccountId\":\""
            + main
            + "\",\"accountIds\":[\""
            + main
            + "\",\""
            + second
            + "\"],\"holderDocumentType\":\"DNI\",\"holderDocumentNumber\":\"12345678\"}",
        "reply-" + id);

    ReceivedCommand debit = participants.awaitCommand("DEBIT_ACCOUNT", id);
    assertThat(debit.field("accountId")).isEqualTo(main.toString());
    assertThat(debit.body()).doesNotContainKeys("holderDocumentType", "holderDocumentNumber");
    participants.accountDebitRejected(debit, "INSUFFICIENT_FUNDS", "No funds.");
    ReceivedCommand next = participants.awaitCommand("DEBIT_ACCOUNT", id, 2);
    assertThat(next.field("accountId")).isEqualTo(second.toString());
    participants.accountDebited(next, "0.00", "20.00");
    assertThat(awaitStatus(id, TransactionStatus.COMPLETED).getAccountId()).isEqualTo(second);
  }

  @Test
  void answersOfCardServiceThatCannotBeUsedAreSkippedAndRepeatsChangeNothing() {
    UUID card = UUID.randomUUID();
    UUID main = UUID.randomUUID();
    UUID id = requestedCardPayment(card);
    ReceivedCommand question = participants.awaitCommand("RESOLVE_CARD", id);

    // No main account, and an answer about a card the payment did not ask about.
    participants.send(
        id.toString(),
        "{\"type\":\"CARD_RESOLVED\",\"transactionId\":\"" + id + "\",\"cardId\":\"" + card + "\"}",
        null);
    participants.send(
        id.toString(),
        "{\"type\":\"CARD_RESOLVED\",\"transactionId\":\""
            + id
            + "\",\"cardId\":\""
            + UUID.randomUUID()
            + "\",\"mainAccountId\":\""
            + UUID.randomUUID()
            + "\"}",
        null);
    participants.cardResolved(question, main);
    participants.cardResolved(question, UUID.randomUUID());
    participants.cardResolutionRejected(question, "CARD_NOT_ACTIVE", "The card is blocked.");

    ReceivedCommand debit = participants.awaitCommand("DEBIT_ACCOUNT", id);
    assertThat(debit.field("accountId")).isEqualTo(main.toString());
    participants.accountDebited(debit, "0.00", "20.00");
    assertThat(awaitStatus(id, TransactionStatus.COMPLETED).getAccountId()).isEqualTo(main);
    assertThat(participants.countCommands("DEBIT_ACCOUNT", id, Duration.ofSeconds(1))).isEqualTo(1);
  }
}
