package com.banking.transactionservice.application.exception;

import java.util.UUID;

/** No transaction has the requested ID. */
public class TransactionNotFoundException extends RuntimeException {

  public TransactionNotFoundException(UUID transactionId) {
    super("Transaction " + transactionId + " does not exist.");
  }
}
