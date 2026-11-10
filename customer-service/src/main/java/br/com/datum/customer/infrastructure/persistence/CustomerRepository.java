package br.com.datum.customer.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.Optional;

// A interface expõe apenas as operações necessárias; não oferece DELETE físico.
public interface CustomerRepository extends Repository<CustomerEntity, Long> {

    CustomerEntity save(CustomerEntity customer);

    CustomerEntity saveAndFlush(CustomerEntity customer);

    Optional<CustomerEntity> findByIdAndDeletedAtIsNull(Long id);

    Page<CustomerEntity> findAllByDeletedAtIsNull(Pageable pageable);

    // Inclui os excluídos: a unicidade do CPF continua valendo no histórico.
    boolean existsByCpf(String cpf);
}
