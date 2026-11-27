package com.banking.transactionservice.infrastructure.adapter.in.kafka;

import com.banking.transactionservice.application.command.MoveFundsCommand;
import com.banking.transactionservice.application.port.in.MoveFundsUseCase;
import com.banking.transactionservice.infrastructure.config.TransactionProperties;
import com.banking.transactionservice.infrastructure.correlation.CorrelationId;
import java.time.Duration;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives the movements of wallet funds Yanki Service asks for.
 *
 * <p>Like the listener of answers, the method waits until the request is handled before it returns,
 * on the thread of the Kafka consumer: the position in the topic only moves forward once the
 * transaction is stored. A request that fails is retried by the container (see
 * MessagingConfiguration) and then skipped; Yanki Service asks again.
 *
 * <p>A message that cannot be read (not JSON, another type, no transaction ID) is logged and
 * skipped: there is nobody to answer.
 */
@Component
public class FundsMovementListener {

  private static final Logger LOG = LoggerFactory.getLogger(FundsMovementListener.class);
  private static final Duration LIMIT = Duration.ofSeconds(30);

  private final MoveFundsUseCase moveFunds;
  private final JsonMapper json;
  private final String commandsTopic;

  /** Creates the listener with the use case, the JSON mapper and the name of the topic. */
  public FundsMovementListener(
      MoveFundsUseCase moveFunds, JsonMapper json, TransactionProperties properties) {
    this.moveFunds = moveFunds;
    this.json = json;
    this.commandsTopic = properties.messaging().commandsTopic();
  }

  /** Topic the listener reads, taken from the settings. */
  public String getCommandsTopic() {
    return commandsTopic;
  }

  /**
   * Handles one request. The correlation identifier of its header is in every log line written
   * while it is handled, and it is kept with the transaction.
   *
   * @param message the record read from the commands topic
   */
  @KafkaListener(topics = "#{__listener.commandsTopic}")
  public void onCommand(ConsumerRecord<String, String> message) {
    String correlationId = MessageCorrelation.of(message);
    try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationId.KEY, correlationId)) {
      read(message, correlationId)
          .ifPresent(
              command ->
                  moveFunds
                      .execute(command)
                      .contextWrite(context -> context.put(CorrelationId.KEY, correlationId))
                      .block(LIMIT));
    }
  }

  private Optional<MoveFundsCommand> read(
      ConsumerRecord<String, String> message, String correlationId) {
    try {
      return Optional.of(
          json.readValue(message.value(), MoveFundsMessage.class).toCommand(correlationId));
    } catch (JacksonException | IllegalArgumentException unreadable) {
      // The content is not logged: it may be anything.
      LOG.warn(
          "Command skipped, it cannot be read. topic={}, partition={}, offset={}, reason={}",
          message.topic(),
          message.partition(),
          message.offset(),
          unreadable.getClass().getSimpleName());
      return Optional.empty();
    }
  }
}
