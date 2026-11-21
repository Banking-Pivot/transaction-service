package com.banking.transactionservice.application.handler.command;

import com.banking.transactionservice.application.exception.TransactionConcurrentModificationException;
import com.banking.transactionservice.application.port.in.ResendPendingCommandsUseCase;
import com.banking.transactionservice.application.port.out.TransactionStorePort;
import com.banking.transactionservice.domain.model.Transaction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Sends again the command of every transaction that has waited too long for an answer.
 *
 * <p>This is what makes a transaction finish when a message is lost on the way: the command never
 * reached the broker, the service that had to answer was down, or its answer could not be stored
 * here. Every command is idempotent in the service that receives it, so a repeat is answered with
 * the result of the first one and no money moves twice. A transaction is sent again for as long as
 * it waits; each time is counted and logged.
 */
public class ResendPendingCommandsHandler implements ResendPendingCommandsUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(ResendPendingCommandsHandler.class);

  private final TransactionStorePort transactions;
  private final CommandDispatcher dispatcher;
  private final Clock clock;
  private final Duration resendAfter;
  private final int batchSize;

  /**
   * Creates the handler.
   *
   * @param resendAfter how long a command waits for its answer before it is sent again
   * @param batchSize most transactions handled in one run
   */
  public ResendPendingCommandsHandler(
      TransactionStorePort transactions,
      CommandDispatcher dispatcher,
      Clock clock,
      Duration resendAfter,
      int batchSize) {
    this.transactions = Objects.requireNonNull(transactions);
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.clock = Objects.requireNonNull(clock);
    this.resendAfter = Objects.requireNonNull(resendAfter);
    this.batchSize = batchSize;
  }

  @Override
  public Mono<Long> execute() {
    return Mono.fromSupplier(clock::instant)
        .flatMap(
            now ->
                transactions
                    .findWaitingSince(now.minus(resendAfter), batchSize)
                    .concatMap(transaction -> resend(transaction, now))
                    .count());
  }

  // The new send time is written first: if an answer arrived in the meantime the write fails and
  // there is nothing to send again.
  private Mono<Transaction> resend(Transaction transaction, Instant now) {
    Transaction marked = transaction.commandResent(now);
    return transactions
        .update(marked, transaction.getVersion())
        .then(
            Mono.fromRunnable(
                () ->
                    LOG.warn(
                        "No answer in time, sending the command again. corrId={}, "
                            + "transactionId={}, status={}, attempt={}",
                        marked.getCorrelationId(),
                        marked.getId(),
                        marked.getStatus(),
                        marked.getCommandAttempts())))
        .then(dispatcher.dispatch(marked))
        .thenReturn(marked)
        .onErrorResume(TransactionConcurrentModificationException.class, moved -> Mono.empty());
  }
}
