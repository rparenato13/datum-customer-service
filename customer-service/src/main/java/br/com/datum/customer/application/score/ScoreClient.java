package br.com.datum.customer.application.score;

import br.com.datum.customer.domain.Score;

public interface ScoreClient {
    Score findByCpf(String cpf);
}
