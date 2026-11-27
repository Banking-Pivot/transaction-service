package com.banking.transactionservice.application.port.in;

import com.banking.transactionservice.application.model.CardMovement;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Reports the latest movements of a debit card. */
public interface GetLatestCardMovementsUseCase {

  /** How many movements the report shows at most. */
  int LIMIT = 10;

  /**
   * The last completed transactions that moved money through the card, newest first.
   *
   * @return at most {@link #LIMIT} movements; none when the card moved no money, or is unknown
   */
  Mono<List<CardMovement>> execute(UUID cardId);
}
