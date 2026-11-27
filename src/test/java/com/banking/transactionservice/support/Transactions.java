package com.banking.transactionservice.support;

import com.banking.transactionservice.domain.model.Transaction;
import com.banking.transactionservice.domain.model.enums.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Ready-made transactions for unit tests. */
public final class Transactions {

  public static final Instant START = Instant.parse("2026-11-20T15:00:00Z");
  public static final UUID ACCOUNT = UUID.fromString("3f2a9c1e-5b7d-4e8f-9a0b-1c2d3e4f5a6b");
  public static final UUID CREDIT = UUID.fromString("8d7c6b5a-4f3e-4d2c-8b1a-0f9e8d7c6b5a");
  public static final UUID REVERSAL = UUID.fromString("11111111-2222-4333-8444-555555555555");
  public static final UUID CARD = UUID.fromString("2c4e6a8b-0d1f-4a3c-9e5b-7d9f1b3c5e7a");
  public static final UUID OTHER_CARD = UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");
  public static final UUID SECOND_ACCOUNT = UUID.fromString("5a5a5a5a-1111-4222-8333-444444444444");
  public static final UUID THIRD_ACCOUNT = UUID.fromString("6b6b6b6b-1111-4222-8333-444444444444");
  public static final UUID DESTINATION = UUID.fromString("7c7c7c7c-1111-4222-8333-444444444444");

  private Transactions() {}

  /** A payment of 150.00 PEN just requested, waiting for the debit. */
  public static Transaction pendingDebit(UUID id) {
    return Transaction.creditPayment(
        id,
        ACCOUNT,
        CREDIT,
        new BigDecimal("150.00"),
        CurrencyCode.PEN,
        "November installment",
        REVERSAL,
        "corr-1",
        START);
  }

  /** A card payment of 80.00 PEN just requested, waiting for Card Service. */
  public static Transaction cardPayment(UUID id) {
    return Transaction.cardPayment(
        id,
        CARD,
        new BigDecimal("80.00"),
        CurrencyCode.PEN,
        "Grocery store",
        REVERSAL,
        "corr-1",
        START);
  }

  /** A card payment in PEN with the given card, of the given amount, requested at an instant. */
  public static Transaction cardPayment(UUID id, UUID cardId, String amount, Instant requestedAt) {
    return Transaction.cardPayment(
        id,
        cardId,
        new BigDecimal(amount),
        CurrencyCode.PEN,
        "Grocery store",
        UUID.randomUUID(),
        "corr-1",
        requestedAt);
  }

  /**
   * The same card payment once the card is known to use ACCOUNT (main), SECOND_ACCOUNT and
   * THIRD_ACCOUNT, waiting for the debit of the main one.
   */
  public static Transaction cardPaymentPendingDebit(UUID id) {
    return cardPayment(id)
        .cardResolved(
            CARD, ACCOUNT, List.of(ACCOUNT, SECOND_ACCOUNT, THIRD_ACCOUNT), START.plusSeconds(1))
        .orElseThrow();
  }

  /** A wallet payment of 60.00 PEN just requested, waiting for Card Service. */
  public static Transaction walletPayment(UUID id, UUID sourceCardId, UUID destinationCardId) {
    return Transaction.walletPayment(
        id,
        sourceCardId,
        destinationCardId,
        new BigDecimal("60.00"),
        CurrencyCode.PEN,
        "Yanki payment",
        REVERSAL,
        "corr-1",
        START);
  }

  /**
   * A wallet payment from CARD (main account ACCOUNT) to OTHER_CARD (main account DESTINATION)
   * whose debit was done, waiting for the credit.
   */
  public static Transaction walletPendingCredit(UUID id) {
    return walletPayment(id, CARD, OTHER_CARD)
        .cardResolved(CARD, ACCOUNT, List.of(ACCOUNT), START.plusSeconds(1))
        .flatMap(
            next ->
                next.cardResolved(
                    OTHER_CARD, DESTINATION, List.of(DESTINATION), START.plusSeconds(2)))
        .flatMap(
            next ->
                next.debited(new BigDecimal("0.50"), new BigDecimal("39.50"), START.plusSeconds(3)))
        .orElseThrow();
  }

  /** The same payment after the account was debited, waiting for the credit product. */
  public static Transaction pendingPayment(UUID id) {
    return pendingDebit(id)
        .debited(new BigDecimal("1.50"), new BigDecimal("348.50"), START.plusSeconds(1))
        .orElseThrow();
  }

  /** The same payment after the credit product rejected it, waiting for the reversal. */
  public static Transaction pendingReversal(UUID id) {
    return pendingPayment(id)
        .paymentRejected(
            "PAYMENT_EXCEEDS_DEBT", "The payment exceeds the debt.", START.plusSeconds(2))
        .orElseThrow();
  }
}
