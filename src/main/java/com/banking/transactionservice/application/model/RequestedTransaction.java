package com.banking.transactionservice.application.model;

import com.banking.transactionservice.domain.model.Transaction;
import java.util.Objects;

/**
 * What a request for a transaction returns.
 *
 * @param replayed {@code true} when the request repeated a recorded transaction and nothing new was
 *     started; the transaction is then the recorded one in its current state
 */
public record RequestedTransaction(Transaction transaction, boolean replayed) {

  public RequestedTransaction {
    Objects.requireNonNull(transaction, "transaction");
  }
}
