package com.banking.transactionservice.application.handler.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.transactionservice.application.command.MoveFundsCommand;
import com.banking.transactionservice.domain.model.Transaction;
import com.banking.transactionservice.domain.model.enums.TransactionStatus;
import com.banking.transactionservice.domain.model.enums.TransactionType;
import com.banking.transactionservice.domain.model.vo.FundsMovementOutcome;
import com.banking.transactionservice.domain.model.vo.ParticipantCommand;
import com.banking.transactionservice.support.InMemoryTransactionStore;
import com.banking.transactionservice.support.RecordingPublisher;
import com.banking.transactionservice.support.Transactions;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MoveFundsHandlerTest {

  private static final UUID ID = UUID.fromString("5e7a9c1b-3d5f-4b2a-8c4e-6f8a0b2c4d6e");

  private final InMemoryTransactionStore store = new InMemoryTransactionStore();
  private final RecordingPublisher publisher = new RecordingPublisher();
  private final MoveFundsHandler handler =
      new MoveFundsHandler(
          store,
          new CommandDispatcher(publisher, publisher),
          Clock.fixed(Transactions.START, ZoneOffset.UTC),
          () -> Transactions.REVERSAL);

  @Test
  void aNewRequestIsStoredAndBothCardsAreAskedAboutWithItsCorrelationId() {
    handler.execute(command(Transactions.CARD, Transactions.OTHER_CARD, "60.00", "PEN")).block();

    Transaction stored = store.get(ID);
    assertThat(stored.getType()).isEqualTo(TransactionType.WALLET_PAYMENT);
    assertThat(stored.getStatus()).isEqualTo(TransactionStatus.PENDING_CARD);
    assertThat(stored.getCorrelationId()).isEqualTo("yanki-1");
    assertThat(stored.getReversalOperationId()).isEqualTo(Transactions.REVERSAL);
    assertThat(publisher.commands())
        .containsExactly(
            new ParticipantCommand.ResolveCard(ID, Transactions.CARD),
            new ParticipantCommand.ResolveCard(ID, Transactions.OTHER_CARD));
    assertThat(publisher.sent()).allMatch(sending -> sending.correlationId().equals("yanki-1"));
    assertThat(publisher.outcomes()).isEmpty();
  }

  @Test
  void aRepeatOfAMovementInProgressDoesNothing() {
    store.put(Transactions.walletPendingCredit(ID));

    handler.execute(command(Transactions.CARD, Transactions.OTHER_CARD, "60.00", "PEN")).block();

    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.PENDING_CREDIT);
    assertThat(publisher.sent()).isEmpty();
    assertThat(publisher.outcomes()).isEmpty();
  }

  @Test
  void aRepeatOfAMovementThatEndedIsAnsweredAgain() {
    Transaction completed =
        Transactions.walletPendingCredit(ID)
            .credited(new BigDecimal("0.25"), Transactions.START.plusSeconds(4))
            .orElseThrow();
    store.put(completed);

    handler.execute(command(Transactions.CARD, Transactions.OTHER_CARD, "60.00", "PEN")).block();
    handler.execute(command(Transactions.CARD, Transactions.OTHER_CARD, "60.00", "PEN")).block();

    assertThat(publisher.sent()).isEmpty();
    assertThat(publisher.outcomes())
        .containsExactly(completed.outcome().orElseThrow(), completed.outcome().orElseThrow());
  }

  @Test
  void theSameIdWithOtherDataIsAnsweredAsAConflictAndTheMovementIsLeftAlone() {
    store.put(Transactions.walletPendingCredit(ID));

    handler.execute(command(Transactions.CARD, Transactions.OTHER_CARD, "61.00", "PEN")).block();
    handler.execute(command(Transactions.CARD, null, "60.00", "PEN")).block();

    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.PENDING_CREDIT);
    assertThat(publisher.sent()).isEmpty();
    assertThat(publisher.outcomes())
        .hasSize(2)
        .allSatisfy(
            outcome ->
                assertThat(outcome)
                    .isInstanceOfSatisfying(
                        FundsMovementOutcome.Rejected.class,
                        rejected ->
                            assertThat(rejected.code())
                                .isEqualTo(FundsMovementOutcome.Rejected.OPERATION_ID_CONFLICT)));
  }

  @Test
  void anIdThatBelongsToAnotherKindOfTransactionIsAConflict() {
    store.put(Transactions.pendingDebit(ID));

    handler.execute(command(Transactions.CARD, null, "150.00", "PEN")).block();

    assertThat(store.get(ID).getType()).isEqualTo(TransactionType.CREDIT_PAYMENT);
    assertThat(publisher.outcomes())
        .singleElement()
        .isInstanceOfSatisfying(
            FundsMovementOutcome.Rejected.class,
            rejected ->
                assertThat(rejected.code())
                    .isEqualTo(FundsMovementOutcome.Rejected.OPERATION_ID_CONFLICT));
  }

  @Test
  void aRequestThatCannotBeAcceptedIsAnsweredAndNothingIsStored() {
    handler.execute(command(null, null, "60.00", "PEN")).block();
    handler.execute(command(Transactions.CARD, null, "60.00", "EUR")).block();
    handler.execute(command(Transactions.CARD, null, "60.00", null)).block();
    handler.execute(command(Transactions.CARD, null, "0.00", "PEN")).block();
    handler.execute(command(Transactions.CARD, null, "1.005", "PEN")).block();
    handler.execute(command(Transactions.CARD, null, null, "PEN")).block();

    assertThat(store.get(ID)).isNull();
    assertThat(publisher.sent()).isEmpty();
    assertThat(publisher.outcomes())
        .hasSize(6)
        .allSatisfy(
            outcome -> {
              assertThat(outcome.transactionId()).isEqualTo(ID);
              assertThat(outcome)
                  .isInstanceOfSatisfying(
                      FundsMovementOutcome.Rejected.class,
                      rejected ->
                          assertThat(rejected.code())
                              .isEqualTo(FundsMovementOutcome.Rejected.INVALID_REQUEST));
            });
  }

  @Test
  void theRequestIsStoredEvenWhenTheCardCannotBeAskedYet() {
    publisher.goDown();

    handler.execute(command(Transactions.CARD, null, "60.00", "PEN")).block();

    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.PENDING_CARD);
  }

  @Test
  void anAnswerThatCannotBeSentDoesNotFailTheRequest() {
    publisher.outcomesGoDown();
    store.put(
        Transactions.walletPendingCredit(ID)
            .credited(BigDecimal.ZERO, Transactions.START.plusSeconds(4))
            .orElseThrow());

    handler.execute(command(Transactions.CARD, Transactions.OTHER_CARD, "60.00", "PEN")).block();
    handler.execute(command(null, null, "60.00", "PEN")).block();

    assertThat(publisher.outcomes()).isEmpty();
    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.COMPLETED);
  }

  private static MoveFundsCommand command(
      UUID sourceCardId, UUID destinationCardId, String amount, String currency) {
    return new MoveFundsCommand(
        ID,
        sourceCardId,
        destinationCardId,
        amount == null ? null : new BigDecimal(amount),
        currency,
        "Yanki payment",
        "yanki-1");
  }
}
