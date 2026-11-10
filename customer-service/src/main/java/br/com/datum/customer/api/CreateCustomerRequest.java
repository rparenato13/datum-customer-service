package br.com.datum.customer.api;

import br.com.datum.customer.domain.CustomerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.br.CPF;

@Schema(description="Dados completos do cliente. Campos desconhecidos são recusados; espaços nas extremidades são removidos.",
        additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record CreateCustomerRequest(
        @NotBlank(message = "Nome é obrigatório.")
        @Size(max = 150, message = "Nome deve ter no máximo 150 caracteres.")
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED, description="Nome não vazio após remover espaços nas extremidades.", example="Cliente de Demonstracao", minLength=1, maxLength=150)
        String name,

        @NotBlank(message = "CPF é obrigatório.")
        @Pattern(regexp = "[0-9]{11}", message = "CPF deve conter 11 dígitos, sem máscara.")
        @CPF(message = "CPF possui dígitos verificadores inválidos.")
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED, description="CPF com 11 dígitos, sem máscara e com dígitos verificadores válidos. Unicidade inclui clientes excluídos.", example="16899535009", minLength=11, maxLength=11, pattern="[0-9]{11}")
        String cpf,

        @NotBlank(message = "E-mail é obrigatório.")
        @Size(max = 254, message = "E-mail deve ter no máximo 254 caracteres.")
        @Email(message = "E-mail deve possuir formato válido.")
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED, description="E-mail válido, sem espaços nas extremidades.", example="cliente@example.test", format="email", minLength=1, maxLength=254)
        String email,

        @NotNull(message = "Status é obrigatório.")
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED, description="Estado de negócio, independente da exclusão lógica.", example="ACTIVE")
        CustomerStatus status
) {
    public CreateCustomerRequest {
        name = name == null ? null : name.strip();
        cpf = cpf == null ? null : cpf.strip();
        email = email == null ? null : email.strip();
    }
}
