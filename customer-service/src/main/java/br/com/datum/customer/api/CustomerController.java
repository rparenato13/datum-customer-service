package br.com.datum.customer.api;

import br.com.datum.customer.application.CustomerService;
import br.com.datum.customer.domain.CustomerStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name="Clientes", description="Cadastro e consulta de clientes com exclusão lógica.")
@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @Operation(operationId="createCustomer", summary="Cadastrar cliente", description="Acesso ADMIN. Cria cliente com version 0 e evento de criação no outbox na mesma transação. Responde após commit, sem aguardar broker ou notificação. CPF permanece reservado após exclusão lógica.")
    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CreateCustomerRequest request) {
        var customer = service.create(request.name(), request.cpf(), request.email(), request.status());
        return ResponseEntity.created(URI.create("/customers/" + customer.getId()))
                .body(CustomerResponse.from(customer));
    }

    @Operation(operationId="listCustomers", summary="Listar clientes", description="Acesso USER ou ADMIN. Página ordenada por id crescente, sem excluídos, incluindo ACTIVE e INACTIVE. Filtro de status opcional. O produto page × size deve caber em inteiro de 32 bits.")
    @GetMapping
    public CustomerPageResponse list(
            @Parameter(description="Filtrar pelo estado de negócio; omita para ambos.", schema=@Schema(allowableValues={"ACTIVE", "INACTIVE"}))
            @RequestParam(name = "status", required = false)
            @Pattern(regexp = "ACTIVE|INACTIVE", message = "Status deve ser ACTIVE ou INACTIVE.") String status,
            @Parameter(description="Página iniciando em zero; page × size não pode exceder 2147483647.", schema=@Schema(minimum="0", maximum="2147483647", defaultValue="0"))
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = "Page deve ser maior ou igual a zero.") int page,
            @Parameter(description="Quantidade de clientes por página.", schema=@Schema(minimum="1", maximum="100", defaultValue="20"))
            @RequestParam(name = "size", defaultValue = "20")
            @Min(value = 1, message = "Size deve ser maior ou igual a 1.")
            @Max(value = 100, message = "Size deve ser menor ou igual a 100.") int size) {
        return CustomerPageResponse.from(service.list(parseStatus(status), page, size));
    }

    @Operation(operationId="searchCustomers", summary="Buscar clientes por nome", description="Acesso USER ou ADMIN. Busca parcial literal, sem diferenciar maiúsculas/minúsculas, preservando acentos. Remove espaços nas extremidades; name não pode ficar vazio. Exclui registros apagados logicamente; ordena por id crescente. O produto page × size deve caber em inteiro de 32 bits.")
    @GetMapping("/search")
    public CustomerPageResponse search(
            @Parameter(description="Trecho literal do nome; obrigatório e não vazio após trim.", example="ANA", schema=@Schema(minLength=1, maxLength=150))
            @RequestParam(name = "name")
            @NotBlank(message = "Nome é obrigatório para a busca.")
            @Size(max = 150, message = "Nome deve ter no máximo 150 caracteres.") String name,
            @Parameter(description="Filtrar pelo estado de negócio; omita para ambos.", schema=@Schema(allowableValues={"ACTIVE", "INACTIVE"}))
            @RequestParam(name = "status", required = false)
            @Pattern(regexp = "ACTIVE|INACTIVE", message = "Status deve ser ACTIVE ou INACTIVE.") String status,
            @Parameter(description="Página iniciando em zero; page × size não pode exceder 2147483647.", schema=@Schema(minimum="0", maximum="2147483647", defaultValue="0"))
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = "Page deve ser maior ou igual a zero.") int page,
            @Parameter(description="Quantidade de clientes por página.", schema=@Schema(minimum="1", maximum="100", defaultValue="20"))
            @RequestParam(name = "size", defaultValue = "20")
            @Min(value = 1, message = "Size deve ser maior ou igual a 1.")
            @Max(value = 100, message = "Size deve ser menor ou igual a 100.") int size) {
        return CustomerPageResponse.from(service.search(name, parseStatus(status), page, size));
    }

    @Operation(operationId="findCustomer", summary="Consultar cliente por ID", description="Acesso USER ou ADMIN. Retorna dados e version de cliente não excluído, inclusive INACTIVE. A versão é usada como expectedVersion na solicitação assíncrona de status.")
    @GetMapping("/{id}")
    public CustomerResponse findById(@Parameter(description="ID positivo do cliente.", example="123", schema=@Schema(minimum="1", maximum="9223372036854775807"))
            @PathVariable("id") @Positive Long id) {
        return CustomerResponse.from(service.findById(id));
    }

    @Operation(operationId="replaceCustomer", summary="Substituir dados do cliente", description="Acesso ADMIN. Exige os quatro campos; preserva ID e createdAt. Mudança efetiva avança updatedAt e version; repetição sem mudança mantém o estado. Não cria nem restaura cliente inexistente/excluído. version é somente de saída; não deve ser enviado no corpo.")
    @PutMapping("/{id}")
    public CustomerResponse update(@Parameter(description="ID positivo do cliente.", example="123", schema=@Schema(minimum="1", maximum="9223372036854775807"))
            @PathVariable("id") @Positive Long id,
            @Valid @RequestBody UpdateCustomerRequest request) {
        return CustomerResponse.from(service.update(id, request.name(), request.cpf(), request.email(), request.status()));
    }

    @Operation(operationId="deleteCustomer", summary="Excluir cliente logicamente", description="Acesso ADMIN. Define deletedAt e avança updatedAt/version, sem liberar CPF. Não confunde exclusão com status INACTIVE. Primeira exclusão retorna 204 sem corpo; repetição retorna 404.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description="ID positivo do cliente.", example="123", schema=@Schema(minimum="1", maximum="9223372036854775807"))
            @PathVariable("id") @Positive Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    private CustomerStatus parseStatus(String status) {
        return status == null ? null : CustomerStatus.valueOf(status);
    }
}
