package com.banking.transactionservice.infrastructure.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.transactionservice.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The correlation identifier of a request: header, error body and the command it starts. */
class CorrelationIdApiTest extends IntegrationTest {

  private static final String RECEIVED = "order-2026.11_20-A";

  @Test
  void theIdentifierReceivedGoesBackInTheHeaderAndInTheErrorBody() {
    webTestClient
        .get()
        .uri("/api/v1/transactions/{id}", UUID.randomUUID())
        .header(CorrelationId.HEADER, RECEIVED)
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectHeader()
        .valueEquals(CorrelationId.HEADER, RECEIVED)
        .expectBody()
        .jsonPath("$.traceId")
        .isEqualTo(RECEIVED);
  }

  @Test
  void aRequestWithoutIdentifierGetsANewOneAndItsCommandCarriesIt() {
    UUID id = UUID.randomUUID();

    String generated =
        webTestClient
            .post()
            .uri("/api/v1/transactions/credit-payments")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payment(id, UUID.randomUUID(), UUID.randomUUID()))
            .exchange()
            .expectStatus()
            .isAccepted()
            .returnResult(Void.class)
            .getResponseHeaders()
            .getFirst(CorrelationId.HEADER);

    assertThat(generated).matches("[0-9a-f-]{36}");
    assertThat(participants.awaitCommand("DEBIT_ACCOUNT", id).correlationId()).isEqualTo(generated);
    assertThat(generatedFor(webTestClient.get().uri("/api/v1/transactions")))
        .isNotEqualTo(generated);
  }

  @Test
  void anIdentifierThatCouldBreakALogLineIsReplaced() {
    String tooLong = "x".repeat(65);

    assertThat(generatedFor(headed(tooLong))).isNotEqualTo(tooLong);
    assertThat(generatedFor(headed("two words"))).isNotEqualTo("two words");
    assertThat(generatedFor(headed("{\"injected\":true}"))).doesNotContain("injected");
  }

  private WebTestClient.RequestHeadersSpec<?> headed(String identifier) {
    return webTestClient.get().uri("/api/v1/transactions").header(CorrelationId.HEADER, identifier);
  }

  private static String generatedFor(WebTestClient.RequestHeadersSpec<?> request) {
    String identifier =
        request
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(Void.class)
            .getResponseHeaders()
            .getFirst(CorrelationId.HEADER);
    assertThat(identifier).matches("[0-9a-f-]{36}");
    return identifier;
  }
}
