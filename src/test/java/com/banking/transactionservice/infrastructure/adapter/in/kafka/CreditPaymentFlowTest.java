package com.banking.transactionservice.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.RejectedStep;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionResponse;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.TransactionStatus;
import com.banking.transactionservice.infrastructure.correlation.CorrelationId;
import com.banking.transactionservice.support.IntegrationTest;
import com.banking.transactionservice.support.TestParticipants;
import com.banking.transactionservice.support.TestParticipants.ReceivedCommand;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

/**
 * A credit payment from the request to its end, over a real Kafka. The test answers the commands
 * the way Account Service and Credit Service would.
 */
class CreditPaymentFlowTest extends IntegrationTest {

  private static final String RECEIVED = "payment-2026.11_20-A";
  private static final String REPLY_LOGGER =
      "com.banking.transactionservice.application.handler.command.ApplyParticipantReplyHandler";

  @Test
  void aPaymentDebitsTheAccountThenPaysTheCreditAndCompletes() {
    UUID id = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    UUID credit = UUID.randomUUID();

    webTestClient
        .post()
        .uri("/api/v1/transactions/credit-payments")
        .header(CorrelationId.HEADER, RECEIVED)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(payment(id, account, credit))
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals("Location", "/api/v1/transactions/" + id)
        .expectHeader()
        .doesNotExist("Idempotent-Replayed")
        .expectBody()
        .jsonPath("$.id")
        .isEqualTo(id.toString())
        .jsonPath("$.type")
        .isEqualTo("CREDIT_PAYMENT")
        .jsonPath("$.status")
        .isEqualTo("PENDING_DEBIT")
        .jsonPath("$.fee")
        .doesNotExist()
        .jsonPath("$.rejection")
        .doesNotExist();

    ReceivedCommand debit = participants.awaitCommand("DEBIT_ACCOUNT", id);
    assertThat(debit.topic()).isEqualTo(TestParticipants.ACCOUNT_COMMANDS);
    assertThat(debit.key()).isEqualTo(account.toString());
    assertThat(debit.correlationId()).isEqualTo(RECEIVED);
    assertThat(debit.field("operationId")).isEqualTo(id.toString());
    assertThat(debit.field("accountId")).isEqualTo(account.toString());
    assertThat(debit.body().get("amount")).isEqualTo(new BigDecimal("150.00"));
    assertThat(debit.field("currency")).isEqualTo("PEN");
    assertThat(debit.field("description")).isEqualTo("November installment");
    assertThat(debit.body()).doesNotContainKeys("creditId", "originalOperationId");
    assertThat(debit.text()).contains("\"amount\":150.00");

    participants.accountDebited(id, "1.50", "348.50");

    ReceivedCommand pay = participants.awaitCommand("PAY_CREDIT", id);
    assertThat(pay.topic()).isEqualTo(TestParticipants.CREDIT_COMMANDS);
    assertThat(pay.key()).isEqualTo(credit.toString());
    // The identifier of the request, not the one the answer came with.
    assertThat(pay.correlationId()).isEqualTo(RECEIVED);
    assertThat(pay.field("operationId")).isEqualTo(id.toString());
    assertThat(pay.field("creditId")).isEqualTo(credit.toString());
    assertThat(pay.body().get("amount")).isEqualTo(new BigDecimal("150.00"));
    assertThat(pay.body()).doesNotContainKeys("accountId", "originalOperationId");

    participants.creditPaid(id, "850.00");

    TransactionResponse completed = awaitStatus(id, TransactionStatus.COMPLETED);
    assertThat(completed.getFee()).isEqualByComparingTo("1.50");
    assertThat(completed.getAccountBalanceAfter()).isEqualByComparingTo("348.50");
    assertThat(completed.getDebtAfter()).isEqualByComparingTo("850.00");
    assertThat(completed.getRejection()).isNull();
    assertThat(completed.getReversalFailure()).isNull();
    assertThat(completed.getUpdatedAt()).isAfterOrEqualTo(completed.getCreatedAt());
    assertThat(column(id, "version")).isEqualTo(2);
  }

