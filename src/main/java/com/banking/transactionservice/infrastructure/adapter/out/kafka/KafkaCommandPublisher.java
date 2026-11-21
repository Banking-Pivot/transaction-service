package com.banking.transactionservice.infrastructure.adapter.out.kafka;

import com.banking.transactionservice.application.port.out.CommandPublisherPort;
import com.banking.transactionservice.domain.model.ParticipantCommand;
import com.banking.transactionservice.infrastructure.config.TransactionProperties;
import com.banking.transactionservice.infrastructure.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends commands to Kafka as JSON text.
 *
 * <p>Commands for Account Service are keyed by the account and commands for Credit Service by the
 * credit product, so everything asked of one account or one credit product is handled in order. The
 * correlation identifier of the transaction goes in the {@code X-Correlation-ID} header.
 *
 * <p>The Kafka client can block while it looks for the broker, so the send runs away from the
 * threads that serve requests, and it fails after the configured time instead of waiting.
 */
@Component
public class KafkaCommandPublisher implements CommandPublisherPort {

  private final KafkaTemplate<String, String> kafka;
  private final JsonMapper json;
  private final TransactionProperties.Messaging messaging;

  /** Creates the publisher with the Kafka client, the JSON mapper and the topic names. */
  public KafkaCommandPublisher(
      KafkaTemplate<String, String> kafka, JsonMapper json, TransactionProperties properties) {
    this.kafka = kafka;
    this.json = json;
    this.messaging = properties.messaging();
  }

  @Override
  public Mono<Void> publish(ParticipantCommand command, String correlationId) {
    return Mono.fromCallable(() -> toRecord(command, correlationId))
        .flatMap(message -> Mono.fromFuture(() -> kafka.send(message)))
        .subscribeOn(Schedulers.boundedElastic())
        .timeout(messaging.publishTimeout())
        // The broker's confirmation arrives on the thread of the Kafka client; what follows (the
        // response of the request, the next query) must not run there.
        .publishOn(Schedulers.parallel())
        .then();
  }

  private ProducerRecord<String, String> toRecord(
      ParticipantCommand command, String correlationId) {
    if (command instanceof ParticipantCommand.DebitAccount debit) {
      return message(
          messaging.accountCommandsTopic(),
          debit.accountId(),
          CommandMessage.of(debit),
          correlationId);
    }
    if (command instanceof ParticipantCommand.PayCredit payment) {
      return message(
          messaging.creditCommandsTopic(),
          payment.creditId(),
          CommandMessage.of(payment),
          correlationId);
    }
    ParticipantCommand.ReverseDebit reversal = (ParticipantCommand.ReverseDebit) command;
    return message(
        messaging.accountCommandsTopic(),
        reversal.accountId(),
        CommandMessage.of(reversal),
        correlationId);
  }

  private ProducerRecord<String, String> message(
      String topic, UUID key, CommandMessage body, String correlationId) {
    ProducerRecord<String, String> message =
        new ProducerRecord<>(topic, key.toString(), json.writeValueAsString(body));
    message.headers().add(CorrelationId.HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
    return message;
  }
}
