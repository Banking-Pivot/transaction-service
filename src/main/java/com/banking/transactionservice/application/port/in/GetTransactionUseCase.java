package com.banking.transactionservice.application.port.in;

import com.banking.transactionservice.domain.model.Transaction;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Reads a transaction in its current state. */
public interface GetTransactionUseCase {

  Mono<Transaction> execute(UUID transactionId);
}
