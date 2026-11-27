package com.banking.transactionservice.application.handler.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.transactionservice.domain.model.Transaction;
import com.banking.transactionservice.domain.model.enums.TransactionStatus;
import com.banking.transactionservice.domain.model.vo.FundsMovementOutcome;
import com.banking.transactionservice.domain.model.vo.ParticipantCommand;
import com.banking.transactionservice.domain.model.vo.ParticipantReply;
import com.banking.transactionservice.support.InMemoryTransactionStore;
import com.banking.transactionservice.support.RecordingPublisher;
import com.banking.transactionservice.support.Transactions;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What goes out after each answer of a card payment and of a movement of wallet funds. */
class CardAndWalletRepliesTest {

  private static final UUID ID = UUID.fromString("5e7a9c1b-3d5f-4b2a-8c4e-6f8a0b2c4d6e");
  private static final Clock CLOCK = Clock.fixed(Transactions.START.plusSeconds(5), ZoneOffset.UTC);

  private final InMemoryTransactionStore store = new InMemoryTransactionStore();
  private final RecordingPublisher publisher = new RecordingPublisher();
  private final ApplyParticipantReplyHandler handler =
      new ApplyParticipantReplyHandler(store, new CommandDispatcher(publisher, publisher), CLOCK);

  @Test
  void nothingIsSentUntilBothCardsOfAWalletPaymentAreKnown() {
    store.put(Transactions.walletPayment(ID, Transactions.CARD, Transactions.OTHER_CARD));

    handler.execute(resolved(Transactions.OTHER_CARD, Transactions.DESTINATION)).block();

    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.PENDING_CARD);
    assertThat(store.get(ID).getDestinationAccountId()).isEqualTo(Transactions.DESTINATION);
    assertThat(publisher.sent()).isEmpty();

