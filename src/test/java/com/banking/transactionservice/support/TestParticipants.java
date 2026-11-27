package com.banking.transactionservice.support;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plays the services around Transaction Service on a real Kafka. As Account Service, Credit Service
 * and Card Service it reads the commands Transaction Service sends and answers them the way those
 * services would. As Yanki Service it asks for movements of wallet funds and reads how they ended.
 *
 * <p>Messages are looked up by transaction, so what other tests left in the topics does not get in
 * the way.
 */
public final class TestParticipants {

  public static final String ACCOUNT_COMMANDS = "banking.account.commands";
  public static final String CREDIT_COMMANDS = "banking.credit.commands";
  public static final String CARD_COMMANDS = "banking.card.commands";
  public static final String REPLIES = "banking.transaction.replies";
  public static final String TRANSACTION_COMMANDS = "banking.transaction.commands";
  public static final String WALLET_REPLIES = "banking.yanki.replies";

  private static final Duration PATIENCE = Duration.ofSeconds(30);

  private final KafkaConsumer<String, String> consumer;
  private final KafkaProducer<String, String> producer;
  private final List<ReceivedCommand> seen = new ArrayList<>();
  private final JsonMapper json =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private TestParticipants(String bootstrapServers) {
    Map<String, Object> reading = new LinkedHashMap<>();
    reading.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    reading.put(ConsumerConfig.GROUP_ID_CONFIG, "test-participants-" + UUID.randomUUID());
    reading.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    reading.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
    reading.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    reading.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumer = new KafkaConsumer<>(reading);
    consumer.subscribe(List.of(ACCOUNT_COMMANDS, CREDIT_COMMANDS, CARD_COMMANDS, WALLET_REPLIES));

    Map<String, Object> writing = new LinkedHashMap<>();
    writing.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    writing.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    writing.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producer = new KafkaProducer<>(writing);
  }

  /** Connects once the service created the topics. */
  public static TestParticipants connect(String bootstrapServers) {
    return new TestParticipants(bootstrapServers);
  }

  /** The first command of that type for the transaction; fails if none arrives. */
  public ReceivedCommand awaitCommand(String type, UUID transactionId) {
    return awaitCommand(type, transactionId, 1);
  }

  /** The n-th command of that type for the transaction, counting from 1. */
  public ReceivedCommand awaitCommand(String type, UUID transactionId, int occurrence) {
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    while (true) {
      List<ReceivedCommand> matches = commands(type, transactionId);
      if (matches.size() >= occurrence) {
        return matches.get(occurrence - 1);
      }
      if (System.nanoTime() > deadline) {
        throw new AssertionError(
            "Expected command "
                + type
                + " #"
                + occurrence
                + " for "
                + transactionId
                + " but saw "
                + matches.size());
      }
      poll(Duration.ofMillis(200));
    }
  }

  /** The first outcome of that type Yanki Service was told about the transaction. */
  public ReceivedCommand awaitOutcome(String type, UUID transactionId) {
    return awaitCommand(type, transactionId, 1);
  }

  /** The n-th outcome of that type Yanki Service was told about the transaction. */
  public ReceivedCommand awaitOutcome(String type, UUID transactionId, int occurrence) {
    return awaitCommand(type, transactionId, occurrence);
  }

  /** How many commands of that type the transaction got, after listening for a while longer. */
  public int countCommands(String type, UUID transactionId, Duration listenFor) {
    long deadline = System.nanoTime() + listenFor.toNanos();
    while (System.nanoTime() < deadline) {
      poll(Duration.ofMillis(200));
    }
    return commands(type, transactionId).size();
  }

  // ----- answers of Card Service

  /** The card is active and uses these accounts; the first one is the main account. */
  public void cardResolved(ReceivedCommand command, UUID... accountIds) {
    Map<String, Object> body = answerTo("CARD_RESOLVED", command);
    body.put("cardId", command.field("cardId"));
    body.put("customerId", UUID.randomUUID().toString());
    body.put("mainAccountId", accountIds[0].toString());
    body.put("accountIds", Arrays.stream(accountIds).map(UUID::toString).toList());
    send(command, body);
  }

