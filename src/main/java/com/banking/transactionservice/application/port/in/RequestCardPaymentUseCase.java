package com.banking.transactionservice.application.port.in;

import com.banking.transactionservice.application.command.RequestCardPaymentCommand;
import com.banking.transactionservice.application.model.RequestedTransaction;
import reactor.core.publisher.Mono;

/** Records a payment with a debit card and starts it. */
public interface RequestCardPaymentUseCase {

  Mono<RequestedTransaction> execute(RequestCardPaymentCommand command);
}
