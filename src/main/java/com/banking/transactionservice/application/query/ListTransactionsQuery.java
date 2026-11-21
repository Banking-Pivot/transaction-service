package com.banking.transactionservice.application.query;

import com.banking.transactionservice.domain.model.TransactionStatus;
import java.util.Objects;
import java.util.UUID;

/**
 * Lists transactions, newest first.
 *
 * @param accountId optional; the account that is debited
 * @param creditId optional; the credit product that is paid
 * @param status optional filter
 */
public record ListTransactionsQuery(
    PageRequest pageRequest, UUID accountId, UUID creditId, TransactionStatus status) {

  public ListTransactionsQuery {
    Objects.requireNonNull(pageRequest, "pageRequest");
  }
}
