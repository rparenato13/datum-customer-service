package br.com.datum.customer.api;

import br.com.datum.customer.infrastructure.persistence.CustomerEntity;
import org.springframework.data.domain.Page;

import java.util.List;

public record CustomerPageResponse(List<CustomerResponse> content, int page, int size,
                                   long totalElements, int totalPages, boolean first, boolean last) {

    public static CustomerPageResponse from(Page<CustomerEntity> customers) {
        return new CustomerPageResponse(customers.getContent().stream().map(CustomerResponse::from).toList(),
                customers.getNumber(), customers.getSize(), customers.getTotalElements(), customers.getTotalPages(),
                customers.isFirst(), (long) customers.getNumber() + 1 >= customers.getTotalPages());
    }
}
