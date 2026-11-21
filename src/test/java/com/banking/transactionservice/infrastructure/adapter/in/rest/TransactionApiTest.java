package com.banking.transactionservice.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionPageResponse;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionResponse;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionStatus;
import com.banking.transactionservice.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

/** The REST API of transactions: requests, repeats, validation and queries. */
class TransactionApiTest extends IntegrationTest {

  @Test
  void aRepeatedRequestReturnsTheSameTransactionAndAsksForNothingNew() {
    UUID id = UUID.randomUUID();
    Map<String, Object> body = payment(id, UUID.randomUUID(), UUID.randomUUID());
    requestPayment(body)
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .doesNotExist("Idempotent-Replayed");
    participants.awaitCommand("DEBIT_ACCOUNT", id);
    participants.accountDebited(id, "1.50", "348.50");
    awaitStatus(id, TransactionStatus.PENDING_PAYMENT);
    // 150 and 150.00 are the same amount.
    body.put("amount", 150);

    requestPayment(body)
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals("Idempotent-Replayed", "true")
        .expectHeader()
        .valueEquals("Location", "/api/v1/transactions/" + id)
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("PENDING_PAYMENT")
        .jsonPath("$.fee")
        .isEqualTo(1.50);

    assertThat(participants.countCommands("DEBIT_ACCOUNT", id, Duration.ofSeconds(1))).isEqualTo(1);
    assertThat(participants.countCommands("PAY_CREDIT", id, Duration.ZERO)).isEqualTo(1);
  }

  @Test
  void theSameOperationIdWithOtherDataIsAConflict() {
    UUID id = UUID.randomUUID();
    Map<String, Object> body = payment(id, UUID.randomUUID(), UUID.randomUUID());
    requestPayment(body).expectStatus().isAccepted();
    body.put("amount", new BigDecimal("150.01"));

    requestPayment(body)
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("OPERATION_ID_CONFLICT")
        .jsonPath("$.path")
        .isEqualTo("/api/v1/transactions/credit-payments")
        .jsonPath("$.traceId")
        .isNotEmpty()
        .jsonPath("$.timestamp")
        .isNotEmpty();

    assertThat(transaction(id).getAmount()).isEqualByComparingTo("150.00");
  }

  @Test
  void aPaymentWithoutDescriptionIsAccepted() {
    UUID id = UUID.randomUUID();
    Map<String, Object> body = payment(id, UUID.randomUUID(), UUID.randomUUID());
    body.remove("description");
    body.put("currency", "USD");

    requestPayment(body)
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.description")
        .doesNotExist()
        .jsonPath("$.currency")
        .isEqualTo("USD");

    assertThat(participants.awaitCommand("DEBIT_ACCOUNT", id).body())
        .doesNotContainKey("description")
        .containsEntry("currency", "USD");
  }

