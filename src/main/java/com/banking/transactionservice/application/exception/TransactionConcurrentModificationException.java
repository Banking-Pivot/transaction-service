package com.banking.transactionservice.application.exception;

import java.util.UUID;

/** The transaction changed between the moment it was read and the moment it was written. */
public class TransactionConcurrentModificationException extends RuntimeException {

  public TransactionConcurrentModificationException(UUID transactionId) {
    super("Transaction " + transactionId + " changed while it was being updated.");
  }
}
