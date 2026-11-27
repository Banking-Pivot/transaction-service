package com.banking.transactionservice.domain.model;

import com.banking.transactionservice.domain.exception.RuleViolation;
import com.banking.transactionservice.domain.exception.TransactionRuleViolationException;
import com.banking.transactionservice.domain.model.enums.CurrencyCode;
import com.banking.transactionservice.domain.model.enums.RejectedStep;
import com.banking.transactionservice.domain.model.enums.TransactionStatus;
import com.banking.transactionservice.domain.model.enums.TransactionType;
import com.banking.transactionservice.domain.model.vo.FundsMovementOutcome;
import com.banking.transactionservice.domain.model.vo.ParticipantCommand;
import com.banking.transactionservice.domain.model.vo.Rejection;
import com.banking.transactionservice.domain.model.vo.ReversalFailure;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import lombok.Builder;
import lombok.Getter;

/**
 * One operation that moves money between products kept by different services, and how far it got.
 *
 * <p>A transaction goes through its steps in order, each one waiting for the answer of the service
 * that owns it. Which steps it has depends on its type:
 *
 * <pre>
 * CREDIT_PAYMENT   PENDING_DEBIT -> PENDING_PAYMENT -> COMPLETED
 * CARD_PAYMENT     PENDING_CARD -> PENDING_DEBIT -> COMPLETED
 * WALLET_PAYMENT   PENDING_CARD -> [PENDING_DEBIT] -> [PENDING_CREDIT] -> COMPLETED
 * </pre>
 *
 * <p>A step that is refused ends the transaction as REJECTED when no money was taken yet. When the
 * account was already debited and the step after it is refused, the debit goes back:
 *
 * <pre>
 * PENDING_PAYMENT or PENDING_CREDIT --rejected--> PENDING_REVERSAL --reversed--> REVERSED
 *                                                       +-rejected-> REVERSAL_FAILED
 * </pre>
 *
 * <p>A card payment tries the main account of the card first and, only when that account does not
 * have the money, the other accounts of the card in the order they were linked. A wallet payment
 * uses only the main account of each card.
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
  static final String WALLET_REVERSAL_DESCRIPTION = "Wallet payment not applied";
  static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
  static final int MAX_DESCRIPTION = 140;

  /** The operationId of the request, or the ID Yanki Service asked the movement with. */
  private final UUID id;

  private final TransactionType type;
  private final TransactionStatus status;

  /** Debit card the money leaves from. Card and wallet payments only. */
  private final UUID sourceCardId;

  /** Debit card the money goes to. Wallet payments only. */
  private final UUID destinationCardId;

  /**
   * Account that is debited. A credit payment names it; a card or wallet payment learns it from
   * Card Service, and a card payment changes it when it falls back to the next account.
   */
  private final UUID accountId;

  /** Account that is credited: the main account of the destination card. */
  private final UUID destinationAccountId;

  /** Accounts a card payment may charge, in the order it tries them. */
  @Builder.Default private final List<UUID> candidateAccountIds = List.of();

  /** Credit product that is paid. Credit payments only. */
  private final UUID creditId;

  private final BigDecimal amount;
  private final CurrencyCode currency;
  private final String description;

  /** The operationId the reversal is recorded under in Account Service. */
  private final UUID reversalOperationId;

  /** Movement fee the account paid for the debit; empty until the account is debited. */
  private final BigDecimal fee;

  /** Movement fee the destination account paid for the credit; empty until it is credited. */
  private final BigDecimal destinationFee;

  /** Balance of the debited account after the last movement this transaction made on it. */
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
   * @throws TransactionRuleViolationException when the amount is not positive, has more than two
   *     decimals or exceeds the maximum
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
    return requested(id, amount, currency, description, reversalOperationId, correlationId, now)
        .type(TransactionType.CREDIT_PAYMENT)
        .status(TransactionStatus.PENDING_DEBIT)
        .accountId(Objects.requireNonNull(accountId, "accountId"))
        .creditId(Objects.requireNonNull(creditId, "creditId"))
        .build();
  }

  /**
   * A new payment with a debit card, waiting for Card Service to say which accounts the card uses.
   *
   * @param id the operationId of the request
   * @param reversalOperationId kept for every transaction; a card payment never needs it
   * @throws TransactionRuleViolationException when the amount is not positive, has more than two
   *     decimals or exceeds the maximum
   */
  public static Transaction cardPayment(
      UUID id,
      UUID cardId,
      BigDecimal amount,
      CurrencyCode currency,
      String description,
      UUID reversalOperationId,
      String correlationId,
      Instant now) {
    return requested(id, amount, currency, description, reversalOperationId, correlationId, now)
        .type(TransactionType.CARD_PAYMENT)
        .status(TransactionStatus.PENDING_CARD)
        .sourceCardId(Objects.requireNonNull(cardId, "cardId"))
        .build();
  }

  /**
   * A new movement of wallet funds, waiting for Card Service to say which is the main account of
   * each card.
   *
   * @param id the ID Yanki Service asked the movement with
   * @param sourceCardId card whose main account is debited, or null when the sender pays from the
   *     wallet itself
   * @param destinationCardId card whose main account is credited, or null when the receiver keeps
   *     the money in the wallet itself
   * @throws TransactionRuleViolationException when there is no card at all, or the amount is not
   *     positive, has more than two decimals or exceeds the maximum
   */
  public static Transaction walletPayment(
      UUID id,
      UUID sourceCardId,
      UUID destinationCardId,
      BigDecimal amount,
      CurrencyCode currency,
      String description,
      UUID reversalOperationId,
      String correlationId,
      Instant now) {
    if (sourceCardId == null && destinationCardId == null) {
      throw new TransactionRuleViolationException(
          RuleViolation.CARD_REQUIRED, "A source card or a destination card is required.");
    }
    return requested(id, amount, currency, description, reversalOperationId, correlationId, now)
        .type(TransactionType.WALLET_PAYMENT)
        .status(TransactionStatus.PENDING_CARD)
        .sourceCardId(sourceCardId)
        .destinationCardId(destinationCardId)
        .build();
  }

  /** Whether the other transaction asks for exactly the same thing as this one. */
  public boolean sameRequestAs(Transaction other) {
    return type == other.type
        && (type != TransactionType.CREDIT_PAYMENT || Objects.equals(accountId, other.accountId))
        && Objects.equals(creditId, other.creditId)
        && Objects.equals(sourceCardId, other.sourceCardId)
        && Objects.equals(destinationCardId, other.destinationCardId)
        && amount.compareTo(other.amount) == 0
        && currency == other.currency
        && Objects.equals(description, other.description);
  }

  /**
   * The commands the current step is waiting on, to send or to send again. Only the step that asks
   * about two cards has more than one.
   *
   * @return empty once the transaction is final
   */
  public List<ParticipantCommand> nextCommands() {
    return switch (status) {
      case PENDING_CARD ->
          unresolvedCards().stream()
              .<ParticipantCommand>map(card -> new ParticipantCommand.ResolveCard(id, card))
              .toList();
      case PENDING_DEBIT ->
          List.of(
              new ParticipantCommand.DebitAccount(
                  id, debitOperationId(), accountId, amount, currency, description));
      case PENDING_CREDIT ->
          List.of(
              new ParticipantCommand.CreditAccount(
                  id, creditOperationId(), destinationAccountId, amount, currency, description));
      case PENDING_PAYMENT ->
          List.of(
              new ParticipantCommand.PayCredit(id, id, creditId, amount, currency, description));
      case PENDING_REVERSAL ->
          List.of(
              new ParticipantCommand.ReverseDebit(
                  id, reversalOperationId, debitOperationId(), accountId, reversalDescription()));
      default -> List.of();
    };
  }

  /**
   * Whether this transaction, the result of an answer, waits for something its previous state did
   * not: another step, or the same step on another account. Only then there is something to send. A
   * wallet payment with two cards that heard about the first one is still waiting for the other.
   */
  public boolean advancedFrom(Transaction previous) {
    return status != previous.status || !Objects.equals(accountId, previous.accountId);
  }

  /**
   * Whether an answer to a debit is the answer to the debit this transaction is waiting on. A card
   * payment asks one account after another, so an answer about an earlier account can still arrive.
   *
   * @param operationId operationId the answer carries, or null when it carries none
   */
  public boolean waitsForDebit(UUID operationId) {
    return status == TransactionStatus.PENDING_DEBIT
        && (operationId == null || operationId.equals(debitOperationId()));
  }

  /**
   * Card Service said which accounts a card uses. Once every card of the transaction is known, the
   * first money step is next: the debit when there is a source card, the credit otherwise.
   *
   * @param mainAccountId main account of the card
   * @param linkedAccountIds every account of the card in the order it was linked; a card payment
   *     tries them after the main one
   */
  public Optional<Transaction> cardResolved(
      UUID cardId, UUID mainAccountId, List<UUID> linkedAccountIds, Instant now) {
    if (!unresolvedCards().contains(cardId)) {
      return Optional.empty();
    }
    return from(
            TransactionStatus.PENDING_CARD,
            now,
            next -> {
              if (cardId.equals(sourceCardId)) {
                next.accountId(mainAccountId);
                if (type == TransactionType.CARD_PAYMENT) {
                  next.candidateAccountIds(mainFirst(mainAccountId, linkedAccountIds));
                }
              }
              if (cardId.equals(destinationCardId)) {
                next.destinationAccountId(mainAccountId);
              }
              return next;
            })
        .map(next -> next.afterCards(commandSentAt, commandAttempts));
  }

  /** Card Service did not accept a card: the transaction ends and no money moved. */
  public Optional<Transaction> cardRejected(UUID cardId, String code, String message, Instant now) {
    if (!unresolvedCards().contains(cardId)) {
      return Optional.empty();
    }
    return from(
        TransactionStatus.PENDING_CARD,
        now,
        next ->
            next.status(TransactionStatus.REJECTED)
                .rejection(new Rejection(RejectedStep.CARD, code, message)));
  }

  /**
   * The account was debited. What is next depends on the type: the payment of the credit product,
   * the credit of the destination account, or nothing more.
   */
  public Optional<Transaction> debited(
      BigDecimal chargedFee, BigDecimal balanceAfter, Instant now) {
    return from(
        TransactionStatus.PENDING_DEBIT,
        now,
        next -> next.status(afterDebit()).fee(chargedFee).accountBalanceAfter(balanceAfter));
  }

  /**
   * The account was not debited. A card payment whose account does not have the money tries the
   * next account of the card; anything else ends the transaction, and no money moved.
   */
  public Optional<Transaction> debitRejected(String code, String message, Instant now) {
    Optional<UUID> fallback =
        INSUFFICIENT_FUNDS.equals(code) ? nextCandidateAccount() : Optional.empty();
    return from(
        TransactionStatus.PENDING_DEBIT,
        now,
        next ->
            fallback
                .map(next::accountId)
                .orElseGet(
                    () ->
                        next.status(TransactionStatus.REJECTED)
                            .rejection(new Rejection(RejectedStep.DEBIT, code, message))));
  }

  /** The destination account was credited: the transaction ends well. */
  public Optional<Transaction> credited(BigDecimal chargedFee, Instant now) {
    return from(
        TransactionStatus.PENDING_CREDIT,
        now,
        next -> next.status(TransactionStatus.COMPLETED).destinationFee(chargedFee));
  }

  /**
   * The destination account was not credited. When a source account was debited, the debit has to
   * go back to it; otherwise the transaction ends and no money moved.
   */
  public Optional<Transaction> creditRejected(String code, String message, Instant now) {
    return from(
        TransactionStatus.PENDING_CREDIT,
        now,
        next ->
            next.status(
                    sourceCardId == null
                        ? TransactionStatus.REJECTED
                        : TransactionStatus.PENDING_REVERSAL)
                .rejection(new Rejection(RejectedStep.CREDIT, code, message)));
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

  /** The commands of the current step were sent once more because they got no answer. */
  public Transaction commandResent(Instant now) {
    return toBuilder()
        .commandSentAt(now)
        .commandAttempts(commandAttempts + 1)
        .version(version + 1)
        .build();
  }

  /**
   * What Yanki Service is told about a movement of wallet funds that ended.
   *
   * @return empty while the transaction is not final, and for the types nobody is waiting on
   */
  public Optional<FundsMovementOutcome> outcome() {
    if (type != TransactionType.WALLET_PAYMENT || !status.isFinal()) {
      return Optional.empty();
    }
    if (status == TransactionStatus.COMPLETED) {
      return Optional.of(
          new FundsMovementOutcome.Moved(
              id, accountId, destinationAccountId, amount, totalFee(), updatedAt));
    }
    if (status == TransactionStatus.REVERSAL_FAILED) {
      return Optional.of(
          new FundsMovementOutcome.Rejected(
              id,
              FundsMovementOutcome.Rejected.REVERSAL_FAILED,
              "The destination did not take the money and the debit could not be returned."));
    }
    return Optional.of(
        new FundsMovementOutcome.Rejected(id, rejection.code(), rejection.message()));
  }

  private static TransactionBuilder requested(
      UUID id,
      BigDecimal amount,
      CurrencyCode currency,
      String description,
      UUID reversalOperationId,
      String correlationId,
      Instant now) {
    return builder()
        .id(Objects.requireNonNull(id, "id"))
        .amount(Amounts.positive(amount))
        .currency(Objects.requireNonNull(currency, "currency"))
        .description(described(description))
        .reversalOperationId(Objects.requireNonNull(reversalOperationId, "reversalOperationId"))
        .correlationId(Objects.requireNonNull(correlationId, "correlationId"))
        .commandSentAt(Objects.requireNonNull(now, "now"))
        .commandAttempts(0)
        .createdAt(now)
        .updatedAt(now)
        .version(0);
  }

  // Blank is none; what does not fit in the movements of the accounts is cut.
  private static String described(String description) {
    if (description == null || description.isBlank()) {
      return null;
    }
    String stripped = description.strip();
    return stripped.length() <= MAX_DESCRIPTION ? stripped : stripped.substring(0, MAX_DESCRIPTION);
  }

  private static List<UUID> mainFirst(UUID mainAccountId, List<UUID> linkedAccountIds) {
    Set<UUID> ordered = new LinkedHashSet<>();
    ordered.add(mainAccountId);
    if (linkedAccountIds != null) {
      ordered.addAll(linkedAccountIds);
    }
    return List.copyOf(ordered);
  }

  // Cards whose accounts are still unknown; the same card on both sides is asked about once.
  private Set<UUID> unresolvedCards() {
    Set<UUID> cards = new LinkedHashSet<>();
    if (sourceCardId != null && accountId == null) {
      cards.add(sourceCardId);
    }
    if (destinationCardId != null && destinationAccountId == null) {
      cards.add(destinationCardId);
    }
    return cards;
  }

  // Still waiting for the other card, whose command keeps the time it was sent, or on to the
  // first step that moves money.
  private Transaction afterCards(Instant sentAt, int attempts) {
    if (!unresolvedCards().isEmpty()) {
      return toBuilder().commandSentAt(sentAt).commandAttempts(attempts).build();
    }
    return toBuilder()
        .status(
            sourceCardId == null
                ? TransactionStatus.PENDING_CREDIT
                : TransactionStatus.PENDING_DEBIT)
        .build();
  }

  private TransactionStatus afterDebit() {
    if (type == TransactionType.CREDIT_PAYMENT) {
      return TransactionStatus.PENDING_PAYMENT;
    }
    return destinationCardId == null
        ? TransactionStatus.COMPLETED
        : TransactionStatus.PENDING_CREDIT;
  }

  private Optional<UUID> nextCandidateAccount() {
    int next = candidateAccountIds.indexOf(accountId) + 1;
    return next > 0 && next < candidateAccountIds.size()
        ? Optional.of(candidateAccountIds.get(next))
        : Optional.empty();
  }

  // A credit payment debits under its own ID. The other types can ask more than one account, so
  // every debit has its own key, always the same for the same transaction and account.
  private UUID debitOperationId() {
    return type == TransactionType.CREDIT_PAYMENT ? id : derivedOperationId("debit", accountId);
  }

  private UUID creditOperationId() {
    return derivedOperationId("credit", destinationAccountId);
  }

  private UUID derivedOperationId(String step, UUID account) {
    return UUID.nameUUIDFromBytes(
        (id + ":" + step + ":" + account).getBytes(StandardCharsets.UTF_8));
  }

  private String reversalDescription() {
    return type == TransactionType.WALLET_PAYMENT
        ? WALLET_REVERSAL_DESCRIPTION
        : REVERSAL_DESCRIPTION;
  }

  private BigDecimal totalFee() {
    List<BigDecimal> fees = new ArrayList<>();
    if (fee != null) {
      fees.add(fee);
    }
    if (destinationFee != null) {
      fees.add(destinationFee);
    }
    return fees.stream().reduce(BigDecimal.ZERO.setScale(Amounts.SCALE), BigDecimal::add);
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
