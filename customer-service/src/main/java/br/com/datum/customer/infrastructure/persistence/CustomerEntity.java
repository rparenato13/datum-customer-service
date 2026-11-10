package br.com.datum.customer.infrastructure.persistence;

import br.com.datum.customer.domain.CustomerStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Entity
@Table(name = "customers")
public class CustomerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "name_normalized", insertable = false, updatable = false, length = 150)
    private String nameNormalized;

    @Column(nullable = false, length = 11)
    private String cpf;

    @Column(nullable = false, length = 254)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private CustomerStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected CustomerEntity() {
    }

    public CustomerEntity(String name, String cpf, String email) {
        updateDetails(name, cpf, email);
        this.status = CustomerStatus.ACTIVE;
    }

    public void updateDetails(String name, String cpf, String email) {
        this.name = Objects.requireNonNull(name).strip();
        this.cpf = Objects.requireNonNull(cpf).strip();
        this.email = Objects.requireNonNull(email).strip();
    }

    public void changeStatus(CustomerStatus status) {
        this.status = Objects.requireNonNull(status);
    }

    public void markDeleted() {
        if (deletedAt == null) {
            deletedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
    }

    @PrePersist
    private void onCreate() {
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        updatedAt = createdAt;
    }

    @PreUpdate
    private void onUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getNameNormalized() {
        return nameNormalized;
    }

    public String getCpf() {
        return cpf;
    }

    public String getEmail() {
        return email;
    }

    public CustomerStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
