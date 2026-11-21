package com.banking.transactionservice.application.handler.command;

import com.banking.transactionservice.application.command.RequestCreditPaymentCommand;
import com.banking.transactionservice.application.exception.OperationIdConflictException;
import com.banking.transactionservice.application.exception.TransactionAlreadyExistsException;
import com.banking.transactionservice.application.model.RequestedTransaction;
import com.banking.transactionservice.application.port.in.RequestCreditPaymentUseCase;
import com.banking.transactionservice.application.port.out.TransactionStorePort;
import com.banking.transactionservice.domain.model.Transaction;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * Records a credit payment and asks Account Service for the debit, once per operationId.
 *
 * <ol>
 *   <li>A recorded operationId is answered with its transaction: same data is a repeat, other data
 *       a conflict. Nothing is sent again.
 *   <li>Otherwise the transaction is stored waiting for the debit, and only then the command goes
 *       out. The primary key decides between two requests that arrive together: the one that loses
 *       is answered with the transaction the winner stored.
 * </ol>
 */
public class RequestCreditPaymentHandler implements RequestCreditPaymentUseCase {

  private final TransactionStorePort transactions;
  private final CommandDispatcher dispatcher;
  private final Clock clock;
  private final Supplier<UUID> operationIds;

  /**
   * Creates the handler.
   *
   * @param operationIds source of the operationId a reversal would be recorded under
   */
  public RequestCreditPaymentHandler(
      TransactionStorePort transactions,
      CommandDispatcher dispatcher,
      Clock clock,
      Supplier<UUID> operationIds) {
    this.transactions = Objects.requireNonNull(transactions);
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.clock = Objects.requireNonNull(clock);
    this.operationIds = Objects.requireNonNull(operationIds);
  }

  @Override
  public Mono<RequestedTransaction> execute(RequestCreditPaymentCommand command) {
    return Mono.fromCallable(() -> requested(command))
        .flatMap(
            requested ->
                transactions
                    .findById(requested.getId())
                    .map(recorded -> repeatOf(requested, recorded))
                    .switchIfEmpty(Mono.defer(() -> start(requested)))
                    .onErrorResume(
                        TransactionAlreadyExistsException.class,
                        lostRace ->
                            transactions
                                .findById(requested.getId())
                                .switchIfEmpty(Mono.error(lostRace))
                                .map(recorded -> repeatOf(requested, recorded))));
  }

  private Transaction requested(RequestCreditPaymentCommand command) {
    return Transaction.creditPayment(
        command.operationId(),
        command.accountId(),
        command.creditId(),
        command.amount(),
        command.currency(),
        command.description(),
        operationIds.get(),
        command.correlationId(),
        clock.instant());
  }

  private Mono<RequestedTransaction> start(Transaction requested) {
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
