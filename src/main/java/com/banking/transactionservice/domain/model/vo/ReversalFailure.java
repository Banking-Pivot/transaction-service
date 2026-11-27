package com.banking.transactionservice.domain.model.vo;

/**
 * Why Account Service could not return a debit to the account.
 *
 * @param code error code of Account Service
 * @param message explanation of Account Service
 */
public record ReversalFailure(String code, String message) {

  /** Keeps the code and the message within what is stored. */
  public ReversalFailure {
    code = Rejection.limited(code, Rejection.MAX_CODE, "UNKNOWN");
    message = Rejection.limited(message, Rejection.MAX_MESSAGE, "No reason was given.");
  }
}
