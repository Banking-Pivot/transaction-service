package com.banking.transactionservice.application.handler.command;

import com.banking.transactionservice.application.exception.OperationIdConflictException;
import com.banking.transactionservice.application.exception.TransactionAlreadyExistsException;
import com.banking.transactionservice.application.model.RequestedTransaction;
import com.banking.transactionservice.application.port.out.TransactionStorePort;
import com.banking.transactionservice.domain.model.Transaction;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * Records a requested transaction and sends the commands of its first step, once per ID.
 *
 * <ol>
 *   <li>A recorded ID is answered with its transaction: same data is a repeat, other data a
 *       conflict. Nothing is checked or sent again.
 *   <li>Otherwise the rules of admission run, if the caller has any; a transaction that breaks one
 *       is refused and nothing is stored or sent.
 *   <li>Then the transaction is stored waiting for its first step, and only then the commands go
 *       out. The primary key decides between two requests that arrive together: the one that loses
 *       is answered with the transaction the winner stored.
 * </ol>
 */
public class TransactionStarter {

  private final TransactionStorePort transactions;
  private final CommandDispatcher dispatcher;

  /** Creates the starter with the store and the sender of the first commands. */
  public TransactionStarter(TransactionStorePort transactions, CommandDispatcher dispatcher) {
    this.transactions = Objects.requireNonNull(transactions);
    this.dispatcher = Objects.requireNonNull(dispatcher);
  }

  /**
   * Starts the transaction unless its ID is already recorded.
   *
   * @param requested the transaction as the request describes it, not stored yet
   * @return the stored transaction and whether it was there before, or {@link
   *     OperationIdConflictException} when the ID belongs to a transaction with other data
   */
  public Mono<RequestedTransaction> start(Transaction requested) {
    return start(requested, Mono.empty());
  }

  /**
   * Starts the transaction unless its ID is already recorded, and only if it is admitted.
   *
   * @param requested the transaction as the request describes it, not stored yet
   * @param admission rules a new transaction has to pass before anything is stored or sent; it
   *     fails with the rule that was broken. A recorded ID is answered without running it.
   * @return the stored transaction and whether it was there before, the failure of the admission,
   *     or {@link OperationIdConflictException} when the ID belongs to a transaction with other
   *     data
   */
  public Mono<RequestedTransaction> start(Transaction requested, Mono<Void> admission) {
    return transactions
        .findById(requested.getId())
        .map(recorded -> repeatOf(requested, recorded))
        .switchIfEmpty(Mono.defer(() -> admission.then(store(requested))))
        .onErrorResume(
            TransactionAlreadyExistsException.class,
            lostRace ->
                transactions
                    .findById(requested.getId())
                    .switchIfEmpty(Mono.error(lostRace))
                    .map(recorded -> repeatOf(requested, recorded)));
  }

  private Mono<RequestedTransaction> store(Transaction requested) {
    return transactions
        .insert(requested)
        .then(dispatcher.dispatch(requested))
        .thenReturn(new RequestedTransaction(requested, false));
  }

  private static RequestedTransaction repeatOf(Transaction requested, Transaction recorded) {
    if (!recorded.sameRequestAs(requested)) {
      throw new OperationIdConflictException(requested.getId());
    }
    return new RequestedTransaction(recorded, true);
  }
}
