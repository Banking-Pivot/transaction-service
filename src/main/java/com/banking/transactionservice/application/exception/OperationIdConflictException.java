package com.banking.transactionservice.application.exception;

import java.util.UUID;

/** The operationId already identifies a transaction that asks for something else. */
public class OperationIdConflictException extends RuntimeException {

  public OperationIdConflictException(UUID operationId) {
    super("OperationId " + operationId + " was already used with different data.");
  }
}
