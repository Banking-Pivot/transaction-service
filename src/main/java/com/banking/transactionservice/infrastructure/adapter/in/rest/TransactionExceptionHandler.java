package com.banking.transactionservice.infrastructure.adapter.in.rest;

import com.banking.transactionservice.application.exception.OperationIdConflictException;
import com.banking.transactionservice.application.exception.TransactionNotFoundException;
import com.banking.transactionservice.domain.exception.TransactionRuleViolationException;
import com.banking.transactionservice.infrastructure.adapter.in.rest.generated.model.ErrorResponse;
import com.banking.transactionservice.infrastructure.correlation.CorrelationId;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.MissingRequestValueException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;

/**
 * Maps every failure to the contract's ErrorResponse with a stable code.
 *
 * <p>Also receives errors raised before the delegate runs (415 from request mapping, 400 from
 * parameter validation), because WebFlux routes dispatch errors to {@code @ExceptionHandler}
 * methods. Messages never echo free text from the request, such as descriptions.
 */
@Slf4j
@RestControllerAdvice
public class TransactionExceptionHandler {

  private final Clock clock;

  public TransactionExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  /** Answers 404 {@code TRANSACTION_NOT_FOUND}. */
  @ExceptionHandler(TransactionNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleNotFound(
      TransactionNotFoundException exception, ServerWebExchange exchange) {
    return error(
        HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "The transaction does not exist.", exchange);
  }

  /**
   * Answers 409 {@code OPERATION_ID_CONFLICT} when the operationId already identifies a transaction
   * with different data.
   */
  @ExceptionHandler(OperationIdConflictException.class)
  public ResponseEntity<ErrorResponse> handleOperationIdConflict(
      OperationIdConflictException exception, ServerWebExchange exchange) {
    return error(
        HttpStatus.CONFLICT,
        "OPERATION_ID_CONFLICT",
        "The operationId was already used with different data.",
        exchange);
  }

  /**
   * Answers 422 for a broken business rule, with the name of the rule as the code and the message
   * of the domain.
   */
  @ExceptionHandler(TransactionRuleViolationException.class)
  public ResponseEntity<ErrorResponse> handleBusinessRule(
      TransactionRuleViolationException exception, ServerWebExchange exchange) {
    return error(
        HttpStatus.UNPROCESSABLE_CONTENT,
        exception.violation().name(),
        exception.getMessage(),
        exchange);
  }

  /** Answers 400 {@code INVALID_REQUEST} for a missing value. */
  @ExceptionHandler(MissingRequestValueException.class)
  public ResponseEntity<ErrorResponse> handleMissingValue(
      MissingRequestValueException exception, ServerWebExchange exchange) {
    return invalidRequest("The request is missing a required value.", exchange);
  }

  /**
   * Answers 400 {@code INVALID_REQUEST} for a body that fails validation, naming the fields but not
   * their values.
   */
  @ExceptionHandler(WebExchangeBindException.class)
  public ResponseEntity<ErrorResponse> handleInvalidBody(
      WebExchangeBindException exception, ServerWebExchange exchange) {
    String fields =
        exception.getFieldErrors().stream()
            .map(FieldError::getField)
            .distinct()
            .sorted()
            .collect(Collectors.joining(", "));
    return invalidRequest("Invalid or missing fields: " + fields + ".", exchange);
  }

  /** Answers 400 {@code INVALID_REQUEST} for a body or a parameter that cannot be read. */
  @ExceptionHandler(ServerWebInputException.class)
  public ResponseEntity<ErrorResponse> handleInvalidInput(
      ServerWebInputException exception, ServerWebExchange exchange) {
    return invalidRequest(
        "The request is malformed or contains invalid, unknown or missing fields.", exchange);
  }

  /** Answers 400 {@code INVALID_REQUEST} for a parameter that breaks a constraint. */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> handleConstraintViolation(
      ConstraintViolationException exception, ServerWebExchange exchange) {
    return invalidRequest("The request contains invalid parameters.", exchange);
  }

  /**
   * Answers 400 {@code INVALID_REQUEST} for invalid request parameters. An invalid return value is
   * a fault of this service and answers 500 {@code INTERNAL_ERROR}.
   */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ErrorResponse> handleMethodValidation(
      HandlerMethodValidationException exception, ServerWebExchange exchange) {
    if (exception.isForReturnValue()) {
      return unexpected(exception, exchange);
    }
    return invalidRequest("The request contains invalid parameters.", exchange);
  }

  /** Answers 415 {@code UNSUPPORTED_MEDIA_TYPE} when the request body is not JSON. */
  @ExceptionHandler(UnsupportedMediaTypeStatusException.class)
  public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
      UnsupportedMediaTypeStatusException exception, ServerWebExchange exchange) {
    return error(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "UNSUPPORTED_MEDIA_TYPE",
        "Request body must use application/json.",
        exchange);
  }

  /**
   * Keeps the 4xx status WebFlux raised, such as 404 for an unknown route, with the name of the
   * status as the code. A 5xx or unknown status answers 500 {@code INTERNAL_ERROR}.
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> handleHttpError(
      ResponseStatusException exception, ServerWebExchange exchange) {
    HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
    if (status == null || status.is5xxServerError()) {
      return unexpected(exception, exchange);
    }
    return error(status, status.name(), status.getReasonPhrase() + ".", exchange);
  }

  /** Answers 500 {@code INTERNAL_ERROR} for anything else, without exposing why. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(
      Exception exception, ServerWebExchange exchange) {
    return unexpected(exception, exchange);
  }

  private ResponseEntity<ErrorResponse> unexpected(
      Exception exception, ServerWebExchange exchange) {
    // No payloads or SQL messages in the log; the type is enough to correlate with the request.
    log.error(
        "Unhandled transaction request failure. corrId={}, exceptionType={}",
        CorrelationId.of(exchange),
        exception.getClass().getName());
    return error(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "The request could not be completed.",
        exchange);
  }

  private ResponseEntity<ErrorResponse> invalidRequest(String message, ServerWebExchange exchange) {
    return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, exchange);
  }

  private ResponseEntity<ErrorResponse> error(
      HttpStatus status, String code, String message, ServerWebExchange exchange) {
    ErrorResponse response =
        new ErrorResponse()
            .code(code)
            .message(message)
            .timestamp(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
            .path(exchange.getRequest().getPath().value())
            .traceId(CorrelationId.of(exchange));
    return ResponseEntity.status(status).body(response);
  }
}
