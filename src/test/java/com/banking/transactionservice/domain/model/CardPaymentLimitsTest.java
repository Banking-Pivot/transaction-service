package com.banking.transactionservice.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.transactionservice.domain.exception.RuleViolation;
import com.banking.transactionservice.domain.exception.TransactionRuleViolationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class CardPaymentLimitsTest {

  private static final ZoneId LIMA = ZoneId.of("America/Lima");

  private final CardPaymentLimits limits =
      new CardPaymentLimits(new BigDecimal("5000.00"), new BigDecimal("10000.00"), LIMA);

  @Test
  void aPaymentUpToTheLimitPassesAndOneCentMoreIsRefused() {
    assertThatCode(() -> limits.checkPayment(new BigDecimal("5000.00"))).doesNotThrowAnyException();

    assertThatThrownBy(() -> limits.checkPayment(new BigDecimal("5000.01")))
        .isInstanceOfSatisfying(
            TransactionRuleViolationException.class,
            refused ->
                assertThat(refused.violation())
                    .isEqualTo(RuleViolation.CARD_PAYMENT_LIMIT_EXCEEDED))
        .hasMessageContaining("5000.00");
  }

  @Test
  void theDayCanBeFilledExactlyAndOneCentMoreIsRefused() {
    assertThatCode(() -> limits.checkDay(new BigDecimal("9920.00"), new BigDecimal("80.00")))
        .doesNotThrowAnyException();
    assertThatCode(() -> limits.checkDay(BigDecimal.ZERO, new BigDecimal("80.00")))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> limits.checkDay(new BigDecimal("9920.01"), new BigDecimal("80.00")))
        .isInstanceOfSatisfying(
            TransactionRuleViolationException.class,
            refused ->
                assertThat(refused.violation()).isEqualTo(RuleViolation.CARD_DAILY_LIMIT_EXCEEDED))
        .hasMessageContaining("10000.00");
  }

  @Test
  void theBusinessDayIsTheCalendarDayOfTheZoneNotOfUtc() {
    // 15:00 UTC is 10:00 in Lima, the same day.
    Instant morning = Instant.parse("2026-11-20T15:00:00Z");
    assertThat(limits.dayStart(morning)).isEqualTo(Instant.parse("2026-11-20T05:00:00Z"));
    assertThat(limits.dayEnd(morning)).isEqualTo(Instant.parse("2026-11-21T05:00:00Z"));

    // 03:00 UTC is still 22:00 of the day before in Lima.
    Instant night = Instant.parse("2026-11-20T03:00:00Z");
    assertThat(limits.dayStart(night)).isEqualTo(Instant.parse("2026-11-19T05:00:00Z"));
    assertThat(limits.dayEnd(night)).isEqualTo(Instant.parse("2026-11-20T05:00:00Z"));
  }

  @Test
  void aLimitConfiguredWithFewerDecimalsIsShownWithTwo() {
    CardPaymentLimits loose = new CardPaymentLimits(new BigDecimal("5000.0"), BigDecimal.TEN, LIMA);

    assertThat(loose.paymentMax()).hasToString("5000.00");
    assertThat(loose.dailyMax()).hasToString("10.00");
    assertThat(new CardPaymentLimits(new BigDecimal("0.125"), BigDecimal.TEN, LIMA).paymentMax())
        .hasToString("0.125");
  }

  @Test
  void everyValueIsRequired() {
    BigDecimal amount = BigDecimal.ONE;
    assertThatThrownBy(() -> new CardPaymentLimits(null, amount, LIMA))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new CardPaymentLimits(amount, null, LIMA))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new CardPaymentLimits(amount, amount, null))
        .isInstanceOf(NullPointerException.class);
  }
}
