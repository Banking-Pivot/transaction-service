package com.banking.transactionservice.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.CardMovementResponse;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.LatestCardMovementsResponse;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.MovementDirection;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionPageResponse;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionResponse;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionStatus;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionType;
import com.banking.transactionservice.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The REST API of card payments: requests, repeats, validation and the queries by card. */
class CardPaymentApiTest extends IntegrationTest {

  @Test
  void aRepeatedRequestReturnsTheSameTransactionAndAsksForNothingNew() {
    UUID id = UUID.randomUUID();
    UUID card = UUID.randomUUID();
    requestCardPayment(cardPayment(id, card)).expectStatus().isAccepted();
    participants.awaitCommand("RESOLVE_CARD", id);

    requestCardPayment(cardPayment(id, card))
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals("Idempotent-Replayed", "true")
        .expectHeader()
        .valueEquals("Location", "/api/v1/transactions/" + id)
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("PENDING_CARD");

    assertThat(participants.countCommands("RESOLVE_CARD", id, Duration.ofSeconds(1))).isEqualTo(1);
    assertThat(column(id, "version")).isZero();
  }

  @Test
  void theSameOperationIdWithOtherDataOrForAnotherKindOfPaymentIsAConflict() {
    UUID id = UUID.randomUUID();
    UUID card = UUID.randomUUID();
    requestCardPayment(cardPayment(id, card)).expectStatus().isAccepted();

    requestCardPayment(cardPayment(id, UUID.randomUUID()))
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("OPERATION_ID_CONFLICT");
    requestPayment(payment(id, UUID.randomUUID(), UUID.randomUUID()))
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("OPERATION_ID_CONFLICT");

    assertThat(transaction(id).getSourceCardId()).isEqualTo(card);
  }

  @ParameterizedTest
  @ValueSource(strings = {"operationId", "cardId", "amount", "currency"})
  void aRequestWithoutARequiredFieldIsRejected(String field) {
    Map<String, Object> body = cardPayment(UUID.randomUUID(), UUID.randomUUID());
    body.remove(field);

    requestCardPayment(body)
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_REQUEST")
        .jsonPath("$.message")
        .value(message -> assertThat((String) message).contains(field));
  }

  @Test
  void aRequestThatBreaksTheContractIsRejectedAndNothingIsStored() {
    UUID id = UUID.randomUUID();
    Map<String, Object> noAmount = cardPayment(id, UUID.randomUUID());
    noAmount.put("amount", new BigDecimal("0.00"));
    Map<String, Object> badCard = cardPayment(id, UUID.randomUUID());
    badCard.put("cardId", "not-a-uuid");
    Map<String, Object> euros = cardPayment(id, UUID.randomUUID());
    euros.put("currency", "EUR");

    for (Map<String, Object> body : List.of(noAmount, badCard, euros)) {
      requestCardPayment(body)
          .expectStatus()
          .isBadRequest()
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("INVALID_REQUEST");
    }
    webTestClient.get().uri("/api/v1/transactions/{id}", id).exchange().expectStatus().isNotFound();
  }

  @Test
  void aPaymentAboveTheLimitOfOnePaymentIsRefusedBeforeAnythingIsStoredOrSent() {
    UUID id = UUID.randomUUID();
    UUID card = UUID.randomUUID();

    requestCardPayment(cardPayment(id, card, "5000.01"))
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("CARD_PAYMENT_LIMIT_EXCEEDED")
        .jsonPath("$.message")
        .isEqualTo("The amount exceeds the limit of 5000.00 per card payment.")
        .jsonPath("$.path")
        .isEqualTo("/api/v1/transactions/card-payments");

    webTestClient.get().uri("/api/v1/transactions/{id}", id).exchange().expectStatus().isNotFound();
    assertThat(participants.countCommands("RESOLVE_CARD", id, Duration.ofSeconds(1))).isZero();
    // Exactly the limit is a payment like any other, also under the key that was just refused.
    requestCardPayment(cardPayment(id, card, "5000.00")).expectStatus().isAccepted();
    participants.awaitCommand("RESOLVE_CARD", id);
  }

