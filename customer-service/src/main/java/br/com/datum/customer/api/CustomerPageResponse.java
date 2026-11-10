package br.com.datum.customer.api;

import br.com.datum.customer.infrastructure.persistence.CustomerEntity;
import org.springframework.data.domain.Page;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description="Página ordenada por ID crescente, sem clientes excluídos logicamente.",
        requiredProperties={"content", "page", "size", "totalElements", "totalPages", "first", "last"})
public record CustomerPageResponse(
        @Schema(description="Clientes desta página; array vazio quando não há resultados.") List<CustomerResponse> content,
        @Schema(description="Índice da página, iniciado em zero.", minimum="0") int page,
        @Schema(description="Tamanho solicitado da página.", minimum="1", maximum="100") int size,
        @Schema(description="Total que corresponde aos filtros, sem excluídos.", minimum="0") long totalElements,
        @Schema(description="Quantidade de páginas para os filtros/tamanho atuais.", minimum="0") int totalPages,
        @Schema(description="true quando page é zero.") boolean first,
        @Schema(description="true na última página, em páginas além do fim ou sem resultados.") boolean last) {

    public static CustomerPageResponse from(Page<CustomerEntity> customers) {
        return new CustomerPageResponse(customers.getContent().stream().map(CustomerResponse::from).toList(),
                customers.getNumber(), customers.getSize(), customers.getTotalElements(), customers.getTotalPages(),
                customers.isFirst(), (long) customers.getNumber() + 1 >= customers.getTotalPages());
    }
}
