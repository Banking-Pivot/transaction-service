package com.banking.transactionservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Starts Transaction Service, which coordinates the operations that move money between products
 * kept by different services and records how each one ended.
 */
@SpringBootApplication
public class TransactionServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(TransactionServiceApplication.class, args);
  }
}
