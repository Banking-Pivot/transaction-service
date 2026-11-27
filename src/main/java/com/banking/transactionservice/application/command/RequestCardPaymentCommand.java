package com.banking.transactionservice.application.command;

import com.banking.transactionservice.domain.model.CurrencyCode;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Asks to pay with a debit card, once per operationId.
 *
 * @param correlationId correlation identifier of the request, kept with the transaction
 */
public record RequestCardPaymentCommand(
    UUID operationId,
    UUID cardId,
    BigDecimal amount,
    CurrencyCode currency,
    String description,
    String correlationId) {

  /** Requires everything except the description. */
  public RequestCardPaymentCommand {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(cardId, "cardId");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(correlationId, "correlationId");
  }
}
