package br.com.datum.customer.infrastructure.persistence;

import br.com.datum.customer.domain.CustomerStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

// A interface expõe apenas as operações necessárias; não oferece DELETE físico.
public interface CustomerRepository extends Repository<CustomerEntity, Long> {

    CustomerEntity save(CustomerEntity customer);

    CustomerEntity saveAndFlush(CustomerEntity customer);

    Optional<CustomerEntity> findByIdAndDeletedAtIsNull(Long id);

    // Serializa PUT/DELETE do mesmo cliente; o filtro é reavaliado após esperar o lock.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select customer from CustomerEntity customer where customer.id = :id and customer.deletedAt is null")
    Optional<CustomerEntity> findAvailableByIdForUpdate(@Param("id") Long id);

    Page<CustomerEntity> findAllByDeletedAtIsNull(Pageable pageable);

    Page<CustomerEntity> findAllByStatusAndDeletedAtIsNull(CustomerStatus status, Pageable pageable);

    Page<CustomerEntity> findAllByNameNormalizedContainingIgnoreCaseAndDeletedAtIsNull(String name, Pageable pageable);

    Page<CustomerEntity> findAllByNameNormalizedContainingIgnoreCaseAndStatusAndDeletedAtIsNull(
            String name, CustomerStatus status, Pageable pageable);

    // Inclui os excluídos: a unicidade do CPF continua valendo no histórico.
    boolean existsByCpf(String cpf);
}
