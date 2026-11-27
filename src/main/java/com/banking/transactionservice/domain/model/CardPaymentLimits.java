package com.banking.transactionservice.domain.model;

import com.banking.transactionservice.domain.exception.RuleViolation;
import com.banking.transactionservice.domain.exception.TransactionRuleViolationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * How much a debit card can pay: at most {@code paymentMax} in one payment and {@code dailyMax} in
 * one business day. Amounts are compared as numbers, whatever the currency of each payment.
 *
 * @param paymentMax largest amount of a single card payment
 * @param dailyMax largest sum of the card payments of one card in one business day
 * @param businessZone time zone whose calendar days are the business days
 */
public record CardPaymentLimits(BigDecimal paymentMax, BigDecimal dailyMax, ZoneId businessZone) {

  /** Requires the three values. The limits are kept with two decimals at least. */
  public CardPaymentLimits {
    paymentMax = atLeastTwoDecimals(Objects.requireNonNull(paymentMax, "paymentMax"));
    dailyMax = atLeastTwoDecimals(Objects.requireNonNull(dailyMax, "dailyMax"));
    Objects.requireNonNull(businessZone, "businessZone");
  }

  /**
   * Refuses a payment above the limit of a single payment.
   *
   * @throws TransactionRuleViolationException {@code CARD_PAYMENT_LIMIT_EXCEEDED}
   */
  public void checkPayment(BigDecimal amount) {
    if (amount.compareTo(paymentMax) > 0) {
      throw new TransactionRuleViolationException(
          RuleViolation.CARD_PAYMENT_LIMIT_EXCEEDED,
          "The amount exceeds the limit of " + paymentMax.toPlainString() + " per card payment.");
    }
  }

  /**
   * Refuses a payment that takes the card above its limit of the day.
   *
   * @param countedToday sum of the payments of the card requested today that still count
   * @param amount amount of the new payment
   * @throws TransactionRuleViolationException {@code CARD_DAILY_LIMIT_EXCEEDED}
   */
  public void checkDay(BigDecimal countedToday, BigDecimal amount) {
    if (countedToday.add(amount).compareTo(dailyMax) > 0) {
      throw new TransactionRuleViolationException(
          RuleViolation.CARD_DAILY_LIMIT_EXCEEDED,
          "The payment exceeds the limit of " + dailyMax.toPlainString() + " per day of the card.");
    }
  }

  /** First instant of the business day the given instant falls in. */
  public Instant dayStart(Instant now) {
    return now.atZone(businessZone).toLocalDate().atStartOfDay(businessZone).toInstant();
  }

  /** First instant of the business day after the one the given instant falls in. */
  public Instant dayEnd(Instant now) {
    return now.atZone(businessZone)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(businessZone)
        .toInstant();
  }

  // A limit written as 5000 or 5000.0 in the settings reads 5000.00 in the messages.
  private static BigDecimal atLeastTwoDecimals(BigDecimal limit) {
    return limit.scale() < Amounts.SCALE ? limit.setScale(Amounts.SCALE) : limit;
  }
}
