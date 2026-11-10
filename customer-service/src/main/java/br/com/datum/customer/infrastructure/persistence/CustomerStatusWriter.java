package br.com.datum.customer.infrastructure.persistence;

import br.com.datum.customer.application.status.StatusChangeEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public class CustomerStatusWriter {
    private final JdbcTemplate jdbc;

    public CustomerStatusWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> lockAvailableVersion(long id) {
        return jdbc.query("SELECT version FROM customers WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
                (rs, index) -> rs.getLong("version"), id).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean change(StatusChangeEvent event) {
        // Incrementa inclusive para status igual ao atual. O lock/CAS usa a mesma
        // linha que PUT/DELETE, e nenhuma entidade JPA fica em cache neste fluxo.
        return jdbc.update("""
                UPDATE customers SET status = ?, version = version + 1,
                    updated_at = greatest(clock_timestamp(), updated_at + interval '1 microsecond')
                WHERE id = ? AND version = ? AND deleted_at IS NULL
                """, event.status().name(), event.customerId(), event.expectedVersion()) == 1;
    }
}
