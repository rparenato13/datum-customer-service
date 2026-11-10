package br.com.datum.customer.api;

import br.com.datum.customer.domain.CustomerStatus;
import br.com.datum.customer.infrastructure.persistence.CustomerEntity;

import java.time.Instant;

public record CustomerResponse(Long id, String name, String cpf, String email,
                               CustomerStatus status, Instant createdAt, Instant updatedAt, long version) {

    public static CustomerResponse from(CustomerEntity customer) {
        return new CustomerResponse(customer.getId(), customer.getName(), customer.getCpf(),
                customer.getEmail(), customer.getStatus(), customer.getCreatedAt(), customer.getUpdatedAt(), customer.getVersion());
    }
}
