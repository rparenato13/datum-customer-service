package br.com.datum.customer.application.score;

public class ScoreClientException extends RuntimeException {

    public enum Reason { NOT_FOUND, INVALID_RESPONSE, UNAVAILABLE, TIMEOUT }

    private final Reason reason;
    private final boolean retryable;

    public ScoreClientException(Reason reason, boolean retryable) {
        // Nao reter causas HTTP, URLs, CPF, headers ou corpo do provedor.
        super("Falha na consulta de Score: " + reason.name());
        this.reason = reason;
        this.retryable = retryable;
    }

    public Reason reason() {
        return reason;
    }

    public boolean retryable() {
        return retryable;
    }
}
