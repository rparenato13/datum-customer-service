package br.com.datum.customer.api;

import br.com.datum.customer.application.CustomerNotFoundException;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Comparator;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(CustomerNotFoundException.class)
    ResponseEntity<Object> notFound(CustomerNotFoundException ex, WebRequest request) {
        return respond(ex, problem(HttpStatus.NOT_FOUND, "Cliente não encontrado",
                "Cliente não encontrado.", "CUSTOMER_NOT_FOUND"), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> integrityViolation(DataIntegrityViolationException ex, WebRequest request) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "uk_customers_cpf".equals(violation.getConstraintName())) {
                return respond(ex, problem(HttpStatus.CONFLICT, "CPF já cadastrado",
                        "Já existe um cliente cadastrado com este CPF.", "CPF_ALREADY_REGISTERED"), request);
            }
        }
        return unexpected(ex, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var body = problem(HttpStatus.BAD_REQUEST, "Dados inválidos",
                "Revise os campos informados.", "VALIDATION_ERROR");
        body.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", error.getDefaultMessage()))
                .distinct().sorted(Comparator.comparing(error -> error.get("field")))
                .toList());
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, problem(HttpStatus.BAD_REQUEST, "Corpo inválido",
                "Informe um JSON válido com os campos esperados e status ACTIVE ou INACTIVE.",
                "INVALID_JSON"), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, invalidId(), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, invalidId(), headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
        // Mensagens e causas podem conter dados pessoais; registrar somente o tipo.
        LOG.error("Falha inesperada na API. Tipo: {}", ex.getClass().getSimpleName());
        return respond(ex, problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno",
                "Não foi possível concluir a operação.", "INTERNAL_ERROR"), request);
    }

    private ProblemDetail invalidId() {
        return problem(HttpStatus.BAD_REQUEST, "Identificador inválido",
                "O identificador deve ser um número inteiro positivo.", "INVALID_REQUEST");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
        var body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        body.setProperty("code", code);
        return body;
    }

    private ResponseEntity<Object> respond(Exception ex, ProblemDetail body, WebRequest request) {
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatusCode.valueOf(body.getStatus()), request);
    }
}
