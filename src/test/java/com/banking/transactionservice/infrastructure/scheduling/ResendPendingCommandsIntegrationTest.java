package com.banking.transactionservice.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.transactionservice.application.port.in.ResendPendingCommandsUseCase;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionStatus;
import com.banking.transactionservice.support.IntegrationTest;
import com.banking.transactionservice.support.TestParticipants.ReceivedCommand;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A command that got no answer is sent again. The tests make a transaction look old in the database
 * and run the re-send themselves; the timer is set to one hour in the test settings.
 */
class ResendPendingCommandsIntegrationTest extends IntegrationTest {

  @Autowired private ResendPendingCommandsUseCase resend;

  @Test
  void aDebitThatGotNoAnswerIsAskedAgainWithTheSameOperationId() {
    UUID waiting = requestedPayment();
    UUID recent = requestedPayment();
    ReceivedCommand original = participants.awaitCommand("DEBIT_ACCOUNT", waiting);
    participants.awaitCommand("DEBIT_ACCOUNT", recent);
    age(waiting);

    assertThat(resend.execute().block()).isEqualTo(1);

    ReceivedCommand repeated = participants.awaitCommand("DEBIT_ACCOUNT", waiting, 2);
    assertThat(repeated.text()).isEqualTo(original.text());
    assertThat(repeated.key()).isEqualTo(original.key());
    assertThat(repeated.correlationId()).isEqualTo(original.correlationId());
    assertThat(column(waiting, "command_attempts")).isEqualTo(1);
    assertThat(column(recent, "command_attempts")).isZero();
    assertThat(participants.countCommands("DEBIT_ACCOUNT", recent, Duration.ofSeconds(1)))
        .isEqualTo(1);
    // Sent a moment ago: the next run leaves it alone.
    assertThat(resend.execute().block()).isZero();

    // The account answers the first command and the repeat with the same result.
    participants.accountDebited(waiting, "1.50", "348.50");
    participants.accountDebited(waiting, "1.50", "348.50");
    awaitStatus(waiting, TransactionStatus.PENDING_PAYMENT);
    assertThat(column(waiting, "command_attempts")).isZero();
  }

  @Test
  void aPaymentAndAReversalThatGotNoAnswerAreAskedAgain() {
    UUID id = requestedPayment();
    participants.awaitCommand("DEBIT_ACCOUNT", id);
    participants.accountDebited(id, "1.50", "348.50");
    ReceivedCommand payment = participants.awaitCommand("PAY_CREDIT", id);
    age(id);

    assertThat(resend.execute().block()).isEqualTo(1);

    assertThat(participants.awaitCommand("PAY_CREDIT", id, 2).text()).isEqualTo(payment.text());
    participants.creditPaymentRejected(id, "CREDIT_NOT_FOUND", "The credit does not exist.");
    ReceivedCommand reversal = participants.awaitCommand("REVERSE_DEBIT", id);
    age(id);

    assertThat(resend.execute().block()).isEqualTo(1);

    // The repeat carries the same reversal operationId, so the account returns the money once.
    assertThat(participants.awaitCommand("REVERSE_DEBIT", id, 2).text()).isEqualTo(reversal.text());
    participants.debitReversed(id, "500.00");
    awaitStatus(id, TransactionStatus.REVERSED);
    age(id);
    assertThat(resend.execute().block()).isZero();
  }

  private void age(UUID transactionId) {
    databaseClient
        .sql(
            "UPDATE transactions SET command_sent_at = command_sent_at - INTERVAL '5 minutes' WHERE"
                + " id = :id")
        .bind("id", transactionId)
        .then()
        .block();
  }
}
