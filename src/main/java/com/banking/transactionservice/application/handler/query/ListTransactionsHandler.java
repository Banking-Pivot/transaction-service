package com.banking.transactionservice.application.handler.query;

import com.banking.transactionservice.application.model.ResultPage;
import com.banking.transactionservice.application.port.in.ListTransactionsUseCase;
import com.banking.transactionservice.application.port.out.TransactionStorePort;
import com.banking.transactionservice.application.query.ListTransactionsQuery;
import com.banking.transactionservice.domain.model.Transaction;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Lists transactions with their filters, newest first. */
public class ListTransactionsHandler implements ListTransactionsUseCase {

  private final TransactionStorePort transactions;

  public ListTransactionsHandler(TransactionStorePort transactions) {
    this.transactions = Objects.requireNonNull(transactions);
  }

  @Override
  public Mono<ResultPage<Transaction>> execute(ListTransactionsQuery query) {
    return transactions.findPage(query);
  }
}
