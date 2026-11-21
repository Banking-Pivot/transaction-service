package com.banking.transactionservice.infrastructure.adapter.out.kafka;

import com.banking.transactionservice.domain.model.ParticipantCommand;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A command as it travels in Kafka: one JSON object whose {@code type} says what is asked. Fields
 * that do not apply to a type are left out.
 *
 * @param operationId key the receiving service records the effect under
 * @param originalOperationId for REVERSE_DEBIT, the operationId of the debit to return
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandMessage(
    String type,
    UUID transactionId,
    UUID operationId,
    UUID accountId,
    UUID creditId,
    UUID originalOperationId,
    BigDecimal amount,
    String currency,
    String description) {

  static final String DEBIT_ACCOUNT = "DEBIT_ACCOUNT";
  static final String PAY_CREDIT = "PAY_CREDIT";
  static final String REVERSE_DEBIT = "REVERSE_DEBIT";

  static CommandMessage of(ParticipantCommand.DebitAccount command) {
    return new CommandMessage(
        DEBIT_ACCOUNT,
        command.transactionId(),
        command.operationId(),
        command.accountId(),
        null,
        null,
        command.amount(),
        command.currency().name(),
        command.description());
  }

  static CommandMessage of(ParticipantCommand.PayCredit command) {
    return new CommandMessage(
        PAY_CREDIT,
        command.transactionId(),
        command.operationId(),
        null,
        command.creditId(),
        null,
        command.amount(),
        command.currency().name(),
        command.description());
  }

  static CommandMessage of(ParticipantCommand.ReverseDebit command) {
    return new CommandMessage(
        REVERSE_DEBIT,
        command.transactionId(),
        command.operationId(),
        command.accountId(),
        null,
        command.originalOperationId(),
        null,
        null,
        command.description());
  }
}
