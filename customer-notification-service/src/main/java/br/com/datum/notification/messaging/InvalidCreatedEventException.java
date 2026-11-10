package br.com.datum.notification.messaging;

public class InvalidCreatedEventException extends RuntimeException {
    public InvalidCreatedEventException() {
        super("Evento de criacao invalido.");
    }
}
