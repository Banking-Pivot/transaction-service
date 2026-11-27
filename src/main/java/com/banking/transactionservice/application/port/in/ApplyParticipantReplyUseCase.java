package com.banking.transactionservice.application.port.in;

import com.banking.transactionservice.domain.model.ParticipantReply;
import reactor.core.publisher.Mono;

/** Moves a transaction with the answer of the service that owns its current step. */
public interface ApplyParticipantReplyUseCase {

  Mono<Void> execute(ParticipantReply reply);
}
