package com.banking.transactionservice.infrastructure.config;

import com.banking.transactionservice.application.handler.command.ApplyParticipantReplyHandler;
import com.banking.transactionservice.application.handler.command.CommandDispatcher;
import com.banking.transactionservice.application.handler.command.RequestCreditPaymentHandler;
import com.banking.transactionservice.application.handler.command.ResendPendingCommandsHandler;
import com.banking.transactionservice.application.handler.query.GetTransactionHandler;
import com.banking.transactionservice.application.handler.query.ListTransactionsHandler;
import com.banking.transactionservice.application.port.in.ApplyParticipantReplyUseCase;
import com.banking.transactionservice.application.port.in.GetTransactionUseCase;
import com.banking.transactionservice.application.port.in.ListTransactionsUseCase;
import com.banking.transactionservice.application.port.in.RequestCreditPaymentUseCase;
import com.banking.transactionservice.application.port.in.ResendPendingCommandsUseCase;
import com.banking.transactionservice.application.port.out.CommandPublisherPort;
import com.banking.transactionservice.application.port.out.TransactionStorePort;
import java.time.Clock;
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

  @Bean
  public Clock transactionClock() {
    return Clock.systemUTC();
  }

  @Bean
  public CommandDispatcher commandDispatcher(CommandPublisherPort publisher) {
    return new CommandDispatcher(publisher);
  }

  @Bean
  public RequestCreditPaymentUseCase requestCreditPaymentUseCase(
      TransactionStorePort transactions, CommandDispatcher dispatcher, Clock clock) {
    return new RequestCreditPaymentHandler(transactions, dispatcher, clock, UUID::randomUUID);
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
}
