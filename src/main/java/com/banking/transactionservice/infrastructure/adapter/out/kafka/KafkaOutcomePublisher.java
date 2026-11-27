package com.banking.transactionservice.infrastructure.adapter.out.kafka;

import com.banking.transactionservice.application.port.out.OutcomePublisherPort;
import com.banking.transactionservice.domain.model.vo.FundsMovementOutcome;
import com.banking.transactionservice.infrastructure.config.TransactionProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Sends the outcome of a movement of wallet funds to the topic Yanki Service reads its answers
 * from, keyed by the transaction.
 */
@Component
public class KafkaOutcomePublisher implements OutcomePublisherPort {

  private final KafkaJsonSender sender;
  private final String topic;

  KafkaOutcomePublisher(KafkaJsonSender sender, TransactionProperties properties) {
    this.sender = sender;
    this.topic = properties.messaging().walletRepliesTopic();
  }

  @Override
  public Mono<Void> publish(FundsMovementOutcome outcome, String correlationId) {
    return sender.send(topic, outcome.transactionId(), OutcomeMessage.of(outcome), correlationId);
  }
}
