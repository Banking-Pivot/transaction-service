package com.banking.transactionservice.support;

import com.banking.transactionservice.domain.model.CurrencyCode;
import com.banking.transactionservice.domain.model.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Ready-made transactions for unit tests. */
public final class Transactions {

  public static final Instant START = Instant.parse("2026-11-20T15:00:00Z");
  public static final UUID ACCOUNT = UUID.fromString("3f2a9c1e-5b7d-4e8f-9a0b-1c2d3e4f5a6b");
  public static final UUID CREDIT = UUID.fromString("8d7c6b5a-4f3e-4d2c-8b1a-0f9e8d7c6b5a");
  public static final UUID REVERSAL = UUID.fromString("11111111-2222-4333-8444-555555555555");

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
