package com.banking.transactionservice.application.port.in;

import reactor.core.publisher.Mono;

/** Sends again the commands that have waited too long for an answer. */
public interface ResendPendingCommandsUseCase {

  /**
   * Looks for transactions whose current command got no answer in time and sends it again.
   *
   * @return how many commands were sent again
   */
  Mono<Long> execute();
}
