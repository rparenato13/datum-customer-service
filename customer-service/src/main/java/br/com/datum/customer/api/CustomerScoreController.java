package br.com.datum.customer.api;

import br.com.datum.customer.application.score.CustomerScoreService;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/customers")
public class CustomerScoreController {

    private final CustomerScoreService service;

    public CustomerScoreController(CustomerScoreService service) {
        this.service = service;
    }

    @GetMapping("/{id}/score")
    public ResponseEntity<CustomerScoreResponse> find(@PathVariable("id") @Positive Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(CustomerScoreResponse.from(id, service.findByCustomerId(id)));
    }
}