  @Test
  void theCardPaymentsOfOneDayCannotPassTheDailyLimitOfTheCard() {
    UUID card = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID refused = UUID.randomUUID();
    requestCardPayment(cardPayment(first, card, "5000.00")).expectStatus().isAccepted();
    requestCardPayment(cardPayment(second, card, "5000.00")).expectStatus().isAccepted();

    requestCardPayment(cardPayment(refused, card, "0.01"))
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("CARD_DAILY_LIMIT_EXCEEDED")
        .jsonPath("$.message")
        .isEqualTo("The payment exceeds the limit of 10000.00 per day of the card.");

    webTestClient
        .get()
        .uri("/api/v1/transactions/{id}", refused)
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThat(participants.countCommands("RESOLVE_CARD", refused, Duration.ofSeconds(1))).isZero();
    // The limit is of each card.
    requestCardPayment(cardPayment(UUID.randomUUID(), UUID.randomUUID(), "5000.00"))
        .expectStatus()
        .isAccepted();
    // A repeat of a recorded payment is answered as always, with the card full.
    requestCardPayment(cardPayment(first, card, "5000.00"))
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals("Idempotent-Replayed", "true");
  }

  @Test
  void aCardPaymentThatWasRejectedGivesItsAmountBackToTheDay() {
    UUID card = UUID.randomUUID();
    UUID kept = UUID.randomUUID();
    UUID rejected = UUID.randomUUID();
    requestCardPayment(cardPayment(kept, card, "5000.00")).expectStatus().isAccepted();
    requestCardPayment(cardPayment(rejected, card, "5000.00")).expectStatus().isAccepted();
    requestCardPayment(cardPayment(UUID.randomUUID(), card, "80.00")).expectStatus().isEqualTo(422);

    participants.cardResolved(
        participants.awaitCommand("RESOLVE_CARD", rejected), UUID.randomUUID());
    participants.accountDebitRejected(
        participants.awaitCommand("DEBIT_ACCOUNT", rejected), "ACCOUNT_NOT_ACTIVE", "Closed.");
    awaitStatus(rejected, TransactionStatus.REJECTED);
    // The other one completes and keeps counting.
    participants.cardResolved(participants.awaitCommand("RESOLVE_CARD", kept), UUID.randomUUID());
    participants.accountDebited(participants.awaitCommand("DEBIT_ACCOUNT", kept), "0.00", "1.00");
    awaitStatus(kept, TransactionStatus.COMPLETED);

    requestCardPayment(cardPayment(UUID.randomUUID(), card, "5000.00")).expectStatus().isAccepted();
    requestCardPayment(cardPayment(UUID.randomUUID(), card, "0.01")).expectStatus().isEqualTo(422);
  }

