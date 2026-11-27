package com.banking.transactionservice.application.model;

import com.banking.transactionservice.domain.model.Transaction;
import com.banking.transactionservice.domain.model.enums.CurrencyCode;
import com.banking.transactionservice.domain.model.enums.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One completed transaction as a movement of a debit card.
 *
 * @param direction whether the money left the card's account or entered it
 * @param accountId account of the card the money left from or entered
 * @param fee movement fee that account paid
 * @param occurredAt when the transaction completed
 */
public record CardMovement(
    UUID transactionId,
    TransactionType type,
    Direction direction,
    UUID accountId,
    BigDecimal amount,
    BigDecimal fee,
    CurrencyCode currency,
    String description,
    Instant occurredAt) {

  /** Which way the money went, seen from the card. */
  public enum Direction {
    DEBIT,
    CREDIT
  }

  /**
   * The movement a completed transaction is for one of its cards.
   *
   * @param cardId the source card or the destination card of the transaction
   */
  public static CardMovement of(Transaction transaction, UUID cardId) {
    boolean debit = cardId.equals(transaction.getSourceCardId());
    return new CardMovement(
        transaction.getId(),
        transaction.getType(),
        debit ? Direction.DEBIT : Direction.CREDIT,
        debit ? transaction.getAccountId() : transaction.getDestinationAccountId(),
        transaction.getAmount(),
        debit ? transaction.getFee() : transaction.getDestinationFee(),
        transaction.getCurrency(),
        transaction.getDescription(),
        transaction.getUpdatedAt());
  }
}
