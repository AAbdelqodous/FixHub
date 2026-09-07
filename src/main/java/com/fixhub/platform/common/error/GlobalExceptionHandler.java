package com.fixhub.platform.common.error;

import com.fixhub.platform.common.web.CorrelationIdFilter;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private record ValidationError(String field, String message) {}

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
        ErrorCode errorCode = ex.getErrorCode();
        ProblemDetail problemDetail =
                createProblemDetail(errorCode, errorCode.status(), ex.getMessage(), request);

        return ResponseEntity.status(errorCode.status()).body(problemDetail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception ex, WebRequest request) {

        logger.error("Unhandled exception", ex);

        ErrorCode errorCode = CommonErrorCode.INTERNAL_ERROR;
        ProblemDetail problemDetail =
                createProblemDetail(
                        errorCode, errorCode.status(), errorCode.defaultDetail(), request);

        return ResponseEntity.status(errorCode.status()).body(problemDetail);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        List<ValidationError> errors =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(
                                fieldError ->
                                        new ValidationError(
                                                fieldError.getField(),
                                                validationMessage(fieldError)))
                        .toList();

        return handleValidationErrors(ex, errors, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        List<ValidationError> errors =
                ex.getParameterValidationResults().stream()
                        .flatMap(this::toValidationErrors)
                        .toList();

        return handleValidationErrors(ex, errors, headers, status, request);
    }

    private Stream<ValidationError> toValidationErrors(ParameterValidationResult result) {
        if (result instanceof ParameterErrors parameterErrors) {
            return parameterErrors.getFieldErrors().stream()
                    .map(
                            fieldError ->
                                    new ValidationError(
                                            fieldError.getField(), validationMessage(fieldError)));
        }

        String parameterName = result.getMethodParameter().getParameterName();
        String field =
                parameterName != null
                        ? parameterName
                        : "arg" + result.getMethodParameter().getParameterIndex();

        return result.getResolvableErrors().stream()
                .map(error -> new ValidationError(field, validationMessage(error)));
    }

    private ResponseEntity<Object> handleValidationErrors(
            Exception ex,
            List<ValidationError> errors,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        ErrorCode errorCode = CommonErrorCode.VALIDATION_ERROR;
        ProblemDetail problemDetail =
                createProblemDetail(errorCode, status, errorCode.defaultDetail(), request);

        problemDetail.setProperty("errors", errors);

        return handleExceptionInternal(ex, problemDetail, headers, status, request);
    }

    private String validationMessage(MessageSourceResolvable error) {
        return Objects.requireNonNullElse(error.getDefaultMessage(), "Invalid value");
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        return handleFrameworkException(
                ex, CommonErrorCode.MALFORMED_REQUEST, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        return handleFrameworkException(
                ex, CommonErrorCode.MISSING_PARAMETER, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        return handleFrameworkException(
                ex, CommonErrorCode.TYPE_MISMATCH, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        return handleFrameworkException(
                ex, CommonErrorCode.METHOD_NOT_ALLOWED, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        return handleFrameworkException(
                ex, CommonErrorCode.UNSUPPORTED_MEDIA_TYPE, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        return handleFrameworkException(
                ex, CommonErrorCode.NOT_ACCEPTABLE, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(
            NoHandlerFoundException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        return handleEndpointNotFound(ex, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        return handleEndpointNotFound(ex, headers, status, request);
    }

    private ResponseEntity<Object> handleEndpointNotFound(
            Exception ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        return handleFrameworkException(
                ex, CommonErrorCode.ENDPOINT_NOT_FOUND, headers, status, request);
    }

    private ResponseEntity<Object> handleFrameworkException(
            Exception ex,
            ErrorCode errorCode,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        ProblemDetail problemDetail =
                createProblemDetail(errorCode, status, errorCode.defaultDetail(), request);

        return handleExceptionInternal(ex, problemDetail, headers, status, request);
    }

    private ProblemDetail createProblemDetail(
            ErrorCode errorCode, HttpStatusCode status, String detail, WebRequest request) {

        Object correlationAttribute =
                request.getAttribute(
                        CorrelationIdFilter.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);

        if (!(correlationAttribute instanceof String correlationId)) {
            throw new IllegalStateException("Correlation ID is missing from the request");
        }

        if (!(request instanceof ServletWebRequest servletWebRequest)) {
            throw new IllegalStateException("Servlet request is required");
        }

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);

        problemDetail.setInstance(URI.create(servletWebRequest.getRequest().getRequestURI()));
        problemDetail.setProperty("code", errorCode.code());
        problemDetail.setProperty("correlationId", correlationId);

        return problemDetail;
    }
}
