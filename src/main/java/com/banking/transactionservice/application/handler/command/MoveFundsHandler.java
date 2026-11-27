package com.banking.transactionservice.application.handler.command;

import com.banking.transactionservice.application.command.MoveFundsCommand;
import com.banking.transactionservice.application.exception.OperationIdConflictException;
import com.banking.transactionservice.application.model.RequestedTransaction;
import com.banking.transactionservice.application.port.in.MoveFundsUseCase;
import com.banking.transactionservice.application.port.out.TransactionStorePort;
import com.banking.transactionservice.domain.exception.TransactionRuleViolationException;
import com.banking.transactionservice.domain.model.Transaction;
import com.banking.transactionservice.domain.model.enums.CurrencyCode;
import com.banking.transactionservice.domain.model.vo.FundsMovementOutcome;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Starts the movement of wallet funds Yanki Service asked for, once per transaction ID.
 *
 * <p>Yanki Service asks again for as long as it has no answer, so the same request arrives several
 * times:
 *
 * <ul>
 *   <li>The first one stores the transaction and asks Card Service about its cards. The answer to
 *       Yanki Service goes out when the transaction ends.
 *   <li>A repeat of a transaction still in progress does nothing.
 *   <li>A repeat of a transaction that ended is answered again with its outcome: the first answer
 *       may never have arrived.
 *   <li>The same ID with other data is answered {@code OPERATION_ID_CONFLICT}, and a request that
 *       cannot be accepted {@code INVALID_REQUEST}. Neither stores anything.
 * </ul>
 */
public class MoveFundsHandler implements MoveFundsUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(MoveFundsHandler.class);

  private final TransactionStarter starter;
  private final CommandDispatcher dispatcher;
  private final Clock clock;
  private final Supplier<UUID> operationIds;

  /**
   * Creates the handler.
   *
   * @param operationIds source of the operationId a reversal would be recorded under
   */
  public MoveFundsHandler(
      TransactionStorePort transactions,
      CommandDispatcher dispatcher,
      Clock clock,
      Supplier<UUID> operationIds) {
    this.starter = new TransactionStarter(transactions, dispatcher);
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.clock = Objects.requireNonNull(clock);
    this.operationIds = Objects.requireNonNull(operationIds);
  }

  @Override
  public Mono<Void> execute(MoveFundsCommand command) {
    return Mono.fromCallable(() -> requested(command))
        .flatMap(starter::start)
        .flatMap(this::answerRepeat)
        .onErrorResume(
            OperationIdConflictException.class,
            conflict ->
                refuse(
                    command,
                    FundsMovementOutcome.Rejected.OPERATION_ID_CONFLICT,
                    "The transactionId was already used with different data."))
        .onErrorResume(
            TransactionRuleViolationException.class,
            invalid ->
                refuse(
                    command, FundsMovementOutcome.Rejected.INVALID_REQUEST, invalid.getMessage()));
  }

  private Transaction requested(MoveFundsCommand command) {
    return Transaction.walletPayment(
        command.transactionId(),
        command.sourceCardId(),
        command.destinationCardId(),
        command.amount(),
        CurrencyCode.of(command.currency()),
        command.description(),
        operationIds.get(),
        command.correlationId(),
        clock.instant());
  }

  private Mono<Void> answerRepeat(RequestedTransaction requested) {
    return requested.replayed() ? dispatcher.answer(requested.transaction()) : Mono.empty();
  }

  private Mono<Void> refuse(MoveFundsCommand command, String code, String message) {
    if (LOG.isWarnEnabled()) {
      LOG.warn(
          "Movement of funds refused. corrId={}, transactionId={}, code={}",
          command.correlationId(),
          command.transactionId(),
          code);
    }
    return dispatcher.answer(
        new FundsMovementOutcome.Rejected(command.transactionId(), code, message),
        command.correlationId());
  }
}
