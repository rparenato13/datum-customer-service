package br.com.datum.customer.api;

import br.com.datum.customer.application.CustomerService;
import br.com.datum.customer.domain.CustomerStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping
    public CustomerPageResponse list(
            @RequestParam(name = "status", required = false)
            @Pattern(regexp = "ACTIVE|INACTIVE", message = "Status deve ser ACTIVE ou INACTIVE.") String status,
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = "Page deve ser maior ou igual a zero.") int page,
            @RequestParam(name = "size", defaultValue = "20")
            @Min(value = 1, message = "Size deve ser maior ou igual a 1.")
            @Max(value = 100, message = "Size deve ser menor ou igual a 100.") int size) {
        return CustomerPageResponse.from(service.list(parseStatus(status), page, size));
    }

    @GetMapping("/search")
    public CustomerPageResponse search(
            @RequestParam(name = "name")
            @NotBlank(message = "Nome é obrigatório para a busca.")
            @Size(max = 150, message = "Nome deve ter no máximo 150 caracteres.") String name,
            @RequestParam(name = "status", required = false)
            @Pattern(regexp = "ACTIVE|INACTIVE", message = "Status deve ser ACTIVE ou INACTIVE.") String status,
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = "Page deve ser maior ou igual a zero.") int page,
            @RequestParam(name = "size", defaultValue = "20")
            @Min(value = 1, message = "Size deve ser maior ou igual a 1.")
            @Max(value = 100, message = "Size deve ser menor ou igual a 100.") int size) {
        return CustomerPageResponse.from(service.search(name, parseStatus(status), page, size));
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

    private CustomerStatus parseStatus(String status) {
        return status == null ? null : CustomerStatus.valueOf(status);
    }
}
