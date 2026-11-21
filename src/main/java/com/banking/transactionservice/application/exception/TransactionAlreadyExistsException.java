package com.banking.transactionservice.application.exception;

import java.util.UUID;

/** Another request stored a transaction with the same ID first. */
public class TransactionAlreadyExistsException extends RuntimeException {

  public TransactionAlreadyExistsException(UUID transactionId, Throwable cause) {
    super("Transaction " + transactionId + " already exists.", cause);
  }
}
