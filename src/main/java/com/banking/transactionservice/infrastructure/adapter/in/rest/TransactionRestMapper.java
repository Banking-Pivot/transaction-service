package com.banking.transactionservice.infrastructure.adapter.in.rest;

import com.banking.transactionservice.application.model.CardMovement;
import com.banking.transactionservice.application.model.ResultPage;
import com.banking.transactionservice.domain.model.Transaction;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.CardMovementResponse;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.CurrencyCode;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.LatestCardMovementsResponse;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.MovementDirection;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.RejectedStep;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.Rejection;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.ReversalFailure;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionPageResponse;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionResponse;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionStatus;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Turns transactions into the responses of the contract. Enums are matched by name. */
@Component
public class TransactionRestMapper {

  /** The transaction as the contract shows it; fields that do not apply yet are left out. */
  public TransactionResponse toResponse(Transaction transaction) {
    TransactionResponse response =
        new TransactionResponse()
            .id(transaction.getId())
            .type(TransactionType.fromValue(transaction.getType().name()))
            .status(TransactionStatus.fromValue(transaction.getStatus().name()))
            .sourceCardId(transaction.getSourceCardId())
            .destinationCardId(transaction.getDestinationCardId())
            .accountId(transaction.getAccountId())
            .destinationAccountId(transaction.getDestinationAccountId())
            .creditId(transaction.getCreditId())
            .amount(transaction.getAmount())
            .currency(CurrencyCode.fromValue(transaction.getCurrency().name()))
            .description(transaction.getDescription())
            .fee(transaction.getFee())
            .destinationFee(transaction.getDestinationFee())
            .accountBalanceAfter(transaction.getAccountBalanceAfter())
            .debtAfter(transaction.getDebtAfter())
            .createdAt(utc(transaction.getCreatedAt()))
            .updatedAt(utc(transaction.getUpdatedAt()));
    if (transaction.getRejection() != null) {
      response.rejection(
          new Rejection()
              .step(RejectedStep.fromValue(transaction.getRejection().step().name()))
              .code(transaction.getRejection().code())
              .message(transaction.getRejection().message()));
    }
    if (transaction.getReversalFailure() != null) {
      response.reversalFailure(
          new ReversalFailure()
              .code(transaction.getReversalFailure().code())
              .message(transaction.getReversalFailure().message()));
    }
    return response;
  }

  /** A page of transactions with its totals. */
  public TransactionPageResponse toPage(ResultPage<Transaction> page) {
    return new TransactionPageResponse()
        .items(page.items().stream().map(this::toResponse).toList())
        .page(page.page())
        .size(page.size())
        .totalElements(page.totalElements())
        .totalPages(page.totalPages());
  }

  /** The latest movements of a card, in the order they are given. */
  public LatestCardMovementsResponse toLatestMovements(UUID cardId, List<CardMovement> movements) {
    return new LatestCardMovementsResponse()
        .cardId(cardId)
        .items(movements.stream().map(TransactionRestMapper::toMovement).toList());
  }

  private static CardMovementResponse toMovement(CardMovement movement) {
    return new CardMovementResponse()
        .transactionId(movement.transactionId())
        .type(TransactionType.fromValue(movement.type().name()))
        .direction(MovementDirection.fromValue(movement.direction().name()))
        .accountId(movement.accountId())
        .amount(movement.amount())
        .fee(movement.fee())
        .currency(CurrencyCode.fromValue(movement.currency().name()))
        .description(movement.description())
        .occurredAt(utc(movement.occurredAt()));
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