  public void cardResolutionRejected(ReceivedCommand command, String code, String message) {
    Map<String, Object> body = answerTo("CARD_RESOLUTION_REJECTED", command);
    body.put("cardId", command.field("cardId"));
    body.put("code", code);
    body.put("message", message);
    send(command, body);
  }

  // ----- answers of Account Service to a command, with the operationId and account it carried

  public void accountDebited(ReceivedCommand command, String fee, String balanceAfter) {
    Map<String, Object> body = answerTo("ACCOUNT_DEBITED", command);
    body.put("accountId", command.field("accountId"));
    body.put("amount", command.body().get("amount"));
    body.put("fee", new BigDecimal(fee));
    body.put("balanceAfter", new BigDecimal(balanceAfter));
    body.put("currency", command.field("currency"));
    body.put("occurredAt", "2026-11-20T15:00:00Z");
    send(command, body);
  }

  public void accountDebitRejected(ReceivedCommand command, String code, String message) {
    Map<String, Object> body = answerTo("ACCOUNT_DEBIT_REJECTED", command);
    body.put("accountId", command.field("accountId"));
    body.put("code", code);
    body.put("message", message);
    send(command, body);
  }

  public void accountCredited(ReceivedCommand command, String fee, String balanceAfter) {
    Map<String, Object> body = answerTo("ACCOUNT_CREDITED", command);
    body.put("accountId", command.field("accountId"));
    body.put("amount", command.body().get("amount"));
    body.put("fee", new BigDecimal(fee));
    body.put("balanceAfter", new BigDecimal(balanceAfter));
    body.put("occurredAt", "2026-11-20T15:00:01Z");
    send(command, body);
  }

  public void accountCreditRejected(ReceivedCommand command, String code, String message) {
    Map<String, Object> body = answerTo("ACCOUNT_CREDIT_REJECTED", command);
    body.put("accountId", command.field("accountId"));
    body.put("code", code);
    body.put("message", message);
    send(command, body);
  }

  // ----- requests of Yanki Service

  /**
   * Asks to move wallet funds, 60.00 PEN.
   *
   * @param sourceCardId card of the payer, or null
   * @param destinationCardId card of the payee, or null
   */
  public void moveFunds(UUID transactionId, UUID sourceCardId, UUID destinationCardId) {
    moveFunds(transactionId, sourceCardId, destinationCardId, "60.00", "PEN");
  }

