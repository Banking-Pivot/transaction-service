package com.banking.transactionservice.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import lombok.Builder;
import lombok.Getter;

/**
 * One operation that moves money between products kept by different services, and how far it got.
 *
 * <p>A credit payment goes through its steps in this order, each one waiting for the answer of the
 * service that owns the money:
 *
 * <pre>
 * PENDING_DEBIT ──debited──▶ PENDING_PAYMENT ──paid──▶ COMPLETED
 *      │                          │
 *      └─rejected─▶ REJECTED      └─rejected─▶ PENDING_REVERSAL ──reversed──▶ REVERSED
 *                                                    └─rejected─▶ REVERSAL_FAILED
 * </pre>
 *
 * <p>An answer moves the transaction only from the status that waits for it. Any other answer, such
 * as a repeat, changes nothing. The transaction holds no balance: the amounts it keeps are the ones
 * the services reported.
 *
 * <p>Instances are immutable; every change returns a new one with the next {@code version}.
 */
@Getter
@Builder(toBuilder = true)
public final class Transaction {

  static final String REVERSAL_DESCRIPTION = "Credit payment not applied";

  /** The operationId of the request; also the operationId of the debit and of the payment. */
  private final UUID id;

  private final TransactionType type;
  private final TransactionStatus status;
  private final UUID accountId;
  private final UUID creditId;
  private final BigDecimal amount;
  private final CurrencyCode currency;
  private final String description;

  /** The operationId the reversal is recorded under in Account Service. */
  private final UUID reversalOperationId;

  /** Movement fee the account paid for the debit; empty until the account is debited. */
  private final BigDecimal fee;

  /** Balance of the account after the last movement this transaction made on it. */
  private final BigDecimal accountBalanceAfter;

  /** Debt of the credit product after the payment; empty until it is paid. */
  private final BigDecimal debtAfter;

  private final Rejection rejection;
  private final ReversalFailure reversalFailure;

  /** Correlation identifier of the request, written in every message of the transaction. */
  private final String correlationId;

  /** When the command of the current step was last sent. */
  private final Instant commandSentAt;

  /** How many times the command of the current step was sent again. */
  private final int commandAttempts;

  private final Instant createdAt;
  private final Instant updatedAt;
  private final long version;

  /**
   * A new payment of a credit product from an account, waiting for the debit.
   *
   * @param id the operationId of the request
   * @param description free text of the client; blank becomes none
   * @param reversalOperationId key a reversal would be recorded under, decided now so every repeat
   *     of the reversal command carries the same one
   * @throws com.banking.transactionservice.domain.exception.TransactionRuleViolationException when
   *     the amount is not positive, has more than two decimals or exceeds the maximum
   */
  public static Transaction creditPayment(
      UUID id,
      UUID accountId,
      UUID creditId,
      BigDecimal amount,
      CurrencyCode currency,
      String description,
      UUID reversalOperationId,
      String correlationId,
      Instant now) {
    return builder()
        .id(Objects.requireNonNull(id, "id"))
        .type(TransactionType.CREDIT_PAYMENT)
        .status(TransactionStatus.PENDING_DEBIT)
        .accountId(Objects.requireNonNull(accountId, "accountId"))
        .creditId(Objects.requireNonNull(creditId, "creditId"))
        .amount(Amounts.positive(amount))
        .currency(Objects.requireNonNull(currency, "currency"))
        .description(description == null || description.isBlank() ? null : description.strip())
        .reversalOperationId(Objects.requireNonNull(reversalOperationId, "reversalOperationId"))
        .correlationId(Objects.requireNonNull(correlationId, "correlationId"))
        .commandSentAt(Objects.requireNonNull(now, "now"))
        .commandAttempts(0)
        .createdAt(now)
        .updatedAt(now)
        .version(0)
        .build();
  }

