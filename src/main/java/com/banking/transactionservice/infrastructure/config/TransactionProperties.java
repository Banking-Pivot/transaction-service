package com.banking.transactionservice.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of Transaction Service, served by the Config Server under {@code transaction}.
 *
 * @param businessZone time zone whose calendar days are the days of the daily limits
 * @param limits most a debit card can pay
 * @param messaging topics and times of the conversation with the other services
 */
@Validated
@ConfigurationProperties("transaction")
public record TransactionProperties(
    @DefaultValue("America/Lima") @NotNull ZoneId businessZone,
    @NotNull @Valid @DefaultValue Limits limits,
    @NotNull @Valid @DefaultValue Messaging messaging) {

  /**
   * Limits of the payments with a debit card. Movements of wallet funds are not subject to them.
   *
   * @param cardPaymentMax largest amount of a single card payment
   * @param cardDailyMax largest sum of the card payments of one card in one business day
   */
  public record Limits(
      @DefaultValue("5000.00") @NotNull @Positive BigDecimal cardPaymentMax,
      @DefaultValue("10000.00") @NotNull @Positive BigDecimal cardDailyMax) {}

  /**
   * How commands go out and answers come in.
   *
   * @param accountCommandsTopic topic Account Service reads its commands from
   * @param creditCommandsTopic topic Credit Service reads its commands from
   * @param cardCommandsTopic topic Card Service reads its commands from
   * @param repliesTopic topic the three services answer on
   * @param commandsTopic topic Yanki Service asks for movements of wallet funds on
   * @param walletRepliesTopic topic Yanki Service reads the outcome of those movements from
   * @param publishTimeout longest a send may take before it counts as failed
   * @param retryInterval wait between the three retries of an answer that could not be stored
   * @param resendInterval how often the transactions that still wait are looked at
   * @param resendAfter how long a command waits for its answer before it is sent again
   * @param resendBatchSize most transactions sent again in one look
   */
  public record Messaging(
      @DefaultValue("banking.account.commands") @NotBlank String accountCommandsTopic,
      @DefaultValue("banking.credit.commands") @NotBlank String creditCommandsTopic,
      @DefaultValue("banking.card.commands") @NotBlank String cardCommandsTopic,
      @DefaultValue("banking.transaction.replies") @NotBlank String repliesTopic,
      @DefaultValue("banking.transaction.commands") @NotBlank String commandsTopic,
      @DefaultValue("banking.yanki.replies") @NotBlank String walletRepliesTopic,
      @DefaultValue("3s") @NotNull Duration publishTimeout,
      @DefaultValue("1s") @NotNull Duration retryInterval,
      @DefaultValue("10s") @NotNull Duration resendInterval,
      @DefaultValue("30s") @NotNull Duration resendAfter,
      @DefaultValue("50") @Min(1) int resendBatchSize) {}
}
