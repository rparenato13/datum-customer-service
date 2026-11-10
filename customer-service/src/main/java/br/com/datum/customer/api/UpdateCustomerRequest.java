package br.com.datum.customer.api;

import br.com.datum.customer.domain.CustomerStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.br.CPF;

public record UpdateCustomerRequest(
        @NotBlank(message = "Nome é obrigatório.")
        @Size(max = 150, message = "Nome deve ter no máximo 150 caracteres.")
        String name,

        @NotBlank(message = "CPF é obrigatório.")
        @Pattern(regexp = "[0-9]{11}", message = "CPF deve conter 11 dígitos, sem máscara.")
        @CPF(message = "CPF possui dígitos verificadores inválidos.")
        String cpf,

        @NotBlank(message = "E-mail é obrigatório.")
        @Size(max = 254, message = "E-mail deve ter no máximo 254 caracteres.")
        @Email(message = "E-mail deve possuir formato válido.")
        String email,

        @NotNull(message = "Status é obrigatório.")
        CustomerStatus status
) {
    public UpdateCustomerRequest {
        name = name == null ? null : name.strip();
        cpf = cpf == null ? null : cpf.strip();
        email = email == null ? null : email.strip();
    }
}