  /** Asks to move wallet funds with the amount and currency exactly as given. */
  public void moveFunds(
      UUID transactionId,
      UUID sourceCardId,
      UUID destinationCardId,
      String amount,
      String currency) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "MOVE_FUNDS");
    body.put("transactionId", transactionId.toString());
    if (sourceCardId != null) {
      body.put("sourceCardId", sourceCardId.toString());
    }
    if (destinationCardId != null) {
      body.put("destinationCardId", destinationCardId.toString());
    }
    body.put("amount", new BigDecimal(amount));
    body.put("currency", currency);
    body.put("description", "Yanki payment");
    sendTo(
        TRANSACTION_COMMANDS,
        transactionId.toString(),
        json.writeValueAsString(body),
        "yanki-" + transactionId);
  }

  // ----- answers of Account Service to a credit payment, whose debit is recorded under its ID

  public void accountDebited(UUID transactionId, String fee, String balanceAfter) {
    Map<String, Object> body = reply("ACCOUNT_DEBITED", transactionId);
    body.put("amount", new BigDecimal("150.00"));
    body.put("fee", new BigDecimal(fee));
    body.put("balanceAfter", new BigDecimal(balanceAfter));
    body.put("currency", "PEN");
    body.put("occurredAt", "2026-11-20T15:00:00Z");
    send(transactionId.toString(), json.writeValueAsString(body), "reply-" + transactionId);
  }

  public void accountDebitRejected(UUID transactionId, String code, String message) {
    send(transactionId, rejected("ACCOUNT_DEBIT_REJECTED", transactionId, code, message));
  }

  public void debitReversed(UUID transactionId, String balanceAfter) {
    Map<String, Object> body = reply("DEBIT_REVERSED", transactionId);
    body.put("amount", new BigDecimal("151.50"));
    body.put("balanceAfter", new BigDecimal(balanceAfter));
    body.put("occurredAt", "2026-11-20T15:00:02Z");
    send(transactionId, body);
  }

  public void debitReversalRejected(UUID transactionId, String code, String message) {
    send(transactionId, rejected("DEBIT_REVERSAL_REJECTED", transactionId, code, message));
  }

  // ----- answers of Credit Service

  public void creditPaid(UUID transactionId, String debtAfter) {
    Map<String, Object> body = reply("CREDIT_PAID", transactionId);
    body.put("amount", new BigDecimal("150.00"));
    body.put("debtAfter", new BigDecimal(debtAfter));
    body.put("occurredAt", "2026-11-20T15:00:01Z");
    send(transactionId, body);
  }

  public void creditPaymentRejected(UUID transactionId, String code, String message) {
    send(transactionId, rejected("CREDIT_PAYMENT_REJECTED", transactionId, code, message));
  }

  /** Puts any text in the replies topic, to send what a well-behaved service never would. */
  public void send(String key, String value, String correlationId) {
    sendTo(REPLIES, key, value, correlationId);
  }

  /** Puts any text in a topic. */
  public void sendTo(String topic, String key, String value, String correlationId) {
    ProducerRecord<String, String> message = new ProducerRecord<>(topic, key, value);
    if (correlationId != null) {
      message.headers().add("X-Correlation-ID", correlationId.getBytes(StandardCharsets.UTF_8));
    }
    try {
      producer.send(message).get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    } catch (ExecutionException failed) {
      throw new IllegalStateException(failed.getCause());
    }
  }

  private void send(UUID transactionId, Map<String, Object> body) {
    send(transactionId.toString(), json.writeValueAsString(body), "reply-" + transactionId);
  }

  private void send(ReceivedCommand command, Map<String, Object> body) {
    send(
        command.field("transactionId"),
        json.writeValueAsString(body),
        "reply-" + command.field("transactionId"));
  }

  // The answer to a command says which transaction and, when the command had one, which
  // operationId it is about.
  private static Map<String, Object> answerTo(String type, ReceivedCommand command) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", type);
    body.put("transactionId", command.field("transactionId"));
    if (command.field("operationId") != null) {
      body.put("operationId", command.field("operationId"));
    }
    return body;
  }

  private static Map<String, Object> reply(String type, UUID transactionId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", type);
    body.put("transactionId", transactionId.toString());
    body.put("operationId", transactionId.toString());
    return body;
  }

  private static Map<String, Object> rejected(
      String type, UUID transactionId, String code, String message) {
    Map<String, Object> body = reply(type, transactionId);
    body.put("code", code);
    body.put("message", message);
    return body;
  }

  private List<ReceivedCommand> commands(String type, UUID transactionId) {
    return seen.stream()
        .filter(command -> type.equals(command.body().get("type")))
        .filter(command -> transactionId.toString().equals(command.body().get("transactionId")))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private void poll(Duration wait) {
    for (ConsumerRecord<String, String> message : consumer.poll(wait)) {
      Header header = message.headers().lastHeader("X-Correlation-ID");
      seen.add(
          new ReceivedCommand(
              message.topic(),
              message.key(),
              message.value(),
              (Map<String, Object>) json.readValue(message.value(), Map.class),
              header == null ? null : new String(header.value(), StandardCharsets.UTF_8)));
    }
  }

  /**
   * A command, or an outcome, as it was read from Kafka.
   *
   * @param text the value exactly as sent
   * @param body the value parsed; decimals are BigDecimal
   * @param correlationId value of the X-Correlation-ID header, or null
   */
  public record ReceivedCommand(
      String topic, String key, String text, Map<String, Object> body, String correlationId) {

    public String field(String name) {
      Object value = body.get(name);
      return value == null ? null : value.toString();
    }
  }
}
