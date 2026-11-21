package com.banking.transactionservice.application.handler.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.transactionservice.application.command.RequestCreditPaymentCommand;
import com.banking.transactionservice.application.exception.OperationIdConflictException;
import com.banking.transactionservice.application.model.RequestedTransaction;
import com.banking.transactionservice.domain.exception.TransactionRuleViolationException;
import com.banking.transactionservice.domain.model.CurrencyCode;
import com.banking.transactionservice.domain.model.ParticipantCommand;
import com.banking.transactionservice.domain.model.TransactionStatus;
import com.banking.transactionservice.support.InMemoryTransactionStore;
import com.banking.transactionservice.support.RecordingPublisher;
import com.banking.transactionservice.support.Transactions;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestCreditPaymentHandlerTest {

  private static final UUID ID = UUID.fromString("4a6c8e0b-2d4f-4b1a-9c3e-5f7a9b1c3d5e");

  private final InMemoryTransactionStore store = new InMemoryTransactionStore();
  private final RecordingPublisher publisher = new RecordingPublisher();
  private final RequestCreditPaymentHandler handler =
      new RequestCreditPaymentHandler(
          store,
          new CommandDispatcher(publisher),
          Clock.fixed(Transactions.START, ZoneOffset.UTC),
          () -> Transactions.REVERSAL);

  @Test
  void aNewRequestIsStoredAndTheDebitIsAskedWithItsCorrelationId() {
    RequestedTransaction result = handler.execute(command("150.00", "corr-7")).block();

    assertThat(result.replayed()).isFalse();
    assertThat(result.transaction().getStatus()).isEqualTo(TransactionStatus.PENDING_DEBIT);
    assertThat(store.get(ID).getReversalOperationId()).isEqualTo(Transactions.REVERSAL);
    assertThat(publisher.sent()).hasSize(1);
    assertThat(publisher.sent().get(0).correlationId()).isEqualTo("corr-7");
    assertThat(publisher.last()).isInstanceOf(ParticipantCommand.DebitAccount.class);
  }

  @Test
  void aRepeatWithTheSameDataReturnsTheStoredTransactionAndSendsNothing() {
    store.put(Transactions.pendingPayment(ID));

    RequestedTransaction result = handler.execute(command("150.00", "corr-8")).block();

    assertThat(result.replayed()).isTrue();
    assertThat(result.transaction().getStatus()).isEqualTo(TransactionStatus.PENDING_PAYMENT);
    assertThat(result.transaction().getCorrelationId()).isEqualTo("corr-1");
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void theSameOperationIdWithOtherDataIsAConflict() {
    store.put(Transactions.pendingDebit(ID));

    assertThatThrownBy(() -> handler.execute(command("151.00", "corr-9")).block())
        .isInstanceOf(OperationIdConflictException.class);
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void aRequestThatLosesTheRaceIsAnsweredWithWhatTheWinnerStored() {
    store.anotherRequestStoresFirst(Transactions.pendingDebit(ID));

    RequestedTransaction result = handler.execute(command("150.00", "corr-10")).block();

    assertThat(result.replayed()).isTrue();
    assertThat(result.transaction().getCorrelationId()).isEqualTo("corr-1");
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void aRequestThatLosesTheRaceToOtherDataIsAConflict() {
    store.anotherRequestStoresFirst(
        Transactions.pendingDebit(ID).toBuilder().amount(new BigDecimal("999.00")).build());

    assertThatThrownBy(() -> handler.execute(command("150.00", "corr-11")).block())
        .isInstanceOf(OperationIdConflictException.class);
  }

  @Test
  void theRequestIsAcceptedEvenWhenTheCommandCannotBeSentYet() {
    publisher.goDown();

    RequestedTransaction result = handler.execute(command("150.00", "corr-12")).block();

    assertThat(result.replayed()).isFalse();
    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.PENDING_DEBIT);
  }

  @Test
  void anAmountTheDomainRejectsStoresNothing() {
    assertThatThrownBy(() -> handler.execute(command("0.001", "corr-13")).block())
        .isInstanceOf(TransactionRuleViolationException.class);
    assertThat(store.get(ID)).isNull();
  }

  private static RequestCreditPaymentCommand command(String amount, String correlationId) {
    return new RequestCreditPaymentCommand(
        ID,
        Transactions.ACCOUNT,
        Transactions.CREDIT,
        new BigDecimal(amount),
        CurrencyCode.PEN,
        "November installment",
        correlationId);
  }
}
