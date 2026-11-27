package com.banking.transactionservice.domain.model;

/** What a transaction does. */
public enum TransactionType {
  /** Pays a loan or credit card with money taken from a bank account. */
  CREDIT_PAYMENT,
  /** Pays a purchase with a debit card: the money leaves one of the accounts of the card. */
  CARD_PAYMENT,
  /**
   * Moves the money of a Yanki wallet payment whose sender, receiver or both keep it in the main
   * account of a debit card.
   */
  WALLET_PAYMENT
}
