package com.banking.transactionservice.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka topics and what happens to an answer that cannot be stored.
 *
 * <p>The three topics are declared here and in the services on the other side, with the same shape;
 * whichever starts first creates them. Three partitions let several instances share the work, and
 * the key of every message (account, credit product or transaction) keeps its messages in one
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
  NewTopic repliesTopic(TransactionProperties properties) {
    return topic(properties.messaging().repliesTopic());
  }

  /**
   * An answer that fails to be stored (the database is down, for example) is tried three more times
   * and then skipped with an error in the log. Skipping loses nothing: the transaction keeps
   * waiting, its command is sent again and the service answers again.
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
