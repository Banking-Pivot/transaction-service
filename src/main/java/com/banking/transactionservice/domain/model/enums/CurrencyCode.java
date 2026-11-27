package com.banking.transactionservice.domain.model.enums;

import com.banking.transactionservice.domain.exception.RuleViolation;
import com.banking.transactionservice.domain.exception.TransactionRuleViolationException;

/** Currencies the bank operates in. */
public enum CurrencyCode {
  PEN,
  USD;

  /**
   * The currency with that code.
   *
   * @throws TransactionRuleViolationException when the code is missing or is not one the bank
   *     operates in
   */
  public static CurrencyCode of(String code) {
    for (CurrencyCode currency : values()) {
      if (currency.name().equals(code)) {
        return currency;
      }
    }
    throw new TransactionRuleViolationException(
        RuleViolation.INVALID_CURRENCY, "The currency must be PEN or USD.");
  }
}
