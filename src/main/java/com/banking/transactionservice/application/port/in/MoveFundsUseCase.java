package com.banking.transactionservice.application.port.in;

import com.banking.transactionservice.application.command.MoveFundsCommand;
import reactor.core.publisher.Mono;

/** Starts the movement of wallet funds Yanki Service asked for, or answers why it cannot. */
public interface MoveFundsUseCase {

  /**
   * Handles one request of Yanki Service.
   *
   * @return completion once the transaction is stored, or once the answer of a request that starts
   *     nothing (a repeat of a finished movement, a conflict, an invalid request) was sent
   */
  Mono<Void> execute(MoveFundsCommand command);
}
