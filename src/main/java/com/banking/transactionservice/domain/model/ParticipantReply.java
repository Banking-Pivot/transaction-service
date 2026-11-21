package com.banking.transactionservice.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The answer of Account Service or Credit Service to a command. Each kind of answer knows which
 * step of the transaction it moves.
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

  /** Account Service debited the account. */
  record AccountDebited(UUID transactionId, BigDecimal fee, BigDecimal balanceAfter)
      implements ParticipantReply {

    /** Requires the transaction, the fee charged and the balance after the debit. */
    public AccountDebited {
      Objects.requireNonNull(transactionId, "transactionId");
      Objects.requireNonNull(fee, "fee");
      Objects.requireNonNull(balanceAfter, "balanceAfter");
    }

    @Override
    public Optional<Transaction> applyTo(Transaction transaction, Instant now) {
      return transaction.debited(fee, balanceAfter, now);
    }
  }

  /** Account Service did not debit the account. */
  record AccountDebitRejected(UUID transactionId, String code, String message)
      implements ParticipantReply {

    public AccountDebitRejected {
      Objects.requireNonNull(transactionId, "transactionId");
    }

    @Override
    public Optional<Transaction> applyTo(Transaction transaction, Instant now) {
      return transaction.debitRejected(code, message, now);
    }
  }

  /** Credit Service applied the payment. */
  record CreditPaid(UUID transactionId, BigDecimal debtAfter) implements ParticipantReply {

    /** Requires the transaction and the debt left after the payment. */
    public CreditPaid {
      Objects.requireNonNull(transactionId, "transactionId");
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
      Objects.requireNonNull(transactionId, "transactionId");
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
      Objects.requireNonNull(transactionId, "transactionId");
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
      Objects.requireNonNull(transactionId, "transactionId");
    }

    @Override
    public Optional<Transaction> applyTo(Transaction transaction, Instant now) {
      return transaction.reversalRejected(code, message, now);
    }
  }
}