  @ParameterizedTest
  @ValueSource(strings = {"operationId", "accountId", "creditId", "amount", "currency"})
  void aRequestWithoutARequiredFieldIsRejected(String field) {
    Map<String, Object> body = payment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    body.remove(field);

    requestPayment(body)
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_REQUEST")
        .jsonPath("$.message")
        .value(message -> assertThat((String) message).contains(field));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-5.00", "10.001", "1000000000000.00"})
  void anAmountThatCannotBeMovedIsRejected(String amount) {
    UUID id = UUID.randomUUID();
    Map<String, Object> body = payment(id, UUID.randomUUID(), UUID.randomUUID());
    body.put("amount", new BigDecimal(amount));

    requestPayment(body)
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_REQUEST");

    webTestClient.get().uri("/api/v1/transactions/{id}", id).exchange().expectStatus().isNotFound();
  }

  @Test
  void aBodyThatBreaksTheContractIsRejectedWithoutEchoingIt() {
    Map<String, Object> unknownField =
        payment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    unknownField.put("status", "COMPLETED");
    Map<String, Object> unknownCurrency =
        payment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    unknownCurrency.put("currency", "EUR");
    Map<String, Object> longDescription =
        payment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    longDescription.put("description", "secret ".repeat(21));
    Map<String, Object> badAccount =
        payment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    badAccount.put("accountId", "not-a-uuid");

    for (Object body : List.of(unknownField, unknownCurrency, longDescription, badAccount, "{")) {
      requestPayment(body)
          .expectStatus()
          .isBadRequest()
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("INVALID_REQUEST")
          .jsonPath("$.message")
          .value(
              message ->
                  assertThat((String) message).doesNotContain("secret", "EUR", "not-a-uuid"));
    }
  }

  @Test
  void aBodyThatIsNotJsonIsUnsupported() {
    webTestClient
        .post()
        .uri("/api/v1/transactions/credit-payments")
        .contentType(MediaType.TEXT_PLAIN)
        .bodyValue("pay my credit")
        .exchange()
        .expectStatus()
        .isEqualTo(415)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
  }

  @Test
  void anUnknownTransactionIsNotFoundAndAMalformedIdIsABadRequest() {
    webTestClient
        .get()
        .uri("/api/v1/transactions/{id}", UUID.randomUUID())
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("TRANSACTION_NOT_FOUND");

    webTestClient
        .get()
        .uri("/api/v1/transactions/not-a-uuid")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_REQUEST");
  }

  @Test
  void aRouteThatDoesNotExistKeepsItsStatusInTheContractShape() {
    webTestClient
        .get()
        .uri("/api/v1/transfers")
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("NOT_FOUND")
        .jsonPath("$.traceId")
        .isNotEmpty();

    webTestClient
        .delete()
        .uri("/api/v1/transactions/{id}", UUID.randomUUID())
        .exchange()
        .expectStatus()
        .isEqualTo(405)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("METHOD_NOT_ALLOWED");
  }

  @Test
  void theListIsNewestFirstAndFiltersByAccountCreditAndStatus() {
    UUID account = UUID.randomUUID();
    UUID credit = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID third = UUID.randomUUID();
    requestPayment(payment(first, account, credit)).expectStatus().isAccepted();
    requestPayment(payment(second, account, UUID.randomUUID())).expectStatus().isAccepted();
    requestPayment(payment(third, UUID.randomUUID(), credit)).expectStatus().isAccepted();
    participants.awaitCommand("DEBIT_ACCOUNT", first);
    participants.accountDebitRejected(
        first, "INSUFFICIENT_FUNDS", "The balance does not cover it.");
    awaitStatus(first, TransactionStatus.REJECTED);

    assertThat(ids(list(""))).containsExactly(third, second, first);
    assertThat(ids(list("?accountId=" + account))).containsExactly(second, first);
    assertThat(ids(list("?creditId=" + credit))).containsExactly(third, first);
    assertThat(ids(list("?accountId=" + account + "&creditId=" + credit))).containsExactly(first);
    assertThat(ids(list("?status=REJECTED"))).containsExactly(first);
    assertThat(ids(list("?status=PENDING_DEBIT&accountId=" + account))).containsExactly(second);
    assertThat(ids(list("?status=COMPLETED"))).isEmpty();

    TransactionPageResponse page = list("?page=1&size=2");
    assertThat(ids(page)).containsExactly(first);
    assertThat(page.getPage()).isEqualTo(1);
    assertThat(page.getSize()).isEqualTo(2);
    assertThat(page.getTotalElements()).isEqualTo(3);
    assertThat(page.getTotalPages()).isEqualTo(2);
    assertThat(list("?page=7").getItems()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "?page=-1",
        "?page=10001",
        "?size=0",
        "?size=101",
        "?size=many",
        "?status=DONE",
        "?accountId=not-a-uuid"
      })
  void aListRequestOutOfRangeIsRejected(String query) {
    webTestClient
        .get()
        .uri("/api/v1/transactions" + query)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_REQUEST");
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