    handler.execute(resolved(Transactions.CARD, Transactions.ACCOUNT)).block();

    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.PENDING_DEBIT);
    assertThat(publisher.commands())
        .singleElement()
        .isInstanceOfSatisfying(
            ParticipantCommand.DebitAccount.class,
            debit -> assertThat(debit.accountId()).isEqualTo(Transactions.ACCOUNT));
    assertThat(publisher.outcomes()).isEmpty();
  }

  @Test
  void anAccountWithoutFundsMakesTheNextAccountOfTheCardBeAsked() {
    Transaction waiting = Transactions.cardPaymentPendingDebit(ID);
    store.put(waiting);
    UUID firstDebit = debitOf(waiting).operationId();

    handler
        .execute(
            new ParticipantReply.AccountDebitRejected(
                ID, firstDebit, "INSUFFICIENT_FUNDS", "No funds."))
        .block();

    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.PENDING_DEBIT);
    assertThat(store.get(ID).getAccountId()).isEqualTo(Transactions.SECOND_ACCOUNT);
    assertThat(publisher.commands())
        .singleElement()
        .isInstanceOfSatisfying(
            ParticipantCommand.DebitAccount.class,
            debit -> {
              assertThat(debit.accountId()).isEqualTo(Transactions.SECOND_ACCOUNT);
              assertThat(debit.operationId()).isNotEqualTo(firstDebit);
            });

    // The first account answers once more, late: the payment keeps waiting for the second.
    handler
        .execute(
            new ParticipantReply.AccountDebitRejected(
                ID, firstDebit, "INSUFFICIENT_FUNDS", "No funds."))
        .block();

    assertThat(store.get(ID).getAccountId()).isEqualTo(Transactions.SECOND_ACCOUNT);
    assertThat(publisher.sent()).hasSize(1);
  }

  @Test
  void aCardPaymentThatEndsTellsNobody() {
    Transaction waiting = Transactions.cardPaymentPendingDebit(ID);
    store.put(waiting);

    handler
        .execute(
            new ParticipantReply.AccountDebited(
                ID, debitOf(waiting).operationId(), BigDecimal.ZERO, BigDecimal.TEN))
        .block();

    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    assertThat(publisher.sent()).isEmpty();
    assertThat(publisher.outcomes()).isEmpty();
  }

  @Test
  void aWalletPaymentThatEndsTellsYankiServiceHowWithItsCorrelationId() {
    store.put(Transactions.walletPendingCredit(ID));

    handler.execute(new ParticipantReply.AccountCredited(ID, new BigDecimal("0.25"))).block();

    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    assertThat(publisher.sent()).isEmpty();
    assertThat(publisher.outcomes())
        .containsExactly(
            new FundsMovementOutcome.Moved(
                ID,
                Transactions.ACCOUNT,
                Transactions.DESTINATION,
                new BigDecimal("60.00"),
                new BigDecimal("0.75"),
                Transactions.START.plusSeconds(5)));
  }

  @Test
  void aRefusedCreditAsksForTheDebitBackAndTheAnswerWaitsForTheReversal() {
    store.put(Transactions.walletPendingCredit(ID));

    handler
        .execute(new ParticipantReply.AccountCreditRejected(ID, "ACCOUNT_NOT_ACTIVE", "Closed."))
        .block();

    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.PENDING_REVERSAL);
    assertThat(publisher.last()).isInstanceOf(ParticipantCommand.ReverseDebit.class);
    assertThat(publisher.outcomes()).isEmpty();

    handler.execute(new ParticipantReply.DebitReversed(ID, new BigDecimal("100.00"))).block();

    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.REVERSED);
    assertThat(publisher.outcomes())
        .containsExactly(new FundsMovementOutcome.Rejected(ID, "ACCOUNT_NOT_ACTIVE", "Closed."));
  }

  @Test
  void aRefusedCardEndsAWalletPaymentAndYankiServiceIsTold() {
    store.put(Transactions.walletPayment(ID, Transactions.CARD, null));

    handler
        .execute(
            new ParticipantReply.CardResolutionRejected(
                ID, Transactions.CARD, "CARD_NOT_ACTIVE", "The card is blocked."))
        .block();

    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.REJECTED);
    assertThat(publisher.outcomes())
        .containsExactly(
            new FundsMovementOutcome.Rejected(ID, "CARD_NOT_ACTIVE", "The card is blocked."));
  }

  @Test
  void theTransactionEndsEvenWhenYankiServiceCannotBeToldYet() {
    publisher.outcomesGoDown();
    store.put(Transactions.walletPendingCredit(ID));

    handler.execute(new ParticipantReply.AccountCredited(ID, BigDecimal.ZERO)).block();

    assertThat(store.get(ID).getStatus()).isEqualTo(TransactionStatus.COMPLETED);
  }

  @Test
  void theCommandsOfTheNewWaitingStatusesAreSentAgainWhenTheyGetNoAnswer() {
    UUID cards = UUID.fromString("00000000-0000-4000-8000-000000000011");
    UUID credit = UUID.fromString("00000000-0000-4000-8000-000000000012");
    store.put(Transactions.walletPayment(cards, Transactions.CARD, Transactions.OTHER_CARD));
    store.put(
        Transactions.walletPendingCredit(credit).toBuilder()
            .commandSentAt(Transactions.START)
            .build());
    ResendPendingCommandsHandler resend =
        new ResendPendingCommandsHandler(
            store,
            new CommandDispatcher(publisher, publisher),
            Clock.fixed(Transactions.START.plusSeconds(60), ZoneOffset.UTC),
            Duration.ofSeconds(30),
            50);

    assertThat(resend.execute().block()).isEqualTo(2);

    assertThat(publisher.commands())
        .hasSize(3)
        .filteredOn(ParticipantCommand.ResolveCard.class::isInstance)
        .containsExactly(
            new ParticipantCommand.ResolveCard(cards, Transactions.CARD),
            new ParticipantCommand.ResolveCard(cards, Transactions.OTHER_CARD));
    assertThat(publisher.commands())
        .filteredOn(ParticipantCommand.CreditAccount.class::isInstance)
        .hasSize(1);
    assertThat(publisher.outcomes()).isEmpty();
  }

  private static ParticipantReply resolved(UUID cardId, UUID mainAccountId) {
    return new ParticipantReply.CardResolved(ID, cardId, mainAccountId, List.of(mainAccountId));
  }

  private static ParticipantCommand.DebitAccount debitOf(Transaction transaction) {
    return (ParticipantCommand.DebitAccount) transaction.nextCommands().get(0);
  }
}
