package com.banking.transactionservice.application.port.in;

import com.banking.transactionservice.application.command.RequestCreditPaymentCommand;
import com.banking.transactionservice.application.model.RequestedTransaction;
import reactor.core.publisher.Mono;

/** Records the payment of a credit product from an account and starts it. */
public interface RequestCreditPaymentUseCase {

  Mono<RequestedTransaction> execute(RequestCreditPaymentCommand command);
}
