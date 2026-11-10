package br.com.datum.customer.api;

import br.com.datum.customer.domain.Score;
import br.com.datum.customer.domain.ScoreClassification;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description="Score obtido do provedor, sem expor CPF e sem persistir o resultado.",
        requiredProperties={"customerId", "score", "classification"})
public record CustomerScoreResponse(@Schema(minimum="1", maximum="9223372036854775807") Long customerId,
                                    @Schema(minimum="0", maximum="1000") int score,
                                    ScoreClassification classification) {
    public static CustomerScoreResponse from(Long customerId, Score score) {
        return new CustomerScoreResponse(customerId, score.score(), score.classification());
    }
}
