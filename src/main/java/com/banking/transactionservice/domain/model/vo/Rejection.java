package com.banking.transactionservice.domain.model.vo;

import com.banking.transactionservice.domain.model.enums.RejectedStep;
import java.util.Objects;

/**
 * Why a transaction did not complete, in the words of the service that refused its step.
 *
 * @param code error code of that service, the same its own API answers
 * @param message explanation of that service
 */
public record Rejection(RejectedStep step, String code, String message) {

  static final int MAX_CODE = 60;
  static final int MAX_MESSAGE = 300;

  /** Requires the step and keeps the code and the message within what is stored. */
  public Rejection {
    Objects.requireNonNull(step, "step");
    code = limited(code, MAX_CODE, "UNKNOWN");
    message = limited(message, MAX_MESSAGE, "No reason was given.");
  }

  // A missing text gets a fixed one, so a rejection always says something.
  static String limited(String text, int maxLength, String missing) {
    if (text == null || text.isBlank()) {
      return missing;
    }
    String stripped = text.strip();
    return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength);
  }
}
