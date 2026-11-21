package com.banking.transactionservice.infrastructure.adapter.in.rest;

import com.banking.transactionservice.application.command.RequestCreditPaymentCommand;
import com.banking.transactionservice.application.model.RequestedTransaction;
import com.banking.transactionservice.application.port.in.GetTransactionUseCase;
import com.banking.transactionservice.application.port.in.ListTransactionsUseCase;
import com.banking.transactionservice.application.port.in.RequestCreditPaymentUseCase;
import com.banking.transactionservice.application.query.ListTransactionsQuery;
import com.banking.transactionservice.application.query.PageRequest;
import com.banking.transactionservice.domain.model.CurrencyCode;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.api.TransactionsApiDelegate;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.CreditPaymentRequest;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionPageResponse;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionResponse;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionStatus;
import com.banking.transactionservice.infrastructure.correlation.CorrelationId;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Requests and queries of transactions. A request is answered 202 as soon as the transaction is
 * recorded, with where to read its result; a recognized repeat answers the same with {@code
 * Idempotent-Replayed: true} and the transaction in its current state.
 */
@Component
public class TransactionsApiDelegateImpl implements TransactionsApiDelegate {

  static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
  static final String TRANSACTIONS_PATH = "/api/v1/transactions/";

  private final RequestCreditPaymentUseCase requestCreditPayment;
  private final GetTransactionUseCase getTransaction;
  private final ListTransactionsUseCase listTransactions;
  private final TransactionRestMapper mapper;

  /** Creates the delegate with the use cases and the mapper that builds the responses. */
  public TransactionsApiDelegateImpl(
      RequestCreditPaymentUseCase requestCreditPayment,
      GetTransactionUseCase getTransaction,
      ListTransactionsUseCase listTransactions,
      TransactionRestMapper mapper) {
    this.requestCreditPayment = requestCreditPayment;
    this.getTransaction = getTransaction;
    this.listTransactions = listTransactions;
    this.mapper = mapper;
  }

  @Override
  public Mono<ResponseEntity<TransactionResponse>> requestCreditPayment(
      Mono<CreditPaymentRequest> request, ServerWebExchange exchange) {
    return request
        .map(
            body ->
                new RequestCreditPaymentCommand(
                    body.getOperationId(),
                    body.getAccountId(),
                    body.getCreditId(),
                    body.getAmount(),
                    CurrencyCode.valueOf(body.getCurrency().getValue()),
                    body.getDescription(),
                    CorrelationId.of(exchange)))
        .flatMap(requestCreditPayment::execute)
        .map(this::accepted);
  }

  @Override
  public Mono<ResponseEntity<TransactionResponse>> getTransaction(
      UUID transactionId, ServerWebExchange exchange) {
    return getTransaction.execute(transactionId).map(mapper::toResponse).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<TransactionPageResponse>> listTransactions(
      Integer page,
      Integer size,
      UUID accountId,
      UUID creditId,
      TransactionStatus status,
      ServerWebExchange exchange) {
    return Mono.fromCallable(
            () ->
                new ListTransactionsQuery(
                    PageRequest.of(page, size),
                    accountId,
                    creditId,
                    status == null
                        ? null
                        : com.banking.transactionservice.domain.model.TransactionStatus.valueOf(
                            status.getValue())))
        .flatMap(listTransactions::execute)
        .map(mapper::toPage)
        .map(ResponseEntity::ok);
  }

  private ResponseEntity<TransactionResponse> accepted(RequestedTransaction requested) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.accepted()
            .location(URI.create(TRANSACTIONS_PATH + requested.transaction().getId()));
    if (requested.replayed()) {
      response.header(IDEMPOTENT_REPLAYED, "true");
    }
    return response.body(mapper.toResponse(requested.transaction()));
  }
}
