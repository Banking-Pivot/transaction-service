package com.banking.transactionservice.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionResponse;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the whole service on a random port against a real PostgreSQL and a real Kafka in Docker.
 *
 * <p>The application keeps its real {@code spring.config.import}. A stub Config Server serves the
 * settings of {@code stub-config/transaction-service.yml} and the locations of the containers; a
 * real Vault holds the database user and password, which the Config Server never serves. All of
 * them start once, before any Spring context. {@link TestParticipants} plays Account Service and
 * Credit Service on the same Kafka.
 *
 * <p>Every test starts with an empty table. The topics are not emptied: every test uses its own
 * transaction IDs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
public abstract class IntegrationTest {

  protected static final String DATABASE = "transactions_db";
  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:16-alpine").withDatabaseName(DATABASE);
  protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");
  protected static final StubConfigServer CONFIG_SERVER;
  protected static final TestVault VAULT;

  private static TestParticipants connected;

  static {
    POSTGRES.start();
    KAFKA.start();
    // Read by application.yaml through VAULT_URI and VAULT_APPROLE_FILE.
    VAULT =
        TestVault.start(
            "transaction-service",
            Map.of(
                "spring.r2dbc.username", POSTGRES.getUsername(),
                "spring.r2dbc.password", POSTGRES.getPassword(),
                "spring.flyway.user", POSTGRES.getUsername(),
                "spring.flyway.password", POSTGRES.getPassword()));
    Map<String, String> properties = new HashMap<>(yaml("stub-config/transaction-service.yml"));
    properties.put(
        "spring.r2dbc.url",
        "r2dbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(5432)
            + "/"
            + DATABASE);
    properties.put("spring.flyway.url", POSTGRES.getJdbcUrl());
    properties.put("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
    // No registry in the tests: the service must not register with a real Eureka.
    properties.put("eureka.client.enabled", "false");
    CONFIG_SERVER = StubConfigServer.start(properties);
    // Read by application.yaml: configserver:${CONFIG_SERVER_URL:http://localhost:8888}
    System.setProperty("CONFIG_SERVER_URL", CONFIG_SERVER.url());
  }

  @Autowired protected WebTestClient webTestClient;

  @Autowired protected DatabaseClient databaseClient;

  protected TestParticipants participants;

  @BeforeEach
  void startClean() {
    databaseClient.sql("TRUNCATE transactions").then().block();
    participants = participants();
    webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(15)).build();
  }

  // One connection for all the tests, opened when the first context is up and the topics exist.
  private static synchronized TestParticipants participants() {
    if (connected == null) {
      connected = TestParticipants.connect(KAFKA.getBootstrapServers());
    }
    return connected;
  }

  private static Map<String, String> yaml(String resource) {
    YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
    factory.setResources(new ClassPathResource(resource));
    Properties loaded = factory.getObject();
    Map<String, String> properties = new HashMap<>();
    loaded.forEach((key, value) -> properties.put(key.toString(), value.toString()));
    return properties;
  }

  // ----- requests

  /** A valid request to pay 150.00 PEN; tests change what they need. */
  protected static Map<String, Object> payment(UUID operationId, UUID accountId, UUID creditId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("operationId", operationId.toString());
    body.put("accountId", accountId.toString());
    body.put("creditId", creditId.toString());
    body.put("amount", new BigDecimal("150.00"));
    body.put("currency", "PEN");
    body.put("description", "November installment");
    return body;
  }

  protected WebTestClient.ResponseSpec requestPayment(Object body) {
    return webTestClient
        .post()
        .uri("/api/v1/transactions/credit-payments")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  /** Requests a valid payment and returns the ID of its transaction. */
  protected UUID requestedPayment() {
    UUID operationId = UUID.randomUUID();
    requestPayment(payment(operationId, UUID.randomUUID(), UUID.randomUUID()))
        .expectStatus()
        .isAccepted()
        .expectBody(Void.class);
    return operationId;
  }

  protected TransactionResponse transaction(UUID transactionId) {
    return webTestClient
        .get()
        .uri("/api/v1/transactions/{id}", transactionId)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(TransactionResponse.class)
        .returnResult()
        .getResponseBody();
  }

  /** Waits until the transaction reaches the status and returns it. */
  protected TransactionResponse awaitStatus(UUID transactionId, TransactionStatus status) {
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(transaction(transactionId).getStatus()).isEqualTo(status));
    return transaction(transactionId);
  }

  protected long column(UUID transactionId, String column) {
    return databaseClient
        .sql("SELECT " + column + " FROM transactions WHERE id = :id")
        .bind("id", transactionId)
        .map(row -> row.get(0, Number.class).longValue())
        .one()
        .block();
  }
}
