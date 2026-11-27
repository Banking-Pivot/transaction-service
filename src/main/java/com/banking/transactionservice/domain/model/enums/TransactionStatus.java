package com.banking.transactionservice.domain.model.enums;

import java.util.List;

/**
 * How far a transaction got.
 *
 * <p>The five PENDING statuses wait for the answer of a service; the other four are final.
 */
public enum TransactionStatus {
  /** Card Service was asked which accounts the debit card, or cards, of the transaction use. */
  PENDING_CARD(false),
  /** Account Service was asked to debit the account. */
  PENDING_DEBIT(false),
  /** Account Service was asked to credit the destination account. */
  PENDING_CREDIT(false),
  /** The account was debited and Credit Service was asked to apply the payment. */
  PENDING_PAYMENT(false),
  /** The second step was rejected and Account Service was asked to return the debit. */
  PENDING_REVERSAL(false),
  /** Every step of the transaction was done. */
  COMPLETED(true),
  /** A step was refused and no money stayed moved. */
  REJECTED(true),
  /** The second step was rejected and the debit went back to the account. */
  REVERSED(true),
  /** The second step was rejected and the debit could not be returned. Needs attention. */
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
    return List.of(PENDING_CARD, PENDING_DEBIT, PENDING_CREDIT, PENDING_PAYMENT, PENDING_REVERSAL);
  }
}
