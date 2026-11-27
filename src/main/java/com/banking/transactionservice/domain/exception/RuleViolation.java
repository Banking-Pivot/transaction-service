package com.banking.transactionservice.domain.exception;

/** Business rules a well-formed request can break. The name is the stable API error code. */
public enum RuleViolation {
  INVALID_AMOUNT,
  INVALID_CURRENCY,
  CARD_REQUIRED,
  CARD_PAYMENT_LIMIT_EXCEEDED,
  CARD_DAILY_LIMIT_EXCEEDED
}
