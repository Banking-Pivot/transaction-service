package com.banking.transactionservice.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of Transaction Service, served by the Config Server under {@code transaction}.
 *
 * @param messaging topics and times of the conversation with Account Service and Credit Service
 */
@Validated
@ConfigurationProperties("transaction")
public record TransactionProperties(@NotNull @Valid @DefaultValue Messaging messaging) {

  /**
   * How commands go out and answers come in.
   *
   * @param accountCommandsTopic topic Account Service reads its commands from
   * @param creditCommandsTopic topic Credit Service reads its commands from
   * @param repliesTopic topic both services answer on
   * @param publishTimeout longest a send may take before it counts as failed
   * @param retryInterval wait between the three retries of an answer that could not be stored
   * @param resendInterval how often the transactions that still wait are looked at
   * @param resendAfter how long a command waits for its answer before it is sent again
   * @param resendBatchSize most transactions sent again in one look
   */
  public record Messaging(
      @DefaultValue("banking.account.commands") @NotBlank String accountCommandsTopic,
      @DefaultValue("banking.credit.commands") @NotBlank String creditCommandsTopic,
      @DefaultValue("banking.transaction.replies") @NotBlank String repliesTopic,
      @DefaultValue("3s") @NotNull Duration publishTimeout,
      @DefaultValue("1s") @NotNull Duration retryInterval,
      @DefaultValue("10s") @NotNull Duration resendInterval,
      @DefaultValue("30s") @NotNull Duration resendAfter,
      @DefaultValue("50") @Min(1) int resendBatchSize) {}
}
