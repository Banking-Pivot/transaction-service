package com.banking.transactionservice.application.port.in;

import com.banking.transactionservice.domain.model.ParticipantReply;
import reactor.core.publisher.Mono;

/** Moves a transaction with the answer of Account Service or Credit Service to its command. */
public interface ApplyParticipantReplyUseCase {

  Mono<Void> execute(ParticipantReply reply);
}
