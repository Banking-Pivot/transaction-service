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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Row of {@code transactions}. Enums are stored by name. {@code version} is managed by the domain
 * and checked by the adapter on every update. The accounts a card payment may charge are one text
 * column: their IDs in order, separated by commas.
 */
@Table("transactions")
public record TransactionRow(
    @Id UUID id,
    TransactionType transactionType,
    TransactionStatus status,
    UUID sourceCardId,
    UUID destinationCardId,
    UUID accountId,
    UUID destinationAccountId,
    String candidateAccountIds,
    UUID creditId,
    BigDecimal amount,
    CurrencyCode currency,
    String description,
    UUID reversalOperationId,
    BigDecimal fee,
    BigDecimal destinationFee,
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

  private static final String SEPARATOR = ",";

  static TransactionRow of(Transaction transaction) {
    Rejection rejection = transaction.getRejection();
    ReversalFailure failure = transaction.getReversalFailure();
    return new TransactionRow(
        transaction.getId(),
        transaction.getType(),
        transaction.getStatus(),
        transaction.getSourceCardId(),
        transaction.getDestinationCardId(),
        transaction.getAccountId(),
        transaction.getDestinationAccountId(),
        joined(transaction.getCandidateAccountIds()),
        transaction.getCreditId(),
        transaction.getAmount(),
        transaction.getCurrency(),
        transaction.getDescription(),
        transaction.getReversalOperationId(),
        transaction.getFee(),
        transaction.getDestinationFee(),
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

  /** The accounts as they are stored, or null when there are none. */
  static String joined(List<UUID> accountIds) {
    return accountIds.isEmpty()
        ? null
        : accountIds.stream().map(UUID::toString).collect(Collectors.joining(SEPARATOR));
  }

  Transaction toTransaction() {
    return Transaction.builder()
        .id(id)
        .type(transactionType)
        .status(status)
        .sourceCardId(sourceCardId)
        .destinationCardId(destinationCardId)
        .accountId(accountId)
        .destinationAccountId(destinationAccountId)
        .candidateAccountIds(
            candidateAccountIds == null
                ? List.of()
                : Arrays.stream(candidateAccountIds.split(SEPARATOR))
                    .map(UUID::fromString)
                    .toList())
        .creditId(creditId)
        .amount(amount)
        .currency(currency)
        .description(description)
        .reversalOperationId(reversalOperationId)
        .fee(fee)
        .destinationFee(destinationFee)
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
