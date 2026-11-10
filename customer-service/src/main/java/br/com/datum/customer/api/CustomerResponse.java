package br.com.datum.customer.api;

import br.com.datum.customer.domain.CustomerStatus;
import br.com.datum.customer.infrastructure.persistence.CustomerEntity;

import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description="Cliente não excluído. version é somente leitura e participa do controle de concorrência dos eventos de status.",
        requiredProperties={"id", "name", "cpf", "email", "status", "createdAt", "updatedAt", "version"})
public record CustomerResponse(
        @Schema(minimum="1", maximum="9223372036854775807", accessMode=Schema.AccessMode.READ_ONLY) Long id,
        @Schema(minLength=1, maxLength=150) String name,
        @Schema(pattern="[0-9]{11}", minLength=11, maxLength=11) String cpf,
        @Schema(format="email", maxLength=254) String email,
        CustomerStatus status,
        @Schema(description="Instante UTC de criação, preservado nas alterações.", accessMode=Schema.AccessMode.READ_ONLY) Instant createdAt,
        @Schema(description="Instante UTC da última alteração efetiva.", accessMode=Schema.AccessMode.READ_ONLY) Instant updatedAt,
        @Schema(description="Versão corrente. Clientes existentes na implantação da V3 começaram em 0.", minimum="0",
                maximum="9223372036854775807", accessMode=Schema.AccessMode.READ_ONLY) long version) {

    public static CustomerResponse from(CustomerEntity customer) {
        return new CustomerResponse(customer.getId(), customer.getName(), customer.getCpf(),
                customer.getEmail(), customer.getStatus(), customer.getCreatedAt(), customer.getUpdatedAt(), customer.getVersion());
    }
}
