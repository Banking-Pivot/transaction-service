package com.banking.transactionservice.application.port.out;

import com.banking.transactionservice.application.model.ResultPage;
import com.banking.transactionservice.application.query.ListTransactionsQuery;
import com.banking.transactionservice.domain.model.Transaction;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where transactions are kept. */
public interface TransactionStorePort {

  /**
   * Stores a new transaction.
   *
   * @return completion, or {@link
   *     com.banking.transactionservice.application.exception.TransactionAlreadyExistsException}
   *     when its ID is taken
   */
  Mono<Void> insert(Transaction transaction);

  Mono<Transaction> findById(UUID transactionId);

  /**
   * Writes the changed transaction if it is still at the version it was read with.
   *
   * @param expectedVersion version of the transaction when it was read
   * @return completion, or {@code TransactionConcurrentModificationException} when something else
   *     changed it first
   */
  Mono<Void> update(Transaction changed, long expectedVersion);

  /**
   * Transactions still waiting for a service whose current command was sent before the given
   * instant, oldest first.
   */
  Flux<Transaction> findWaitingSince(Instant sentBefore, int limit);

  Mono<ResultPage<Transaction>> findPage(ListTransactionsQuery query);
}
