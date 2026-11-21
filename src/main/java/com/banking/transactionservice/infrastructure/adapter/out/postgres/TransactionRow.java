package com.banking.transactionservice.infrastructure.adapter.out.postgres;

import com.banking.transactionservice.domain.model.CurrencyCode;
import com.banking.transactionservice.domain.model.RejectedStep;
import com.banking.transactionservice.domain.model.Rejection;
import com.banking.transactionservice.domain.model.ReversalFailure;
import com.banking.transactionservice.domain.model.Transaction;
import com.banking.transactionservice.domain.model.TransactionStatus;
import com.banking.transactionservice.domain.model.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Row of {@code transactions}. Enums are stored by name. {@code version} is managed by the domain
 * and checked by the adapter on every update.
 */
@Table("transactions")
public record TransactionRow(
    @Id UUID id,
    TransactionType transactionType,
    TransactionStatus status,
    UUID accountId,
    UUID creditId,
    BigDecimal amount,
    CurrencyCode currency,
    String description,
    UUID reversalOperationId,
    BigDecimal fee,
    BigDecimal accountBalanceAfter,
    BigDecimal debtAfter,
    RejectedStep rejectionStep,
    String rejectionCode,
    String rejectionMessage,
    String reversalFailureCode,
    String reversalFailureMessage,
    String correlationId,
    Instant commandSentAt,
    int commandAttempts,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  static TransactionRow of(Transaction transaction) {
    Rejection rejection = transaction.getRejection();
    ReversalFailure failure = transaction.getReversalFailure();
    return new TransactionRow(
        transaction.getId(),
        transaction.getType(),
        transaction.getStatus(),
        transaction.getAccountId(),
        transaction.getCreditId(),
        transaction.getAmount(),
        transaction.getCurrency(),
        transaction.getDescription(),
        transaction.getReversalOperationId(),
        transaction.getFee(),
        transaction.getAccountBalanceAfter(),
        transaction.getDebtAfter(),
        rejection == null ? null : rejection.step(),
        rejection == null ? null : rejection.code(),
        rejection == null ? null : rejection.message(),
        failure == null ? null : failure.code(),
        failure == null ? null : failure.message(),
        transaction.getCorrelationId(),
        transaction.getCommandSentAt(),
        transaction.getCommandAttempts(),
        transaction.getCreatedAt(),
        transaction.getUpdatedAt(),
        transaction.getVersion());
  }

  Transaction toTransaction() {
    return Transaction.builder()
        .id(id)
        .type(transactionType)
        .status(status)
        .accountId(accountId)
        .creditId(creditId)
        .amount(amount)
        .currency(currency)
        .description(description)
        .reversalOperationId(reversalOperationId)
        .fee(fee)
        .accountBalanceAfter(accountBalanceAfter)
        .debtAfter(debtAfter)
        .rejection(
            rejectionStep == null
                ? null
                : new Rejection(rejectionStep, rejectionCode, rejectionMessage))
        .reversalFailure(
            reversalFailureCode == null
                ? null
                : new ReversalFailure(reversalFailureCode, reversalFailureMessage))
        .correlationId(correlationId)
        .commandSentAt(commandSentAt)
        .commandAttempts(commandAttempts)
        .createdAt(createdAt)
        .updatedAt(updatedAt)
        .version(version)
        .build();
  }
}
