package com.banking.transactionservice.support;

import com.banking.transactionservice.application.exception.TransactionAlreadyExistsException;
import com.banking.transactionservice.application.exception.TransactionConcurrentModificationException;
import com.banking.transactionservice.application.model.ResultPage;
import com.banking.transactionservice.application.port.out.TransactionStorePort;
import com.banking.transactionservice.application.query.ListTransactionsQuery;
import com.banking.transactionservice.domain.model.Transaction;
import com.banking.transactionservice.domain.model.enums.TransactionStatus;
import com.banking.transactionservice.domain.model.enums.TransactionType;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The transaction store in memory, for unit tests of the use cases. It keeps the two rules of the
 * real one: an ID is stored once, and an update needs the version the transaction was read with.
 */
public final class InMemoryTransactionStore implements TransactionStorePort {

  private final Map<UUID, Transaction> rows = new LinkedHashMap<>();
  private Transaction rival;
  private int conflicts;

  /** The next insert finds that another request stored this transaction a moment before. */
  public void anotherRequestStoresFirst(Transaction stored) {
    rival = stored;
  }

  /** The next updates find that something else changed the transaction first. */
  public void failNextUpdates(int times) {
    conflicts = times;
  }

  public void put(Transaction transaction) {
    rows.put(transaction.getId(), transaction);
  }

  public Transaction get(UUID transactionId) {
    return rows.get(transactionId);
  }

  @Override
  public Mono<Void> insert(Transaction transaction) {
    return Mono.defer(
        () -> {
          if (rival != null) {
            rows.put(rival.getId(), rival);
            rival = null;
          }
          if (rows.containsKey(transaction.getId())) {
            return Mono.error(new TransactionAlreadyExistsException(transaction.getId(), null));
          }
          rows.put(transaction.getId(), transaction);
          return Mono.empty();
        });
  }

  @Override
  public Mono<Transaction> findById(UUID transactionId) {
    return Mono.defer(() -> Mono.justOrEmpty(rows.get(transactionId)));
  }

  @Override
  public Mono<Void> update(Transaction changed, long expectedVersion) {
    return Mono.defer(
        () -> {
          Transaction current = rows.get(changed.getId());
          if (conflicts > 0) {
            conflicts--;
            return Mono.error(new TransactionConcurrentModificationException(changed.getId()));
          }
          if (current == null || current.getVersion() != expectedVersion) {
            return Mono.error(new TransactionConcurrentModificationException(changed.getId()));
          }
          rows.put(changed.getId(), changed);
          return Mono.empty();
        });
  }

  @Override
  public Flux<Transaction> findWaitingSince(Instant sentBefore, int limit) {
    return Flux.defer(
        () ->
            Flux.fromIterable(
                rows.values().stream()
                    .filter(transaction -> !transaction.getStatus().isFinal())
                    .filter(transaction -> transaction.getCommandSentAt().isBefore(sentBefore))
                    .sorted(Comparator.comparing(Transaction::getCommandSentAt))
                    .limit(limit)
                    .toList()));
  }

  @Override
  public Flux<Transaction> findLatestCompletedByCard(UUID cardId, int limit) {
    return Flux.defer(
        () ->
            Flux.fromIterable(
                rows.values().stream()
                    .filter(transaction -> transaction.getStatus() == TransactionStatus.COMPLETED)
                    .filter(
                        transaction ->
                            cardId.equals(transaction.getSourceCardId())
                                || cardId.equals(transaction.getDestinationCardId()))
                    .sorted(Comparator.comparing(Transaction::getUpdatedAt).reversed())
                    .limit(limit)
                    .toList()));
  }

  @Override
  public Flux<Transaction> findCountedCardPayments(UUID cardId, Instant from, Instant until) {
    return Flux.defer(
        () ->
            Flux.fromIterable(
                rows.values().stream()
                    .filter(transaction -> transaction.getType() == TransactionType.CARD_PAYMENT)
                    .filter(transaction -> cardId.equals(transaction.getSourceCardId()))
                    .filter(transaction -> !transaction.getCreatedAt().isBefore(from))
                    .filter(transaction -> transaction.getCreatedAt().isBefore(until))
                    .filter(transaction -> transaction.getStatus() != TransactionStatus.REJECTED)
                    .filter(transaction -> transaction.getStatus() != TransactionStatus.REVERSED)
                    .toList()));
  }

  @Override
  public Mono<ResultPage<Transaction>> findPage(ListTransactionsQuery query) {
    List<Transaction> all = List.copyOf(rows.values());
    return Mono.just(ResultPage.of(all, query.pageRequest(), all.size()));
  }
}
