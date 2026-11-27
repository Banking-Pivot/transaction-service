package com.banking.transactionservice.application.handler.query;

import com.banking.transactionservice.application.model.CardMovement;
import com.banking.transactionservice.application.port.in.GetLatestCardMovementsUseCase;
import com.banking.transactionservice.application.port.out.TransactionStorePort;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Reports the latest movements of a debit card from the completed transactions that used it: card
 * payments, and wallet payments that took the money from its main account or put it there.
 */
public class GetLatestCardMovementsHandler implements GetLatestCardMovementsUseCase {

  private final TransactionStorePort transactions;

  public GetLatestCardMovementsHandler(TransactionStorePort transactions) {
    this.transactions = Objects.requireNonNull(transactions);
  }

  @Override
  public Mono<List<CardMovement>> execute(UUID cardId) {
    return transactions
        .findLatestCompletedByCard(cardId, LIMIT)
        .map(transaction -> CardMovement.of(transaction, cardId))
        .collectList();
  }
}
