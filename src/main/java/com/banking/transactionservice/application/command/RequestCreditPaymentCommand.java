package com.banking.transactionservice.application.command;

import com.banking.transactionservice.domain.model.CurrencyCode;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Asks to pay a credit product from an account, once per operationId.
 *
 * @param correlationId correlation identifier of the request, kept with the transaction
 */
public record RequestCreditPaymentCommand(
    UUID operationId,
    UUID accountId,
    UUID creditId,
    BigDecimal amount,
    CurrencyCode currency,
    String description,
    String correlationId) {

  /** Requires everything except the description. */
  public RequestCreditPaymentCommand {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(creditId, "creditId");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(correlationId, "correlationId");
  }
}
