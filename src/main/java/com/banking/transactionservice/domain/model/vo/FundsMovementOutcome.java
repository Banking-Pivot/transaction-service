package com.banking.transactionservice.domain.model.vo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * How a movement of wallet funds ended, as it is told to the service that asked for it (Yanki
 * Service).
 */
public sealed interface FundsMovementOutcome {

  /** The transaction the outcome belongs to: the ID the movement was asked with. */
  UUID transactionId();

  /**
   * The money was moved.
   *
   * @param sourceAccountId account that was debited; null when the movement had no source card
   * @param destinationAccountId account that was credited; null when it had no destination card
   * @param fee what Account Service charged for the movements, the debit and the credit together
   * @param occurredAt when the transaction completed
   */
  record Moved(
      UUID transactionId,
      UUID sourceAccountId,
      UUID destinationAccountId,
      BigDecimal amount,
      BigDecimal fee,
      Instant occurredAt)
      implements FundsMovementOutcome {

    /** Requires the transaction, the amount, the fee and the instant. */
    public Moved {
      Objects.requireNonNull(transactionId, "transactionId");
      Objects.requireNonNull(amount, "amount");
      Objects.requireNonNull(fee, "fee");
      Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  /**
   * The money was not moved, or it was moved and returned.
   *
   * @param code why: the code of the service that refused its step, or one of this service
   */
  record Rejected(UUID transactionId, String code, String message) implements FundsMovementOutcome {

    /** Code when the request itself cannot be accepted. */
    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    /** Code when the ID already identifies a transaction that asks for something else. */
    public static final String OPERATION_ID_CONFLICT = "OPERATION_ID_CONFLICT";

    /** Code when the destination refused the money and the debit could not be returned. */
    public static final String REVERSAL_FAILED = "REVERSAL_FAILED";

    /** Requires the transaction and keeps the code and the message within their limits. */
    public Rejected {
      Objects.requireNonNull(transactionId, "transactionId");
      code = Rejection.limited(code, Rejection.MAX_CODE, "UNKNOWN");
      message = Rejection.limited(message, Rejection.MAX_MESSAGE, "No reason was given.");
    }
  }
}
