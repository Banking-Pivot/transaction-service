package com.banking.transactionservice.support;

import com.banking.transactionservice.application.port.out.CommandPublisherPort;
import com.banking.transactionservice.domain.model.ParticipantCommand;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;

/** Remembers the commands it was asked to send; it can also refuse to send. */
public final class RecordingPublisher implements CommandPublisherPort {

  private final List<Sent> sent = new ArrayList<>();
  private boolean down;

  /** From now on every send fails, as when Kafka cannot be reached. */
  public void goDown() {
    down = true;
  }

  public List<Sent> sent() {
    return List.copyOf(sent);
  }

  public ParticipantCommand last() {
    return sent.get(sent.size() - 1).command();
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

  /** One command and the correlation identifier it was sent with. */
  public record Sent(ParticipantCommand command, String correlationId) {}
}
