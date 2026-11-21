package com.banking.transactionservice.application.handler.query;

import com.banking.transactionservice.application.exception.TransactionNotFoundException;
import com.banking.transactionservice.application.port.in.GetTransactionUseCase;
import com.banking.transactionservice.application.port.out.TransactionStorePort;
import com.banking.transactionservice.domain.model.Transaction;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Reads a transaction by its ID. */
public class GetTransactionHandler implements GetTransactionUseCase {

  private final TransactionStorePort transactions;

  public GetTransactionHandler(TransactionStorePort transactions) {
    this.transactions = Objects.requireNonNull(transactions);
  }

  @Override
  public Mono<Transaction> execute(UUID transactionId) {
    return transactions
        .findById(transactionId)
        .switchIfEmpty(Mono.error(() -> new TransactionNotFoundException(transactionId)));
  }
}
