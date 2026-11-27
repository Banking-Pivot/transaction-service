package com.banking.transactionservice.infrastructure.adapter.out.postgres;

import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

import com.banking.transactionservice.application.exception.TransactionAlreadyExistsException;
import com.banking.transactionservice.application.exception.TransactionConcurrentModificationException;
import com.banking.transactionservice.application.model.ResultPage;
import com.banking.transactionservice.application.port.out.TransactionStorePort;
import com.banking.transactionservice.application.query.ListTransactionsQuery;
import com.banking.transactionservice.domain.model.Transaction;
import com.banking.transactionservice.domain.model.TransactionStatus;
import com.banking.transactionservice.domain.model.TransactionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps transactions in PostgreSQL through Spring Data, with no SQL written by hand.
 *
 * <p>Every update is conditioned on the version the transaction was read with, so an answer and the
 * periodic re-send never overwrite each other: the one that arrives second is told the transaction
 * changed.
 */
@Component
public class PostgresTransactionAdapter implements TransactionStorePort {

  private static final String PRIMARY_KEY = "pk_transactions";
  private static final String STATUS = "status";
  private static final String COMMAND_SENT_AT = "commandSentAt";
  private static final String CREATED_AT = "createdAt";
  // A payment that moved no money, or gave it back, stops counting against the daily limit.
  private static final List<String> NOT_COUNTED =
      List.of(TransactionStatus.REJECTED.name(), TransactionStatus.REVERSED.name());

  private final R2dbcEntityTemplate template;

  public PostgresTransactionAdapter(R2dbcEntityTemplate template) {
    this.template = template;
  }

  @Override
  public Mono<Void> insert(Transaction transaction) {
    return template
        .insert(TransactionRow.of(transaction))
        .then()
        .onErrorMap(
            error -> PostgresErrors.isUniqueViolation(error, PRIMARY_KEY),
            error -> new TransactionAlreadyExistsException(transaction.getId(), error));
  }

  @Override
  public Mono<Transaction> findById(UUID transactionId) {
    return template
        .selectOne(query(where("id").is(transactionId)), TransactionRow.class)
        .map(TransactionRow::toTransaction);
  }

  @Override
  public Mono<Void> update(Transaction changed, long expectedVersion) {
    return template
        .update(TransactionRow.class)
        .matching(query(where("id").is(changed.getId()).and("version").is(expectedVersion)))
        .apply(changesOf(changed))
        .flatMap(
            rows ->
                rows == 1
                    ? Mono.<Void>empty()
                    : Mono.error(new TransactionConcurrentModificationException(changed.getId())));
  }

  @Override
  public Flux<Transaction> findWaitingSince(Instant sentBefore, int limit) {
    List<String> waiting = TransactionStatus.waiting().stream().map(Enum::name).toList();
    return template
        .select(
            query(where(STATUS).in(waiting).and(COMMAND_SENT_AT).lessThan(sentBefore))
                .sort(Sort.by(COMMAND_SENT_AT))
                .limit(limit),
            TransactionRow.class)
        .map(TransactionRow::toTransaction);
  }

  @Override
  public Mono<ResultPage<Transaction>> findPage(ListTransactionsQuery request) {
    Criteria filter = filterOf(request);
    return Mono.zip(
            template
                .select(
                    query(filter)
                        .sort(Sort.by(Sort.Order.desc(CREATED_AT), Sort.Order.asc("id")))
                        .offset(request.pageRequest().offset())
                        .limit(request.pageRequest().size()),
                    TransactionRow.class)
                .map(TransactionRow::toTransaction)
                .collectList(),
            template.count(query(filter), TransactionRow.class))
        .map(page -> ResultPage.of(page.getT1(), request.pageRequest(), page.getT2()));
  }

  @Override
  public Flux<Transaction> findLatestCompletedByCard(UUID cardId, int limit) {
    return template
        .select(
            query(where(STATUS).is(TransactionStatus.COMPLETED.name()).and(usingCard(cardId)))
                .sort(Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("id")))
                .limit(limit),
            TransactionRow.class)
        .map(TransactionRow::toTransaction);
  }

  @Override
  public Flux<Transaction> findCountedCardPayments(UUID cardId, Instant from, Instant until) {
    return template
        .select(
            query(
                where("transactionType")
                    .is(TransactionType.CARD_PAYMENT.name())
                    .and("sourceCardId")
                    .is(cardId)
                    .and(CREATED_AT)
                    .greaterThanOrEquals(from)
                    .and(CREATED_AT)
                    .lessThan(until)
                    .and(STATUS)
                    .notIn(NOT_COUNTED)),
            TransactionRow.class)
        .map(TransactionRow::toTransaction);
  }

  // The request data never changes. Result columns only go from empty to set, except the account
  // a card payment is trying, which moves on; a value that is still empty is left out of the
  // statement.
  private static Update changesOf(Transaction changed) {
    Update update =
        Update.update(STATUS, changed.getStatus().name())
            .set(COMMAND_SENT_AT, changed.getCommandSentAt())
            .set("commandAttempts", changed.getCommandAttempts())
            .set("updatedAt", changed.getUpdatedAt())
            .set("version", changed.getVersion());
    if (changed.getAccountId() != null) {
      update = update.set("accountId", changed.getAccountId());
    }
    if (changed.getDestinationAccountId() != null) {
      update = update.set("destinationAccountId", changed.getDestinationAccountId());
    }
    if (!changed.getCandidateAccountIds().isEmpty()) {
      update =
          update.set(
              "candidateAccountIds", TransactionRow.joined(changed.getCandidateAccountIds()));
    }
    if (changed.getFee() != null) {
      update = update.set("fee", changed.getFee());
    }
    if (changed.getDestinationFee() != null) {
      update = update.set("destinationFee", changed.getDestinationFee());
    }
    if (changed.getAccountBalanceAfter() != null) {
      update = update.set("accountBalanceAfter", changed.getAccountBalanceAfter());
    }
    if (changed.getDebtAfter() != null) {
      update = update.set("debtAfter", changed.getDebtAfter());
    }
    if (changed.getRejection() != null) {
      update =
          update
              .set("rejectionStep", changed.getRejection().step().name())
              .set("rejectionCode", changed.getRejection().code())
              .set("rejectionMessage", changed.getRejection().message());
    }
    if (changed.getReversalFailure() != null) {
      update =
          update
              .set("reversalFailureCode", changed.getReversalFailure().code())
              .set("reversalFailureMessage", changed.getReversalFailure().message());
    }
    return update;
  }

  private static Criteria filterOf(ListTransactionsQuery request) {
    List<Criteria> filters = new ArrayList<>();
    if (request.accountId() != null) {
      filters.add(where("accountId").is(request.accountId()));
    }
    if (request.creditId() != null) {
      filters.add(where("creditId").is(request.creditId()));
    }
    if (request.cardId() != null) {
      filters.add(Criteria.empty().and(usingCard(request.cardId())));
    }
    if (request.status() != null) {
      filters.add(where(STATUS).is(request.status().name()));
    }
    return Criteria.from(filters);
  }

  // The card on either side of the transaction. Used inside and(...), which keeps the two
  // alternatives together in parentheses.
  private static Criteria usingCard(UUID cardId) {
    return where("sourceCardId").is(cardId).or("destinationCardId").is(cardId);
  }
}
