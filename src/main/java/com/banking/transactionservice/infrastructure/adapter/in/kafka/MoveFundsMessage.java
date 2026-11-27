package com.banking.transactionservice.infrastructure.adapter.in.kafka;

import com.banking.transactionservice.application.command.MoveFundsCommand;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The request of Yanki Service as it travels in Kafka: one JSON object of type {@code MOVE_FUNDS}.
 * Fields this service does not know are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MoveFundsMessage(
    String type,
    UUID transactionId,
    UUID sourceCardId,
    UUID destinationCardId,
    BigDecimal amount,
    String currency,
    String description) {

  static final String MOVE_FUNDS = "MOVE_FUNDS";

  /**
   * The request in the terms of the application. Its values are checked there, where a request that
   * cannot be accepted is answered.
   *
   * @throws IllegalArgumentException when the type is not MOVE_FUNDS or there is no transaction to
   *     answer about
   */
  MoveFundsCommand toCommand(String correlationId) {
    if (!MOVE_FUNDS.equals(type) || transactionId == null) {
      throw new IllegalArgumentException("Not a MOVE_FUNDS command with a transactionId.");
    }
    return new MoveFundsCommand(
        transactionId,
        sourceCardId,
        destinationCardId,
        amount,
        currency,
        description,
        correlationId);
  }
}
