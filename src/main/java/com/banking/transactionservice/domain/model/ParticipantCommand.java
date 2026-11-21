package com.banking.transactionservice.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a transaction asks of the service that owns the money of one step. Every command carries the
 * operationId its effect is recorded under in that service, so sending it again never moves money
 * twice.
 */
public sealed interface ParticipantCommand {

  /** The transaction the command belongs to. */
  UUID transactionId();

  /** Key the receiving service records the effect of the command under. */
  UUID operationId();

  /** Asks Account Service to take the amount from the account, as a withdrawal. */
  record DebitAccount(
      UUID transactionId,
      UUID operationId,
      UUID accountId,
      BigDecimal amount,
      CurrencyCode currency,
      String description)
      implements ParticipantCommand {}

  /** Asks Credit Service to apply the amount to the debt of the credit product. */
  record PayCredit(
      UUID transactionId,
      UUID operationId,
      UUID creditId,
      BigDecimal amount,
      CurrencyCode currency,
      String description)
      implements ParticipantCommand {}

  /**
   * Asks Account Service to return to the account the amount and the fee of a debit.
   *
   * @param originalOperationId operationId the debit was recorded under
   */
  record ReverseDebit(
      UUID transactionId,
      UUID operationId,
      UUID originalOperationId,
      UUID accountId,
      String description)
      implements ParticipantCommand {}
}
