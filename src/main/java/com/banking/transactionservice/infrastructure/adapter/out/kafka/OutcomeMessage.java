package com.banking.transactionservice.infrastructure.adapter.out.kafka;

import com.banking.transactionservice.domain.model.FundsMovementOutcome;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The outcome of a movement of wallet funds as it travels in Kafka: one JSON object whose {@code
 * type} says how it ended. Fields that do not apply are left out.
 *
 * @param fee what Account Service charged, the debit and the credit together
 * @param occurredAt instant the transaction completed, in ISO-8601 (UTC)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutcomeMessage(
    String type,
    UUID transactionId,
    UUID sourceAccountId,
    UUID destinationAccountId,
    BigDecimal amount,
    BigDecimal fee,
    String occurredAt,
    String code,
    String message) {

  static final String FUNDS_MOVED = "FUNDS_MOVED";
  static final String FUNDS_MOVE_REJECTED = "FUNDS_MOVE_REJECTED";

  static OutcomeMessage of(FundsMovementOutcome outcome) {
    if (outcome instanceof FundsMovementOutcome.Moved moved) {
      return new OutcomeMessage(
          FUNDS_MOVED,
          moved.transactionId(),
          moved.sourceAccountId(),
          moved.destinationAccountId(),
          moved.amount(),
          moved.fee(),
          moved.occurredAt().toString(),
          null,
          null);
    }
    FundsMovementOutcome.Rejected rejected = (FundsMovementOutcome.Rejected) outcome;
    return new OutcomeMessage(
        FUNDS_MOVE_REJECTED,
        rejected.transactionId(),
        null,
        null,
        null,
        null,
        null,
        rejected.code(),
        rejected.message());
  }
}
