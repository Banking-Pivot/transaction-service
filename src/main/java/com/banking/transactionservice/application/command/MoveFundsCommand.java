package com.banking.transactionservice.application.command;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * What Yanki Service asks for a wallet payment whose money, on one side or on both, is in the main
 * account of a debit card. The values arrive as they were sent: the use case checks them, because a
 * request that cannot be accepted still has to be answered.
 *
 * @param transactionId ID of the movement; asking again with it never moves money twice
 * @param sourceCardId card whose main account is debited, or null
 * @param destinationCardId card whose main account is credited, or null
 * @param currency currency code as it was sent
 * @param correlationId correlation identifier of the message, kept with the transaction
 */
public record MoveFundsCommand(
    UUID transactionId,
    UUID sourceCardId,
    UUID destinationCardId,
    BigDecimal amount,
    String currency,
    String description,
    String correlationId) {

  /** Requires the transaction and the correlation identifier. */
  public MoveFundsCommand {
    Objects.requireNonNull(transactionId, "transactionId");
    Objects.requireNonNull(correlationId, "correlationId");
  }
}
