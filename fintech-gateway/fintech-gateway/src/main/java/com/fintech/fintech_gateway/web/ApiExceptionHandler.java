package com.fintech.fintech_gateway.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

/**
 * Ensures every internal endpoint returns the same JSON error envelope as the
 * gateway pipeline. Without this, validation failures fall through to
 * Spring's default error page, which leaks stack traces and returns an HTML
 * body to API clients.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ErrorResponseWriter errorResponseWriter;

    public ApiExceptionHandler(ErrorResponseWriter errorResponseWriter) {
        this.errorResponseWriter = errorResponseWriter;
    }

    @ExceptionHandler(GatewayRequestException.class)
    public void handleGatewayRequest(GatewayRequestException e,
                                     HttpServletRequest request,
                                     HttpServletResponse response) throws IOException {
        errorResponseWriter.write(response, e.getStatus(), e.getCode(), e.getMessage(),
                RequestIdFilter.get(request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public void handleValidation(MethodArgumentNotValidException e,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null
                ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
                : "Request validation failed.";
        errorResponseWriter.write(response, HttpStatus.BAD_REQUEST,
                "validation_failed", message, RequestIdFilter.get(request));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public void handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                   HttpServletRequest request,
                                   HttpServletResponse response) throws IOException {
        // The parameter name is server-defined. The rejected value is not
        // echoed back: it is attacker-controlled and may contain log/HTML
        // payloads.
        errorResponseWriter.write(response, HttpStatus.BAD_REQUEST,
                "invalid_parameter",
                "Parameter '" + e.getName() + "' has an invalid value.",
                RequestIdFilter.get(request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public void handleUnreadableBody(HttpMessageNotReadableException e,
                                     HttpServletRequest request,
                                     HttpServletResponse response) throws IOException {
        errorResponseWriter.write(response, HttpStatus.BAD_REQUEST,
                "invalid_json", "The request body is not valid JSON.",
                RequestIdFilter.get(request));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public void handleNotFound(NoResourceFoundException e,
                               HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
        errorResponseWriter.write(response, HttpStatus.NOT_FOUND,
                "not_found", "The requested resource does not exist.",
                RequestIdFilter.get(request));
    }

    @ExceptionHandler(Exception.class)
    public void handleUnexpected(Exception e,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        String requestId = RequestIdFilter.get(request);
        log.error("Unhandled error for request {}", requestId, e);
        errorResponseWriter.write(response, HttpStatus.INTERNAL_SERVER_ERROR,
                "internal_error", "The request could not be processed.",
                requestId);
    }
}
