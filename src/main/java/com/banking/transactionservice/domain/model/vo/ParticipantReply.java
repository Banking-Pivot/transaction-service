package com.banking.transactionservice.domain.model.vo;

import com.banking.transactionservice.domain.model.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The answer of Account Service, Credit Service or Card Service to a command. Each kind of answer
 * knows which step of the transaction it moves.
 */
public interface ParticipantReply {

  /** The transaction the answer belongs to. */
  UUID transactionId();

  /**
   * Applies the answer to the transaction.
   *
   * @param transaction the transaction as it is stored
   * @param now instant of the change
   * @return the transaction after the answer, or empty when the transaction is not waiting for this
   *     answer (a repeat, or an answer that arrived late)
   */
  Optional<Transaction> applyTo(Transaction transaction, Instant now);

  // Every answer says which transaction it is about.
  private static void requireTransaction(UUID transactionId) {
    Objects.requireNonNull(transactionId, "transactionId");
  }

  /**
   * Card Service said which accounts a debit card uses.
   *
   * @param accountIds every linked account, main first; may be empty when only the main one counts
   */
  record CardResolved(UUID transactionId, UUID cardId, UUID mainAccountId, List<UUID> accountIds)
      implements ParticipantReply {

    /** Requires the transaction, the card and its main account. */
    public CardResolved {
      ParticipantReply.requireTransaction(transactionId);
      Objects.requireNonNull(cardId, "cardId");
      Objects.requireNonNull(mainAccountId, "mainAccountId");
      accountIds = accountIds == null ? List.of() : List.copyOf(accountIds);
    }

    @Override
    public Optional<Transaction> applyTo(Transaction transaction, Instant now) {
      return transaction.cardResolved(cardId, mainAccountId, accountIds, now);
    }
  }

  /** Card Service did not accept the debit card. */
  record CardResolutionRejected(UUID transactionId, UUID cardId, String code, String message)
      implements ParticipantReply {

    /** Requires the transaction and the card. */
    public CardResolutionRejected {
      ParticipantReply.requireTransaction(transactionId);
      Objects.requireNonNull(cardId, "cardId");
    }

    @Override
    public Optional<Transaction> applyTo(Transaction transaction, Instant now) {
      return transaction.cardRejected(cardId, code, message, now);
    }
  }

  /**
   * Account Service debited the account.
   *
   * @param operationId operationId of the debit that was answered, or null when the answer does not
   *     say; it tells apart the answers about the different accounts of a card
   */
  record AccountDebited(
      UUID transactionId, UUID operationId, BigDecimal fee, BigDecimal balanceAfter)
      implements ParticipantReply {

    /** Requires the transaction, the fee charged and the balance after the debit. */
    public AccountDebited {
      ParticipantReply.requireTransaction(transactionId);
      Objects.requireNonNull(fee, "fee");
      Objects.requireNonNull(balanceAfter, "balanceAfter");
    }

    /** An answer that does not say which debit it is about. */
    public AccountDebited(UUID transactionId, BigDecimal fee, BigDecimal balanceAfter) {
      this(transactionId, null, fee, balanceAfter);
    }

    @Override
    public Optional<Transaction> applyTo(Transaction transaction, Instant now) {
      return transaction.waitsForDebit(operationId)
          ? transaction.debited(fee, balanceAfter, now)
          : Optional.empty();
    }
  }

  /**
   * Account Service did not debit the account.
   *
   * @param operationId operationId of the debit that was answered, or null when the answer does not
   *     say
   */
  record AccountDebitRejected(UUID transactionId, UUID operationId, String code, String message)
      implements ParticipantReply {

    public AccountDebitRejected {
      ParticipantReply.requireTransaction(transactionId);
    }

    /** An answer that does not say which debit it is about. */
    public AccountDebitRejected(UUID transactionId, String code, String message) {
      this(transactionId, null, code, message);
    }

    @Override
    public Optional<Transaction> applyTo(Transaction transaction, Instant now) {
      return transaction.waitsForDebit(operationId)
          ? transaction.debitRejected(code, message, now)
          : Optional.empty();
    }
  }

  /**
   * Account Service credited the destination account.
   *
   * @param fee movement fee the account paid for the deposit; null counts as none
   */
  record AccountCredited(UUID transactionId, BigDecimal fee) implements ParticipantReply {

    public AccountCredited {
      ParticipantReply.requireTransaction(transactionId);
    }

    @Override
    public Optional<Transaction> applyTo(Transaction transaction, Instant now) {
      return transaction.credited(fee, now);
    }
  }

  /** Account Service did not credit the destination account. */
  record AccountCreditRejected(UUID transactionId, String code, String message)
      implements ParticipantReply {

    public AccountCreditRejected {
      ParticipantReply.requireTransaction(transactionId);
    }

    @Override
    public Optional<Transaction> applyTo(Transaction transaction, Instant now) {
      return transaction.creditRejected(code, message, now);
    }
  }

  /** Credit Service applied the payment. */
  record CreditPaid(UUID transactionId, BigDecimal debtAfter) implements ParticipantReply {

    /** Requires the transaction and the debt left after the payment. */
    public CreditPaid {
      ParticipantReply.requireTransaction(transactionId);
      Objects.requireNonNull(debtAfter, "debtAfter");
    }

    @Override
    public Optional<Transaction> applyTo(Transaction transaction, Instant now) {
      return transaction.paid(debtAfter, now);
    }
  }

  /** Credit Service did not apply the payment. */
  record CreditPaymentRejected(UUID transactionId, String code, String message)
      implements ParticipantReply {

    public CreditPaymentRejected {
      ParticipantReply.requireTransaction(transactionId);
    }

    @Override
    public Optional<Transaction> applyTo(Transaction transaction, Instant now) {
      return transaction.paymentRejected(code, message, now);
    }
  }

  /** Account Service returned the debit to the account. */
  record DebitReversed(UUID transactionId, BigDecimal balanceAfter) implements ParticipantReply {

    /** Requires the transaction and the balance after the reversal. */
    public DebitReversed {
      ParticipantReply.requireTransaction(transactionId);
      Objects.requireNonNull(balanceAfter, "balanceAfter");
    }

    @Override
    public Optional<Transaction> applyTo(Transaction transaction, Instant now) {
      return transaction.reversed(balanceAfter, now);
    }
  }

  /** Account Service could not return the debit to the account. */
  record DebitReversalRejected(UUID transactionId, String code, String message)
      implements ParticipantReply {

    public DebitReversalRejected {
      ParticipantReply.requireTransaction(transactionId);
    }

    @Override
    public Optional<Transaction> applyTo(Transaction transaction, Instant now) {
      return transaction.reversalRejected(code, message, now);
    }
  }
}
