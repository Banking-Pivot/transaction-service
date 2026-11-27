package com.banking.transactionservice.infrastructure.adapter.in.kafka;

import com.banking.transactionservice.domain.model.vo.ParticipantReply;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * An answer of Account Service, Credit Service or Card Service as it travels in Kafka: one JSON
 * object whose {@code type} says what happened. Each type uses some of the fields; fields this
 * service does not know are ignored, so the other services can add data without breaking it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReplyMessage(
    String type,
    UUID transactionId,
    UUID operationId,
    UUID cardId,
    UUID mainAccountId,
    List<UUID> accountIds,
    BigDecimal fee,
    BigDecimal balanceAfter,
    BigDecimal debtAfter,
    String code,
    String message) {

  /**
   * The answer in the terms of the domain.
   *
   * @throws IllegalArgumentException when the type is missing or unknown
   * @throws NullPointerException when a field the type needs is missing
   */
  ParticipantReply toReply() {
    if (type == null) {
      throw new IllegalArgumentException("The reply has no type.");
    }
    return switch (type) {
      case "CARD_RESOLVED" ->
          new ParticipantReply.CardResolved(transactionId, cardId, mainAccountId, accountIds);
      case "CARD_RESOLUTION_REJECTED" ->
          new ParticipantReply.CardResolutionRejected(transactionId, cardId, code, message);
      case "ACCOUNT_DEBITED" ->
          new ParticipantReply.AccountDebited(transactionId, operationId, fee, balanceAfter);
      case "ACCOUNT_DEBIT_REJECTED" ->
          new ParticipantReply.AccountDebitRejected(transactionId, operationId, code, message);
      case "ACCOUNT_CREDITED" -> new ParticipantReply.AccountCredited(transactionId, fee);
      case "ACCOUNT_CREDIT_REJECTED" ->
          new ParticipantReply.AccountCreditRejected(transactionId, code, message);
      case "CREDIT_PAID" -> new ParticipantReply.CreditPaid(transactionId, debtAfter);
      case "CREDIT_PAYMENT_REJECTED" ->
          new ParticipantReply.CreditPaymentRejected(transactionId, code, message);
      case "DEBIT_REVERSED" -> new ParticipantReply.DebitReversed(transactionId, balanceAfter);
      case "DEBIT_REVERSAL_REJECTED" ->
          new ParticipantReply.DebitReversalRejected(transactionId, code, message);
      default -> throw new IllegalArgumentException("Unknown reply type.");
    };
  }
}
