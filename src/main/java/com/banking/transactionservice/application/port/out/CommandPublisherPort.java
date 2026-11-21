package com.banking.transactionservice.application.port.out;

import com.banking.transactionservice.domain.model.ParticipantCommand;
import reactor.core.publisher.Mono;

/** Sends a command to the service that owns the money of a step. */
public interface CommandPublisherPort {

  /**
   * Sends the command.
   *
   * @param correlationId correlation identifier of the request that started the transaction
   * @return completion once the broker has the command, or an error when it could not be sent
   */
  Mono<Void> publish(ParticipantCommand command, String correlationId);
}
