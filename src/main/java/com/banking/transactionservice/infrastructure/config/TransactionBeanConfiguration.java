package com.banking.transactionservice.infrastructure.config;

import com.banking.transactionservice.application.handler.command.ApplyParticipantReplyHandler;
import com.banking.transactionservice.application.handler.command.CommandDispatcher;
import com.banking.transactionservice.application.handler.command.MoveFundsHandler;
import com.banking.transactionservice.application.handler.command.RequestCardPaymentHandler;
import com.banking.transactionservice.application.handler.command.RequestCreditPaymentHandler;
import com.banking.transactionservice.application.handler.command.ResendPendingCommandsHandler;
import com.banking.transactionservice.application.handler.query.GetLatestCardMovementsHandler;
import com.banking.transactionservice.application.handler.query.GetTransactionHandler;
import com.banking.transactionservice.application.handler.query.ListTransactionsHandler;
import com.banking.transactionservice.application.port.in.ApplyParticipantReplyUseCase;
import com.banking.transactionservice.application.port.in.GetLatestCardMovementsUseCase;
import com.banking.transactionservice.application.port.in.GetTransactionUseCase;
import com.banking.transactionservice.application.port.in.ListTransactionsUseCase;
import com.banking.transactionservice.application.port.in.MoveFundsUseCase;
import com.banking.transactionservice.application.port.in.RequestCardPaymentUseCase;
import com.banking.transactionservice.application.port.in.RequestCreditPaymentUseCase;
import com.banking.transactionservice.application.port.in.ResendPendingCommandsUseCase;
import com.banking.transactionservice.application.port.out.CommandPublisherPort;
import com.banking.transactionservice.application.port.out.OutcomePublisherPort;
import com.banking.transactionservice.application.port.out.TransactionStorePort;
import com.banking.transactionservice.domain.model.vo.CardPaymentLimits;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the use cases. The application and domain layers have no Spring annotations; this class is
 * where they meet their adapters.
 */
@Configuration
@EnableConfigurationProperties(TransactionProperties.class)
public class TransactionBeanConfiguration {

  /**
   * The clock of the service, in microseconds: what PostgreSQL keeps. An instant is then the same
   * in memory and read back, so an outcome sent again says exactly what the first one said.
   */
  @Bean
  public Clock transactionClock() {
    return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
  }

  @Bean
  public CommandDispatcher commandDispatcher(
      CommandPublisherPort commands, OutcomePublisherPort outcomes) {
    return new CommandDispatcher(commands, outcomes);
  }

  @Bean
  public RequestCreditPaymentUseCase requestCreditPaymentUseCase(
      TransactionStorePort transactions, CommandDispatcher dispatcher, Clock clock) {
    return new RequestCreditPaymentHandler(transactions, dispatcher, clock, UUID::randomUUID);
  }

  /** The payment with a debit card, with the limits and the business zone of the settings. */
  @Bean
  public RequestCardPaymentUseCase requestCardPaymentUseCase(
      TransactionStorePort transactions,
      CommandDispatcher dispatcher,
      Clock clock,
      TransactionProperties properties) {
    CardPaymentLimits limits =
        new CardPaymentLimits(
            properties.limits().cardPaymentMax(),
            properties.limits().cardDailyMax(),
            properties.businessZone());
    return new RequestCardPaymentHandler(transactions, dispatcher, limits, clock, UUID::randomUUID);
  }

  @Bean
  public MoveFundsUseCase moveFundsUseCase(
      TransactionStorePort transactions, CommandDispatcher dispatcher, Clock clock) {
    return new MoveFundsHandler(transactions, dispatcher, clock, UUID::randomUUID);
  }

  @Bean
  public ApplyParticipantReplyUseCase applyParticipantReplyUseCase(
      TransactionStorePort transactions, CommandDispatcher dispatcher, Clock clock) {
    return new ApplyParticipantReplyHandler(transactions, dispatcher, clock);
  }

  /** The re-send of unanswered commands, with the wait and the batch size of the settings. */
  @Bean
  public ResendPendingCommandsUseCase resendPendingCommandsUseCase(
      TransactionStorePort transactions,
      CommandDispatcher dispatcher,
      Clock clock,
      TransactionProperties properties) {
    return new ResendPendingCommandsHandler(
        transactions,
        dispatcher,
        clock,
        properties.messaging().resendAfter(),
        properties.messaging().resendBatchSize());
  }

  @Bean
  public GetTransactionUseCase getTransactionUseCase(TransactionStorePort transactions) {
    return new GetTransactionHandler(transactions);
  }

  @Bean
  public ListTransactionsUseCase listTransactionsUseCase(TransactionStorePort transactions) {
    return new ListTransactionsHandler(transactions);
  }

  @Bean
  public GetLatestCardMovementsUseCase getLatestCardMovementsUseCase(
      TransactionStorePort transactions) {
    return new GetLatestCardMovementsHandler(transactions);
  }
}
