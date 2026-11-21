package com.banking.transactionservice.domain.exception;

import java.util.Objects;

/** A request that breaks a business rule of Transaction Service. */
public class TransactionRuleViolationException extends RuntimeException {

  private final RuleViolation violation;

  public TransactionRuleViolationException(RuleViolation violation, String message) {
    super(message);
    this.violation = Objects.requireNonNull(violation, "violation");
  }

  public RuleViolation violation() {
    return violation;
  }
}
