package br.com.datum.customer.api;

import br.com.datum.customer.domain.Score;
import br.com.datum.customer.domain.ScoreClassification;

public record CustomerScoreResponse(Long customerId, int score, ScoreClassification classification) {
    public static CustomerScoreResponse from(Long customerId, Score score) {
        return new CustomerScoreResponse(customerId, score.score(), score.classification());
    }
}
