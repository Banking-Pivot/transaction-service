package com.banking.transactionservice.application.port.in;

import com.banking.transactionservice.application.model.ResultPage;
import com.banking.transactionservice.application.query.ListTransactionsQuery;
import com.banking.transactionservice.domain.model.Transaction;
import reactor.core.publisher.Mono;

/** Lists transactions with bounded pagination. */
public interface ListTransactionsUseCase {

  Mono<ResultPage<Transaction>> execute(ListTransactionsQuery query);
}
