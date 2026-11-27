package com.banking.transactionservice.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka topics and what happens to a message that cannot be handled.
 *
 * <p>The topics are declared here and in the services on the other side, with the same shape;
 * whichever starts first creates them. Three partitions let several instances share the work, and
 * the key of every message (account, credit product, card or transaction) keeps its messages in one
 * partition, so in order.
 */
@Configuration(proxyBeanMethods = false)
public class MessagingConfiguration {

  static final int PARTITIONS = 3;
  static final long RETRIES = 3;

  @Bean
  NewTopic accountCommandsTopic(TransactionProperties properties) {
    return topic(properties.messaging().accountCommandsTopic());
  }

  @Bean
  NewTopic creditCommandsTopic(TransactionProperties properties) {
    return topic(properties.messaging().creditCommandsTopic());
  }

  @Bean
  NewTopic cardCommandsTopic(TransactionProperties properties) {
    return topic(properties.messaging().cardCommandsTopic());
  }

  @Bean
  NewTopic repliesTopic(TransactionProperties properties) {
    return topic(properties.messaging().repliesTopic());
  }

  @Bean
  NewTopic commandsTopic(TransactionProperties properties) {
    return topic(properties.messaging().commandsTopic());
  }

  @Bean
  NewTopic walletRepliesTopic(TransactionProperties properties) {
    return topic(properties.messaging().walletRepliesTopic());
  }

  /**
   * A message that fails to be handled (the database is down, for example) is tried three more
   * times and then skipped with an error in the log. Skipping loses nothing. After an answer, the
   * transaction keeps waiting, its command is sent again and the service answers again. After a
   * request of Yanki Service, that service asks again.
   */
  @Bean
  CommonErrorHandler replyErrorHandler(TransactionProperties properties) {
    return new DefaultErrorHandler(
        new FixedBackOff(properties.messaging().retryInterval().toMillis(), RETRIES));
  }

  private static NewTopic topic(String name) {
    return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
  }
}
