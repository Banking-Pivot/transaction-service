package com.banking.transactionservice.support;

import com.banking.transactionservice.application.port.out.CommandPublisherPort;
import com.banking.transactionservice.application.port.out.OutcomePublisherPort;
import com.banking.transactionservice.domain.model.FundsMovementOutcome;
import com.banking.transactionservice.domain.model.ParticipantCommand;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Remembers the commands and the outcomes it was asked to send; it can also refuse to send either.
 */
public final class RecordingPublisher implements CommandPublisherPort, OutcomePublisherPort {

  private final List<Sent> sent = new ArrayList<>();
  private final List<FundsMovementOutcome> outcomes = new ArrayList<>();
  private boolean down;
  private boolean outcomesDown;

  /** From now on every send of a command fails, as when Kafka cannot be reached. */
  public void goDown() {
    down = true;
  }

  /** From now on every send of an outcome fails. */
  public void outcomesGoDown() {
    outcomesDown = true;
  }

  public List<Sent> sent() {
    return List.copyOf(sent);
  }

  public List<ParticipantCommand> commands() {
    return sent.stream().map(Sent::command).toList();
  }

  public ParticipantCommand last() {
    return sent.get(sent.size() - 1).command();
  }

  public List<FundsMovementOutcome> outcomes() {
    return List.copyOf(outcomes);
  }

  @Override
  public Mono<Void> publish(ParticipantCommand command, String correlationId) {
    return Mono.defer(
        () -> {
          if (down) {
            return Mono.error(new IllegalStateException("Kafka is not reachable"));
          }
          sent.add(new Sent(command, correlationId));
          return Mono.empty();
        });
  }

  @Override
  public Mono<Void> publish(FundsMovementOutcome outcome, String correlationId) {
    return Mono.defer(
        () -> {
          if (outcomesDown) {
            return Mono.error(new IllegalStateException("Kafka is not reachable"));
          }
          outcomes.add(outcome);
          return Mono.empty();
        });
  }

  /** One command and the correlation identifier it was sent with. */
  public record Sent(ParticipantCommand command, String correlationId) {}
}