  /** Whether the other transaction asks for exactly the same thing as this one. */
  public boolean sameRequestAs(Transaction other) {
    return type == other.type
        && accountId.equals(other.accountId)
        && creditId.equals(other.creditId)
        && amount.compareTo(other.amount) == 0
        && currency == other.currency
        && Objects.equals(description, other.description);
  }

  /**
   * The command the current step is waiting on, to send or to send again.
   *
   * @return empty once the transaction is final
   */
  public Optional<ParticipantCommand> nextCommand() {
    return switch (status) {
      case PENDING_DEBIT ->
          Optional.of(
              new ParticipantCommand.DebitAccount(
                  id, id, accountId, amount, currency, description));
      case PENDING_PAYMENT ->
          Optional.of(
              new ParticipantCommand.PayCredit(id, id, creditId, amount, currency, description));
      case PENDING_REVERSAL ->
          Optional.of(
              new ParticipantCommand.ReverseDebit(
                  id, reversalOperationId, id, accountId, REVERSAL_DESCRIPTION));
      default -> Optional.empty();
    };
  }

  /** The account was debited: the payment is next. */
  public Optional<Transaction> debited(
      BigDecimal chargedFee, BigDecimal balanceAfter, Instant now) {
    return from(
        TransactionStatus.PENDING_DEBIT,
        now,
        next ->
            next.status(TransactionStatus.PENDING_PAYMENT)
                .fee(chargedFee)
                .accountBalanceAfter(balanceAfter));
  }

  /** The account was not debited: the transaction ends and no money moved. */
  public Optional<Transaction> debitRejected(String code, String message, Instant now) {
    return from(
        TransactionStatus.PENDING_DEBIT,
        now,
        next ->
            next.status(TransactionStatus.REJECTED)
                .rejection(new Rejection(RejectedStep.DEBIT, code, message)));
  }

  /** The credit product was paid: the transaction ends well. */
  public Optional<Transaction> paid(BigDecimal remainingDebt, Instant now) {
    return from(
        TransactionStatus.PENDING_PAYMENT,
        now,
        next -> next.status(TransactionStatus.COMPLETED).debtAfter(remainingDebt));
  }

  /** The payment was not applied: the debit has to go back to the account. */
  public Optional<Transaction> paymentRejected(String code, String message, Instant now) {
    return from(
        TransactionStatus.PENDING_PAYMENT,
        now,
        next ->
            next.status(TransactionStatus.PENDING_REVERSAL)
                .rejection(new Rejection(RejectedStep.PAYMENT, code, message)));
  }

  /** The debit went back to the account: the transaction ends with the money where it was. */
  public Optional<Transaction> reversed(BigDecimal balanceAfter, Instant now) {
    return from(
        TransactionStatus.PENDING_REVERSAL,
        now,
        next -> next.status(TransactionStatus.REVERSED).accountBalanceAfter(balanceAfter));
  }

  /** The debit could not go back to the account: the bank has to resolve it by hand. */
  public Optional<Transaction> reversalRejected(String code, String message, Instant now) {
    return from(
        TransactionStatus.PENDING_REVERSAL,
        now,
        next ->
            next.status(TransactionStatus.REVERSAL_FAILED)
                .reversalFailure(new ReversalFailure(code, message)));
  }

  /** The command of the current step was sent once more because it got no answer. */
  public Transaction commandResent(Instant now) {
    return toBuilder()
        .commandSentAt(now)
        .commandAttempts(commandAttempts + 1)
        .version(version + 1)
        .build();
  }

  // A change applies only from the status that waits for it; the new step starts its own count
  // of sent commands.
  private Optional<Transaction> from(
      TransactionStatus expected, Instant now, UnaryOperator<TransactionBuilder> change) {
    if (status != expected) {
      return Optional.empty();
    }
    TransactionBuilder next =
        toBuilder().updatedAt(now).commandSentAt(now).commandAttempts(0).version(version + 1);
    return Optional.of(change.apply(next).build());
  }
}
