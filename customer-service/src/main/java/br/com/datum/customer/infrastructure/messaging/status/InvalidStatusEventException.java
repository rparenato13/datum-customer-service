package br.com.datum.customer.infrastructure.messaging.status;

public class InvalidStatusEventException extends RuntimeException {
    public InvalidStatusEventException() {
        super("Evento de status invalido.");
    }
}
