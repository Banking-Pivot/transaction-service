package com.banking.transactionservice.application.handler.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.transactionservice.application.model.CardMovement;
import com.banking.transactionservice.domain.model.Transaction;
import com.banking.transactionservice.domain.model.enums.CurrencyCode;
import com.banking.transactionservice.domain.model.enums.TransactionType;
import com.banking.transactionservice.support.InMemoryTransactionStore;
import com.banking.transactionservice.support.Transactions;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetLatestCardMovementsHandlerTest {

  private static final UUID PAYMENT = UUID.fromString("00000000-0000-4000-8000-000000000021");
  private static final UUID RECEIVED = UUID.fromString("00000000-0000-4000-8000-000000000022");
  private static final UUID PENDING = UUID.fromString("00000000-0000-4000-8000-000000000023");
  private static final UUID REJECTED = UUID.fromString("00000000-0000-4000-8000-000000000024");

  private final InMemoryTransactionStore store = new InMemoryTransactionStore();
  private final GetLatestCardMovementsHandler handler = new GetLatestCardMovementsHandler(store);

  @Test
  void theCompletedTransactionsOfTheCardAreItsMovementsNewestFirst() {
    // OTHER_CARD pays with the card, then receives a wallet payment from CARD.
    Transaction payment =
        Transactions.cardPayment(PAYMENT).toBuilder()
            .sourceCardId(Transactions.OTHER_CARD)
            .build()
            .cardResolved(
                Transactions.OTHER_CARD,
                Transactions.DESTINATION,
                List.of(Transactions.DESTINATION),
                Transactions.START.plusSeconds(1))
            .flatMap(
                next ->
                    next.debited(
                        new BigDecimal("1.00"),
                        new BigDecimal("20.00"),
                        Transactions.START.plusSeconds(2)))
            .orElseThrow();
    Transaction received =
        Transactions.walletPendingCredit(RECEIVED)
            .credited(new BigDecimal("0.25"), Transactions.START.plusSeconds(30))
            .orElseThrow();
    store.put(payment);
    store.put(received);
    store.put(Transactions.walletPendingCredit(PENDING));
    store.put(
        Transactions.walletPayment(REJECTED, null, Transactions.OTHER_CARD)
            .cardRejected(
                Transactions.OTHER_CARD, "CARD_NOT_ACTIVE", "Blocked.", Transactions.START)
            .orElseThrow());

    List<CardMovement> movements = handler.execute(Transactions.OTHER_CARD).block();

    assertThat(movements)
        .containsExactly(
            new CardMovement(
                RECEIVED,
                TransactionType.WALLET_PAYMENT,
                CardMovement.Direction.CREDIT,
                Transactions.DESTINATION,
                new BigDecimal("60.00"),
                new BigDecimal("0.25"),
                CurrencyCode.PEN,
                "Yanki payment",
                Transactions.START.plusSeconds(30)),
            new CardMovement(
                PAYMENT,
                TransactionType.CARD_PAYMENT,
                CardMovement.Direction.DEBIT,
                Transactions.DESTINATION,
                new BigDecimal("80.00"),
                new BigDecimal("1.00"),
                CurrencyCode.PEN,
                "Grocery store",
                Transactions.START.plusSeconds(2)));
    // The same wallet payment is a debit for the card it left from.
    assertThat(handler.execute(Transactions.CARD).block())
        .singleElement()
        .satisfies(
            movement -> {
              assertThat(movement.direction()).isEqualTo(CardMovement.Direction.DEBIT);
              assertThat(movement.accountId()).isEqualTo(Transactions.ACCOUNT);
              assertThat(movement.fee()).isEqualByComparingTo("0.50");
            });
    assertThat(handler.execute(UUID.randomUUID()).block()).isEmpty();
  }

  @Test
  void theReportShowsAtMostTenMovements() {
    for (int index = 0; index < 12; index++) {
      store.put(
          Transactions.cardPaymentPendingDebit(UUID.randomUUID())
              .debited(BigDecimal.ZERO, BigDecimal.TEN, Transactions.START.plusSeconds(10 + index))
              .orElseThrow());
    }

    List<CardMovement> movements = handler.execute(Transactions.CARD).block();

    assertThat(movements).hasSize(10);
    assertThat(movements.get(0).occurredAt()).isEqualTo(Transactions.START.plusSeconds(21));
    assertThat(movements.get(9).occurredAt()).isEqualTo(Transactions.START.plusSeconds(12));
  }
}
