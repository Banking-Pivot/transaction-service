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
            query(where("status").in(waiting).and("commandSentAt").lessThan(sentBefore))
                .sort(Sort.by("commandSentAt"))
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
                        .sort(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")))
                        .offset(request.pageRequest().offset())
                        .limit(request.pageRequest().size()),
                    TransactionRow.class)
                .map(TransactionRow::toTransaction)
                .collectList(),
            template.count(query(filter), TransactionRow.class))
        .map(page -> ResultPage.of(page.getT1(), request.pageRequest(), page.getT2()));
  }

  // The request data never changes. Result columns only go from empty to set, so a value that is
  // still empty is left out of the statement.
  private static Update changesOf(Transaction changed) {
    Update update =
        Update.update("status", changed.getStatus().name())
            .set("commandSentAt", changed.getCommandSentAt())
            .set("commandAttempts", changed.getCommandAttempts())
            .set("updatedAt", changed.getUpdatedAt())
            .set("version", changed.getVersion());
    if (changed.getFee() != null) {
      update = update.set("fee", changed.getFee());
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
    if (request.status() != null) {
      filters.add(where("status").is(request.status().name()));
    }
    return Criteria.from(filters);
  }
}
