package br.com.datum.customer.api;

import br.com.datum.customer.application.score.CustomerScoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name="Score", description="Consulta direta ao provedor externo de Score.")
@RestController
@RequestMapping("/customers")
public class CustomerScoreController {

    private final CustomerScoreService service;

    public CustomerScoreController(CustomerScoreService service) {
        this.service = service;
    }

    @Operation(operationId="findCustomerScore", summary="Consultar Score do cliente", description="Acesso USER ou ADMIN. Resolve o CPF internamente, consulta o provedor HTTP e retorna somente customerId/score/classification. Aceita cliente INACTIVE, exclui apagados logicamente. Não persiste Score e não mantém transação de banco durante a chamada externa. Resposta no-store. 404 distingue cliente inexistente de Score ausente; indisponibilidade, resposta inválida e timeout têm códigos próprios.")
    @GetMapping("/{id}/score")
    public ResponseEntity<CustomerScoreResponse> find(@Parameter(description="ID positivo de cliente não excluído.", example="123", schema=@Schema(minimum="1", maximum="9223372036854775807")) @PathVariable("id") @Positive Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(CustomerScoreResponse.from(id, service.findByCustomerId(id)));
    }
}
