package com.banking.transactionservice.domain.model;

import java.util.List;

/**
 * How far a transaction got.
 *
 * <p>The three PENDING statuses wait for the answer of a service; the other four are final.
 */
public enum TransactionStatus {
  /** Account Service was asked to debit the account. */
  PENDING_DEBIT(false),
  /** The account was debited and Credit Service was asked to apply the payment. */
  PENDING_PAYMENT(false),
  /** Credit Service rejected the payment and Account Service was asked to return the debit. */
  PENDING_REVERSAL(false),
  /** The account was debited and the credit product was paid. */
  COMPLETED(true),
  /** The account could not be debited. No money moved. */
  REJECTED(true),
  /** The payment was rejected and the debit went back to the account. */
  REVERSED(true),
  /** The payment was rejected and the debit could not be returned. Needs the bank's attention. */
  REVERSAL_FAILED(true);

  private final boolean finished;

  TransactionStatus(boolean finished) {
    this.finished = finished;
  }

  /** Whether the transaction ended and nothing else will happen to it. */
  public boolean isFinal() {
    return finished;
  }

  /** The statuses that wait for the answer of a service. */
  public static List<TransactionStatus> waiting() {
    return List.of(PENDING_DEBIT, PENDING_PAYMENT, PENDING_REVERSAL);
  }
}
