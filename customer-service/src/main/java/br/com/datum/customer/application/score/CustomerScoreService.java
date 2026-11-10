package br.com.datum.customer.application.score;

import br.com.datum.customer.application.CustomerService;
import br.com.datum.customer.domain.Score;
import org.springframework.stereotype.Service;

@Service
public class CustomerScoreService {

    private final CustomerService customers;
    private final ScoreClient scores;

    public CustomerScoreService(CustomerService customers, ScoreClient scores) {
        this.customers = customers;
        this.scores = scores;
    }

    public Score findByCustomerId(Long id) {
        // A transacao de leitura termina antes de aguardar o provedor HTTP.
        var customer = customers.findById(id);
        return scores.findByCpf(customer.getCpf());
    }
}