  @Test
  void theMovementsOfACardAreItsCompletedTransactionsNewestFirst() {
    UUID card = UUID.randomUUID();
    UUID main = UUID.randomUUID();
    UUID paid = completedCardPayment(card, main);
    UUID refused = requestedCardPayment(card);
    participants.cardResolutionRejected(
        participants.awaitCommand("RESOLVE_CARD", refused), "CARD_NOT_ACTIVE", "Blocked.");
    awaitStatus(refused, TransactionStatus.REJECTED);
    UUID received = UUID.randomUUID();
    participants.moveFunds(received, null, card);
    participants.cardResolved(participants.awaitCommand("RESOLVE_CARD", received), main);
    participants.accountCredited(
        participants.awaitCommand("CREDIT_ACCOUNT", received), "0.00", "79.00");
    awaitStatus(received, TransactionStatus.COMPLETED);
    UUID waiting = requestedCardPayment(card);
    completedCardPayment(UUID.randomUUID(), UUID.randomUUID());

    LatestCardMovementsResponse movements = movements(card);

    assertThat(movements.getCardId()).isEqualTo(card);
    assertThat(movements.getItems())
        .extracting(CardMovementResponse::getTransactionId)
        .containsExactly(received, paid);
    CardMovementResponse credit = movements.getItems().get(0);
    assertThat(credit.getType()).isEqualTo(TransactionType.WALLET_PAYMENT);
    assertThat(credit.getDirection()).isEqualTo(MovementDirection.CREDIT);
    assertThat(credit.getAccountId()).isEqualTo(main);
    assertThat(credit.getAmount()).isEqualByComparingTo("60.00");
    assertThat(credit.getFee()).isEqualByComparingTo("0.00");
    assertThat(credit.getDescription()).isEqualTo("Yanki payment");
    CardMovementResponse debit = movements.getItems().get(1);
    assertThat(debit.getType()).isEqualTo(TransactionType.CARD_PAYMENT);
    assertThat(debit.getDirection()).isEqualTo(MovementDirection.DEBIT);
    assertThat(debit.getAccountId()).isEqualTo(main);
    assertThat(debit.getAmount()).isEqualByComparingTo("80.00");
    assertThat(debit.getFee()).isEqualByComparingTo("1.00");
    assertThat(debit.getCurrency().getValue()).isEqualTo("PEN");
    assertThat(debit.getDescription()).isEqualTo("Grocery store");
    assertThat(debit.getOccurredAt()).isEqualTo(transaction(paid).getUpdatedAt());
    assertThat(credit.getOccurredAt()).isAfterOrEqualTo(debit.getOccurredAt());

    // The list by card shows every transaction of the card, whatever its status.
    assertThat(ids(list("?cardId=" + card))).containsExactly(waiting, received, refused, paid);
    assertThat(ids(list("?cardId=" + card + "&status=COMPLETED"))).containsExactly(received, paid);
    assertThat(ids(list("?cardId=" + card + "&status=REJECTED"))).containsExactly(refused);
    assertThat(ids(list("?cardId=" + card + "&accountId=" + main))).containsExactly(paid);
    assertThat(ids(list("?cardId=" + UUID.randomUUID()))).isEmpty();
  }

  @Test
  void aCardThatMovedNoMoneyHasNoMovementsAndAMalformedCardIsABadRequest() {
    UUID card = UUID.randomUUID();
    requestedCardPayment(card);

    assertThat(movements(card).getItems()).isEmpty();
    assertThat(movements(card).getCardId()).isEqualTo(card);
    webTestClient
        .get()
        .uri("/api/v1/transactions/cards/not-a-uuid/movements/latest")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_REQUEST");
    webTestClient
        .get()
        .uri("/api/v1/transactions?cardId=not-a-uuid")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  private static Map<String, Object> cardPayment(UUID operationId, UUID cardId, String amount) {
    Map<String, Object> body = cardPayment(operationId, cardId);
    body.put("amount", new BigDecimal(amount));
    return body;
  }

  private UUID completedCardPayment(UUID card, UUID mainAccount) {
    UUID id = requestedCardPayment(card);
    participants.cardResolved(participants.awaitCommand("RESOLVE_CARD", id), mainAccount);
    participants.accountDebited(participants.awaitCommand("DEBIT_ACCOUNT", id), "1.00", "19.00");
    awaitStatus(id, TransactionStatus.COMPLETED);
    return id;
  }

  private LatestCardMovementsResponse movements(UUID card) {
    return webTestClient
        .get()
        .uri("/api/v1/transactions/cards/{cardId}/movements/latest", card)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(LatestCardMovementsResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private TransactionPageResponse list(String query) {
    return webTestClient
        .get()
        .uri("/api/v1/transactions" + query)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(TransactionPageResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private static List<UUID> ids(TransactionPageResponse page) {
    return page.getItems().stream().map(TransactionResponse::getId).toList();
  }
}
