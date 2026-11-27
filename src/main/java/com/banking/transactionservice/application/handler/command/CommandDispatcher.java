package com.banking.transactionservice.application.handler.command;

import com.banking.transactionservice.application.port.out.CommandPublisherPort;
import com.banking.transactionservice.application.port.out.OutcomePublisherPort;
import com.banking.transactionservice.domain.model.FundsMovementOutcome;
import com.banking.transactionservice.domain.model.Transaction;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Sends what follows from the state a transaction was just stored in: the commands of the step it
 * waits on or, when it ended and Yanki Service asked for it, its outcome.
 *
 * <p>The stored transaction is what guarantees the messages. When a command cannot be sent, nothing
 * is lost, because {@link ResendPendingCommandsHandler} finds the transaction still waiting and
 * sends it again. When an outcome cannot be sent, Yanki Service asks again and gets it then. So a
 * failure here is logged and not passed on.
 */
public class CommandDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(CommandDispatcher.class);

  private final CommandPublisherPort commands;
  private final OutcomePublisherPort outcomes;

  /** Creates the dispatcher with the senders of commands and of outcomes. */
  public CommandDispatcher(CommandPublisherPort commands, OutcomePublisherPort outcomes) {
    this.commands = Objects.requireNonNull(commands);
    this.outcomes = Objects.requireNonNull(outcomes);
  }

  /**
   * Sends the commands the transaction is waiting on, or its outcome once it ended.
   *
   * @return completion, also when something could not be sent
   */
  public Mono<Void> dispatch(Transaction transaction) {
    return Flux.fromIterable(transaction.nextCommands())
        .concatMap(command -> commands.publish(command, transaction.getCorrelationId()))
        .then()
        .onErrorResume(
            failure -> {
              LOG.warn(
                  "Command not sent, it will be sent again. corrId={}, transactionId={}, "
                      + "status={}, failureType={}",
                  transaction.getCorrelationId(),
                  transaction.getId(),
                  transaction.getStatus(),
                  failure.getClass().getName());
              return Mono.empty();
            })
        .then(Mono.defer(() -> answer(transaction)));
  }

  /**
   * Tells Yanki Service the outcome of the transaction, if it is a movement of wallet funds that
   * ended.
   *
   * @return completion, also when the outcome could not be sent
   */
  public Mono<Void> answer(Transaction transaction) {
    return Mono.justOrEmpty(transaction.outcome())
        .flatMap(outcome -> answer(outcome, transaction.getCorrelationId()));
  }

  /**
   * Tells Yanki Service an outcome.
   *
   * @return completion, also when the outcome could not be sent
   */
  public Mono<Void> answer(FundsMovementOutcome outcome, String correlationId) {
    return outcomes
        .publish(outcome, correlationId)
        .onErrorResume(
            failure -> {
              LOG.warn(
                  "Outcome not sent, it goes out when it is asked for again. corrId={}, "
                      + "transactionId={}, failureType={}",
                  correlationId,
                  outcome.transactionId(),
                  failure.getClass().getName());
              return Mono.empty();
            });
  }
}
