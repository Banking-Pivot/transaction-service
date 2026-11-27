package com.banking.transactionservice.application.port.out;

import com.banking.transactionservice.domain.model.FundsMovementOutcome;
import reactor.core.publisher.Mono;

/** Tells Yanki Service how a movement of wallet funds ended. */
public interface OutcomePublisherPort {

  /**
   * Sends the outcome.
   *
   * @param correlationId correlation identifier of the request that started the transaction
   * @return completion once the broker has the message, or an error when it could not be sent
   */
  Mono<Void> publish(FundsMovementOutcome outcome, String correlationId);
}