  @Test
  void aRejectedDebitEndsThePaymentAndTheCreditIsNeverAsked() {
    UUID id = requestedPayment();
    participants.awaitCommand("DEBIT_ACCOUNT", id);

    participants.accountDebitRejected(id, "INSUFFICIENT_FUNDS", "The balance does not cover it.");

    TransactionResponse rejected = awaitStatus(id, TransactionStatus.REJECTED);
    assertThat(rejected.getRejection().getStep()).isEqualTo(RejectedStep.DEBIT);
    assertThat(rejected.getRejection().getCode()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(rejected.getRejection().getMessage()).isEqualTo("The balance does not cover it.");
    assertThat(rejected.getFee()).isNull();
    assertThat(rejected.getDebtAfter()).isNull();
    assertThat(participants.countCommands("PAY_CREDIT", id, Duration.ofSeconds(1))).isZero();
    assertThat(participants.countCommands("REVERSE_DEBIT", id, Duration.ZERO)).isZero();
  }

  @Test
  void aRejectedPaymentReturnsTheDebitToTheAccount() {
    UUID id = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    requestPayment(payment(id, account, UUID.randomUUID())).expectStatus().isAccepted();
    participants.awaitCommand("DEBIT_ACCOUNT", id);
    participants.accountDebited(id, "1.50", "348.50");
    participants.awaitCommand("PAY_CREDIT", id);

    participants.creditPaymentRejected(id, "PAYMENT_EXCEEDS_DEBT", "The payment exceeds the debt.");

    ReceivedCommand reversal = participants.awaitCommand("REVERSE_DEBIT", id);
    assertThat(reversal.topic()).isEqualTo(TestParticipants.ACCOUNT_COMMANDS);
    assertThat(reversal.key()).isEqualTo(account.toString());
    assertThat(reversal.field("originalOperationId")).isEqualTo(id.toString());
    assertThat(reversal.field("operationId")).isNotEqualTo(id.toString());
    assertThat(UUID.fromString(reversal.field("operationId"))).isNotNull();
    assertThat(reversal.field("accountId")).isEqualTo(account.toString());
    assertThat(reversal.field("description")).isEqualTo("Credit payment not applied");
    assertThat(reversal.body()).doesNotContainKeys("amount", "currency", "creditId");
    assertThat(transaction(id).getStatus()).isEqualTo(TransactionStatus.PENDING_REVERSAL);

    participants.debitReversed(id, "500.00");

    TransactionResponse reversed = awaitStatus(id, TransactionStatus.REVERSED);
    assertThat(reversed.getRejection().getStep()).isEqualTo(RejectedStep.PAYMENT);
    assertThat(reversed.getRejection().getCode()).isEqualTo("PAYMENT_EXCEEDS_DEBT");
    assertThat(reversed.getAccountBalanceAfter()).isEqualByComparingTo("500.00");
    assertThat(reversed.getFee()).isEqualByComparingTo("1.50");
    assertThat(reversed.getDebtAfter()).isNull();
    assertThat(reversed.getReversalFailure()).isNull();
  }

  @Test
  void aReversalTheAccountRefusesLeavesThePaymentForTheBank() {
    UUID id = requestedPayment();
    participants.awaitCommand("DEBIT_ACCOUNT", id);
    participants.accountDebited(id, "0.00", "350.00");
    participants.awaitCommand("PAY_CREDIT", id);
    participants.creditPaymentRejected(id, "CREDIT_NOT_FOUND", "The credit does not exist.");
    participants.awaitCommand("REVERSE_DEBIT", id);

    participants.debitReversalRejected(id, "ACCOUNT_CLOSED", "The account is closed.");

    TransactionResponse failed = awaitStatus(id, TransactionStatus.REVERSAL_FAILED);
    assertThat(failed.getRejection().getStep()).isEqualTo(RejectedStep.PAYMENT);
    assertThat(failed.getRejection().getCode()).isEqualTo("CREDIT_NOT_FOUND");
    assertThat(failed.getReversalFailure().getCode()).isEqualTo("ACCOUNT_CLOSED");
    assertThat(failed.getReversalFailure().getMessage()).isEqualTo("The account is closed.");
    assertThat(failed.getAccountBalanceAfter()).isEqualByComparingTo("350.00");
  }

  @Test
  void aRejectionWithoutReasonIsStillRecorded() {
    UUID id = requestedPayment();
    participants.awaitCommand("DEBIT_ACCOUNT", id);

    participants.send(
        id.toString(),
        "{\"type\":\"ACCOUNT_DEBIT_REJECTED\",\"transactionId\":\"" + id + "\"}",
        null);

    TransactionResponse rejected = awaitStatus(id, TransactionStatus.REJECTED);
    assertThat(rejected.getRejection().getCode()).isEqualTo("UNKNOWN");
    assertThat(rejected.getRejection().getMessage()).isEqualTo("No reason was given.");
  }

  @Test
  void repeatedAndLateAnswersChangeNothing() {
    UUID id = requestedPayment();
    participants.awaitCommand("DEBIT_ACCOUNT", id);

    participants.accountDebited(id, "1.50", "348.50");
    participants.accountDebited(id, "9.99", "1.00");
    participants.accountDebitRejected(id, "INSUFFICIENT_FUNDS", "Too late.");
    participants.creditPaid(id, "850.00");
    participants.creditPaid(id, "0.00");
    participants.debitReversed(id, "500.00");

    TransactionResponse completed = awaitStatus(id, TransactionStatus.COMPLETED);
    assertThat(completed.getFee()).isEqualByComparingTo("1.50");
    assertThat(completed.getDebtAfter()).isEqualByComparingTo("850.00");
    assertThat(completed.getRejection()).isNull();
    // The answers that came after are read before this one: by then they have been ignored.
    UUID next = requestedPayment();
    participants.awaitCommand("DEBIT_ACCOUNT", next);
    participants.accountDebitRejected(next, "ACCOUNT_BLOCKED", "The account is blocked.");
    awaitStatus(next, TransactionStatus.REJECTED);
    assertThat(transaction(id).getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    assertThat(transaction(id).getDebtAfter()).isEqualByComparingTo("850.00");
    assertThat(column(id, "version")).isEqualTo(2);
    assertThat(participants.countCommands("PAY_CREDIT", id, Duration.ZERO)).isEqualTo(1);
    assertThat(participants.countCommands("REVERSE_DEBIT", id, Duration.ZERO)).isZero();
  }

  @Test
  void answersThatCannotBeReadOrBelongToNoTransactionAreSkipped() {
    UUID id = requestedPayment();
    participants.awaitCommand("DEBIT_ACCOUNT", id);
    UUID stranger = UUID.randomUUID();

    participants.send(id.toString(), "this is not JSON", null);
    participants.send(id.toString(), null, null);
    participants.send(id.toString(), "[]", "list");
    participants.send(id.toString(), "{\"transactionId\":\"" + id + "\"}", "no-type");
    participants.send(
        id.toString(), "{\"type\":\"ACCOUNT_FROZEN\",\"transactionId\":\"" + id + "\"}", null);
    participants.send(id.toString(), "{\"type\":\"ACCOUNT_DEBITED\"}", null);
    participants.send(
        id.toString(), "{\"type\":\"ACCOUNT_DEBITED\",\"transactionId\":\"" + id + "\"}", null);
    participants.send(
        id.toString(), "{\"type\":\"CREDIT_PAID\",\"transactionId\":\"not-a-uuid\"}", null);
    participants.accountDebited(stranger, "1.50", "348.50");
    assertThat(transaction(id).getStatus()).isEqualTo(TransactionStatus.PENDING_DEBIT);

    // The listener is still alive and reads what comes after.
    participants.accountDebited(id, "1.50", "348.50");

    awaitStatus(id, TransactionStatus.PENDING_PAYMENT);
    webTestClient
        .get()
        .uri("/api/v1/transactions/{id}", stranger)
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void theLogOfAnAnswerCarriesTheIdentifierOfItsMessage() {
    UUID id = requestedPayment();
    participants.awaitCommand("DEBIT_ACCOUNT", id);
    ListAppender<ILoggingEvent> lines = new ListAppender<>();
    lines.start();
    Logger handler = (Logger) LoggerFactory.getLogger(REPLY_LOGGER);
    handler.addAppender(lines);

    try {
      participants.send(
          id.toString(),
          "{\"type\":\"ACCOUNT_DEBIT_REJECTED\",\"transactionId\":\""
              + id
              + "\",\"code\":\"ACCOUNT_BLOCKED\",\"message\":\"The account is blocked.\"}",
          "reply-7");
      awaitStatus(id, TransactionStatus.REJECTED);
    } finally {
      handler.detachAppender(lines);
    }

    assertThat(lines.list)
        .isNotEmpty()
        .allSatisfy(
            line ->
                assertThat(line.getMDCPropertyMap()).containsEntry(CorrelationId.KEY, "reply-7"));
  }
}
