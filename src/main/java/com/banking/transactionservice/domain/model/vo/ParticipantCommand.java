package com.banking.transactionservice.domain.model.vo;

import com.banking.transactionservice.domain.model.enums.CurrencyCode;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a transaction asks of the service that owns one of its steps. Every command that moves money
 * carries the operationId its effect is recorded under in that service, so sending it again never
 * moves money twice.
 */
public sealed interface ParticipantCommand {

  /** The transaction the command belongs to. */
  UUID transactionId();

  /** Asks Card Service which accounts a debit card uses. It changes nothing there. */
  record ResolveCard(UUID transactionId, UUID cardId) implements ParticipantCommand {}

  /** Asks Account Service to take the amount from the account, as a withdrawal. */
  record DebitAccount(
      UUID transactionId,
      UUID operationId,
      UUID accountId,
      BigDecimal amount,
      CurrencyCode currency,
      String description)
      implements ParticipantCommand {}

  /** Asks Account Service to put the amount in the account, as a deposit. */
  record CreditAccount(
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
