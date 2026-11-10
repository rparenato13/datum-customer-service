package br.com.datum.customer.application;

import br.com.datum.customer.domain.CustomerStatus;
import br.com.datum.customer.infrastructure.persistence.CustomerEntity;
import br.com.datum.customer.infrastructure.persistence.CustomerRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private final CustomerRepository repository;

    public CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public CustomerEntity create(String name, String cpf, String email, CustomerStatus status) {
        var customer = new CustomerEntity(name, cpf, email);
        customer.changeStatus(status);
        // A constraint do banco garante unicidade também sob concorrência.
        return repository.saveAndFlush(customer);
    }

    @Transactional(readOnly = true)
    public CustomerEntity findById(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id).orElseThrow(CustomerNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Page<CustomerEntity> list(CustomerStatus status, int page, int size) {
        var pagination = pagination(page, size);
        return status == null ? repository.findAllByDeletedAtIsNull(pagination)
                : repository.findAllByStatusAndDeletedAtIsNull(status, pagination);
    }

    @Transactional(readOnly = true)
    public Page<CustomerEntity> search(String name, CustomerStatus status, int page, int size) {
        if (name == null || name.isBlank()) {
            throw new InvalidCustomerQueryException();
        }
        var pagination = pagination(page, size);
        return status == null
                ? repository.findAllByNameNormalizedContainingIgnoreCaseAndDeletedAtIsNull(name.strip(), pagination)
                : repository.findAllByNameNormalizedContainingIgnoreCaseAndStatusAndDeletedAtIsNull(name.strip(), status, pagination);
    }

    @Transactional
    public CustomerEntity update(Long id, String name, String cpf, String email, CustomerStatus status) {
        var customer = repository.findAvailableByIdForUpdate(id).orElseThrow(CustomerNotFoundException::new);
        customer.updateDetails(name, cpf, email);
        customer.changeStatus(status);
        return repository.saveAndFlush(customer);
    }

    @Transactional
    public void delete(Long id) {
        var customer = repository.findAvailableByIdForUpdate(id).orElseThrow(CustomerNotFoundException::new);
        customer.markDeleted();
        repository.saveAndFlush(customer);
    }

    private Pageable pagination(int page, int size) {
        // JPA aceita offset inteiro; rejeitar combinações incompatíveis como 400.
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new InvalidCustomerQueryException();
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id"));
    }
}
