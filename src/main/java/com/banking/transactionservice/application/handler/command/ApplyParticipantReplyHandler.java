package com.banking.transactionservice.application.handler.command;

import com.banking.transactionservice.application.exception.TransactionConcurrentModificationException;
import com.banking.transactionservice.application.port.in.ApplyParticipantReplyUseCase;
import com.banking.transactionservice.application.port.out.TransactionStorePort;
import com.banking.transactionservice.domain.model.Transaction;
import com.banking.transactionservice.domain.model.vo.ParticipantReply;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Moves a transaction with the answer of a service and sends what follows: the commands of the next
 * step, or the outcome when the transaction ended and Yanki Service is waiting for it.
 *
 * <p>Answers can arrive twice (a command that was sent again is answered again) and late. The
 * transaction decides: an answer it is not waiting for changes nothing. The change is written only
 * if the transaction is still as it was read; when the periodic re-send touched it in between, the
 * answer is applied again on the fresh transaction.
 */
public class ApplyParticipantReplyHandler implements ApplyParticipantReplyUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(ApplyParticipantReplyHandler.class);
  private static final int ATTEMPTS_ON_CONFLICT = 3;

  private final TransactionStorePort transactions;
  private final CommandDispatcher dispatcher;
  private final Clock clock;

  /** Creates the handler with the store, the sender of the next command and the clock. */
  public ApplyParticipantReplyHandler(
      TransactionStorePort transactions, CommandDispatcher dispatcher, Clock clock) {
    this.transactions = Objects.requireNonNull(transactions);
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public Mono<Void> execute(ParticipantReply reply) {
    return Mono.defer(() -> applyOnce(reply))
        .retryWhen(
            Retry.max(ATTEMPTS_ON_CONFLICT)
                .filter(TransactionConcurrentModificationException.class::isInstance)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
  }

  private Mono<Void> applyOnce(ParticipantReply reply) {
    return transactions
        .findById(reply.transactionId())
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .flatMap(
            found ->
                found
                    .map(transaction -> apply(reply, transaction))
                    .orElseGet(() -> unknown(reply)));
  }

  private Mono<Void> apply(ParticipantReply reply, Transaction transaction) {
    Optional<Transaction> changed = reply.applyTo(transaction, clock.instant());
    if (changed.isEmpty()) {
      LOG.info(
          "Reply ignored, the transaction is not waiting for it. transactionId={}, status={}, "
              + "reply={}",
          transaction.getId(),
          transaction.getStatus(),
          reply.getClass().getSimpleName());
      return Mono.empty();
    }
    Transaction next = changed.get();
    return transactions
        .update(next, transaction.getVersion())
        .doOnSuccess(
            done ->
                LOG.info(
                    "Transaction moved. transactionId={}, from={}, to={}",
                    next.getId(),
                    transaction.getStatus(),
                    next.getStatus()))
        .then(
            Mono.defer(
                () -> next.advancedFrom(transaction) ? dispatcher.dispatch(next) : Mono.empty()));
  }

  private static Mono<Void> unknown(ParticipantReply reply) {
    LOG.warn(
        "Reply ignored, the transaction does not exist. transactionId={}, reply={}",
        reply.transactionId(),
        reply.getClass().getSimpleName());
    return Mono.empty();
  }
}
