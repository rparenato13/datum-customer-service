package br.com.datum.customer.application;

public class InvalidCustomerQueryException extends RuntimeException {

    public InvalidCustomerQueryException() {
        super("Parâmetros de consulta inválidos.");
    }
}
