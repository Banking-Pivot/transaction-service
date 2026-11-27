package com.banking.transactionservice.application.handler.command;

import com.banking.transactionservice.application.command.RequestCardPaymentCommand;
import com.banking.transactionservice.application.model.RequestedTransaction;
import com.banking.transactionservice.application.port.in.RequestCardPaymentUseCase;
import com.banking.transactionservice.application.port.out.TransactionStorePort;
import com.banking.transactionservice.domain.model.CardPaymentLimits;
import com.banking.transactionservice.domain.model.Transaction;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * Records a payment with a debit card and asks Card Service which accounts the card uses, once per
 * operationId. See {@link TransactionStarter} for what "once" means.
 *
 * <p>A new payment has to fit the limits of the card first: the largest single payment, and the sum
 * of the day. The day's payments are read and added here, because the store is queried with
 * criteria and those cannot add in the database. A repeat of a recorded operationId is answered
 * without checking the limits again.
 *
 * <p>The check and the insert are not one step. Two payments of one card that arrive together can
 * both read the same sum and both be accepted, so a day can end above its limit by the payments
 * that were being requested at the same moment.
 */
public class RequestCardPaymentHandler implements RequestCardPaymentUseCase {

  private final TransactionStorePort transactions;
  private final TransactionStarter starter;
  private final CardPaymentLimits limits;
  private final Clock clock;
  private final Supplier<UUID> operationIds;

  /**
   * Creates the handler.
   *
   * @param limits most a card can pay at once and in a day
   * @param operationIds source of the reversal operationId every transaction keeps
   */
  public RequestCardPaymentHandler(
      TransactionStorePort transactions,
      CommandDispatcher dispatcher,
      CardPaymentLimits limits,
      Clock clock,
      Supplier<UUID> operationIds) {
    this.transactions = Objects.requireNonNull(transactions);
    this.limits = Objects.requireNonNull(limits);
    this.starter = new TransactionStarter(transactions, dispatcher);
    this.clock = Objects.requireNonNull(clock);
    this.operationIds = Objects.requireNonNull(operationIds);
  }

  @Override
  public Mono<RequestedTransaction> execute(RequestCardPaymentCommand command) {
    return Mono.fromCallable(() -> requested(command))
        .flatMap(payment -> starter.start(payment, Mono.defer(() -> withinLimits(payment))));
  }

  private Mono<Void> withinLimits(Transaction payment) {
    limits.checkPayment(payment.getAmount());
    Instant requestedAt = payment.getCreatedAt();
    return transactions
        .findCountedCardPayments(
            payment.getSourceCardId(), limits.dayStart(requestedAt), limits.dayEnd(requestedAt))
        .map(Transaction::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .doOnNext(countedToday -> limits.checkDay(countedToday, payment.getAmount()))
        .then();
  }

  private Transaction requested(RequestCardPaymentCommand command) {
    return Transaction.cardPayment(
        command.operationId(),
        command.cardId(),
        command.amount(),
        command.currency(),
        command.description(),
        operationIds.get(),
        command.correlationId(),
        clock.instant());
  }
}
