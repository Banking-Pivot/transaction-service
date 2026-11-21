package com.banking.transactionservice.application.handler.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.transactionservice.application.exception.TransactionNotFoundException;
import com.banking.transactionservice.application.query.ListTransactionsQuery;
import com.banking.transactionservice.application.query.PageRequest;
import com.banking.transactionservice.support.InMemoryTransactionStore;
import com.banking.transactionservice.support.Transactions;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetTransactionHandlerTest {

  private static final UUID ID = UUID.fromString("4a6c8e0b-2d4f-4b1a-9c3e-5f7a9b1c3d5e");

  private final InMemoryTransactionStore store = new InMemoryTransactionStore();

  @Test
  void aStoredTransactionIsReturned() {
    store.put(Transactions.pendingDebit(ID));

    assertThat(new GetTransactionHandler(store).execute(ID).block().getId()).isEqualTo(ID);
    assertThat(
            new ListTransactionsHandler(store)
                .execute(new ListTransactionsQuery(PageRequest.of(null, null), null, null, null))
                .block()
                .totalElements())
        .isEqualTo(1);
  }

  @Test
  void anUnknownIdIsNotFound() {
    assertThatThrownBy(() -> new GetTransactionHandler(store).execute(ID).block())
        .isInstanceOf(TransactionNotFoundException.class)
        .hasMessageContaining(ID.toString());
  }
}
