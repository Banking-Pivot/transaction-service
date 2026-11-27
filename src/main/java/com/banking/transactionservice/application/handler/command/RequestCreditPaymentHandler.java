package com.banking.transactionservice.application.handler.command;

import com.banking.transactionservice.application.command.RequestCreditPaymentCommand;
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
 * Records a credit payment and asks Account Service for the debit, once per operationId. See {@link
 * TransactionStarter} for what "once" means.
 */
public class RequestCreditPaymentHandler implements RequestCreditPaymentUseCase {

  private final TransactionStarter starter;
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
    this.starter = new TransactionStarter(transactions, dispatcher);
    this.clock = Objects.requireNonNull(clock);
    this.operationIds = Objects.requireNonNull(operationIds);
  }

  @Override
  public Mono<RequestedTransaction> execute(RequestCreditPaymentCommand command) {
    return Mono.fromCallable(() -> requested(command)).flatMap(starter::start);
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
}
