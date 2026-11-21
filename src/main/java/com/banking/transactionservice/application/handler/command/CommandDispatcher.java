package com.banking.transactionservice.application.handler.command;

import com.banking.transactionservice.application.port.out.CommandPublisherPort;
import com.banking.transactionservice.domain.model.Transaction;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Sends the command of the step a transaction is in, after the transaction was stored in that step.
 *
 * <p>The stored transaction is what guarantees the command: when sending fails, nothing is lost,
 * because {@link ResendPendingCommandsHandler} finds the transaction still waiting and sends the
 * command again. So a failure here is logged and not passed on.
 */
public class CommandDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(CommandDispatcher.class);

  private final CommandPublisherPort publisher;

  public CommandDispatcher(CommandPublisherPort publisher) {
    this.publisher = Objects.requireNonNull(publisher);
  }

  /**
   * Sends the command the transaction is waiting on, if it is waiting on one.
   *
   * @return completion, also when the command could not be sent
   */
  public Mono<Void> dispatch(Transaction transaction) {
    return Mono.justOrEmpty(transaction.nextCommand())
        .flatMap(command -> publisher.publish(command, transaction.getCorrelationId()))
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
            });
  }
}
