package com.banking.transactionservice.infrastructure.adapter.out.kafka;

import com.banking.transactionservice.application.port.out.CommandPublisherPort;
import com.banking.transactionservice.domain.model.vo.ParticipantCommand;
import com.banking.transactionservice.infrastructure.config.TransactionProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Sends commands to Kafka as JSON text.
 *
 * <p>Commands for Account Service are keyed by the account, commands for Credit Service by the
 * credit product and commands for Card Service by the card, so everything asked about one of them
 * is handled in order. Card Service has more than one requester, so its commands say where the
 * answer goes.
 */
@Component
public class KafkaCommandPublisher implements CommandPublisherPort {

  private final KafkaJsonSender sender;
  private final TransactionProperties.Messaging messaging;

  KafkaCommandPublisher(KafkaJsonSender sender, TransactionProperties properties) {
    this.sender = sender;
    this.messaging = properties.messaging();
  }

  @Override
  public Mono<Void> publish(ParticipantCommand command, String correlationId) {
    if (command instanceof ParticipantCommand.ResolveCard resolution) {
      return sender.send(
          messaging.cardCommandsTopic(),
          resolution.cardId(),
          CommandMessage.of(resolution, messaging.repliesTopic()),
          correlationId);
    }
    if (command instanceof ParticipantCommand.DebitAccount debit) {
      return sender.send(
          messaging.accountCommandsTopic(),
          debit.accountId(),
          CommandMessage.of(debit),
          correlationId);
    }
    if (command instanceof ParticipantCommand.CreditAccount credit) {
      return sender.send(
          messaging.accountCommandsTopic(),
          credit.accountId(),
          CommandMessage.of(credit),
          correlationId);
    }
    if (command instanceof ParticipantCommand.PayCredit payment) {
      return sender.send(
          messaging.creditCommandsTopic(),
          payment.creditId(),
          CommandMessage.of(payment),
          correlationId);
    }
    ParticipantCommand.ReverseDebit reversal = (ParticipantCommand.ReverseDebit) command;
    return sender.send(
        messaging.accountCommandsTopic(),
        reversal.accountId(),
        CommandMessage.of(reversal),
        correlationId);
  }
}
