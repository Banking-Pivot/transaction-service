package com.banking.transactionservice.infrastructure.adapter.out.kafka;

import com.banking.transactionservice.domain.model.vo.ParticipantCommand;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Builder;

/**
 * A command as it travels in Kafka: one JSON object whose {@code type} says what is asked. Fields
 * that do not apply to a type are left out.
 *
 * @param operationId key the receiving service records the effect under
 * @param originalOperationId for REVERSE_DEBIT, the operationId of the debit to return
 * @param replyTo for RESOLVE_CARD, the topic Card Service answers on
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandMessage(
    String type,
    UUID transactionId,
    UUID operationId,
    UUID cardId,
    UUID accountId,
    UUID creditId,
    UUID originalOperationId,
    BigDecimal amount,
    String currency,
    String description,
    String replyTo) {

  static final String RESOLVE_CARD = "RESOLVE_CARD";
  static final String DEBIT_ACCOUNT = "DEBIT_ACCOUNT";
  static final String CREDIT_ACCOUNT = "CREDIT_ACCOUNT";
  static final String PAY_CREDIT = "PAY_CREDIT";
  static final String REVERSE_DEBIT = "REVERSE_DEBIT";

  static CommandMessage of(ParticipantCommand.ResolveCard command, String replyTo) {
    return builder()
        .type(RESOLVE_CARD)
        .transactionId(command.transactionId())
        .cardId(command.cardId())
        .replyTo(replyTo)
        .build();
  }

  static CommandMessage of(ParticipantCommand.DebitAccount command) {
    return builder()
        .type(DEBIT_ACCOUNT)
        .transactionId(command.transactionId())
        .operationId(command.operationId())
        .accountId(command.accountId())
        .amount(command.amount())
        .currency(command.currency().name())
        .description(command.description())
        .build();
  }

  static CommandMessage of(ParticipantCommand.CreditAccount command) {
    return builder()
        .type(CREDIT_ACCOUNT)
        .transactionId(command.transactionId())
        .operationId(command.operationId())
        .accountId(command.accountId())
        .amount(command.amount())
        .currency(command.currency().name())
        .description(command.description())
        .build();
  }

  static CommandMessage of(ParticipantCommand.PayCredit command) {
    return builder()
        .type(PAY_CREDIT)
        .transactionId(command.transactionId())
        .operationId(command.operationId())
        .creditId(command.creditId())
        .amount(command.amount())
        .currency(command.currency().name())
        .description(command.description())
        .build();
  }

  static CommandMessage of(ParticipantCommand.ReverseDebit command) {
    return builder()
        .type(REVERSE_DEBIT)
        .transactionId(command.transactionId())
        .operationId(command.operationId())
        .accountId(command.accountId())
        .originalOperationId(command.originalOperationId())
        .description(command.description())
        .build();
  }
}
