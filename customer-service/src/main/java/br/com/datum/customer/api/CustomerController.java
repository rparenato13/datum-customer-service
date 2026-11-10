package br.com.datum.customer.api;

import br.com.datum.customer.application.CustomerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CreateCustomerRequest request) {
        var customer = service.create(request.name(), request.cpf(), request.email(), request.status());
        return ResponseEntity.created(URI.create("/customers/" + customer.getId()))
                .body(CustomerResponse.from(customer));
    }

    @GetMapping("/{id}")
    public CustomerResponse findById(@PathVariable("id") @Positive Long id) {
        return CustomerResponse.from(service.findById(id));
    }

    @PutMapping("/{id}")
    public CustomerResponse update(@PathVariable("id") @Positive Long id,
            @Valid @RequestBody UpdateCustomerRequest request) {
        return CustomerResponse.from(service.update(id, request.name(), request.cpf(), request.email(), request.status()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") @Positive Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
