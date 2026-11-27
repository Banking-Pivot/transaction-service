package com.banking.transactionservice.domain.model;

/** The step of a transaction a service refused. */
public enum RejectedStep {
  /** Card Service did not accept the debit card. */
  CARD,
  /** Account Service did not debit the account. */
  DEBIT,
  /** Account Service did not credit the destination account. */
  CREDIT,
  /** Credit Service did not apply the payment. */
  PAYMENT
}
