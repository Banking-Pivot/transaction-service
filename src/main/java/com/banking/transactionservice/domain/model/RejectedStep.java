package com.banking.transactionservice.domain.model;

/** The step of a transaction a service refused. */
public enum RejectedStep {
  /** Account Service did not debit the account. */
  DEBIT,
  /** Credit Service did not apply the payment. */
  PAYMENT
}
