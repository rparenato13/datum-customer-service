package br.com.datum.customer.application;

import br.com.datum.customer.domain.CustomerStatus;
import br.com.datum.customer.infrastructure.persistence.CustomerEntity;
import br.com.datum.customer.infrastructure.persistence.CustomerRepository;
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
}
