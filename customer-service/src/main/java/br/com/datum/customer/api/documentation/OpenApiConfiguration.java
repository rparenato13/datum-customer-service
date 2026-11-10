package br.com.datum.customer.api.documentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name="customer.documentation.enabled", havingValue="true", matchIfMissing=true)
public class OpenApiConfiguration {
    private final ObjectMapper mapper;

    public OpenApiConfiguration(ObjectMapper mapper) { this.mapper = mapper; }

    private void addProblemSchemas(Components components) {
        var fieldError = new ObjectSchema().addProperty("field", new StringSchema().example("name"))
                .addProperty("message", new StringSchema().example("Nome é obrigatório."))
                .required(List.of("field", "message"));
        var problem = new ObjectSchema().description("Erro RFC 9457 com extensões code e errors. Não inclui valores rejeitados, CPF, token ou causa interna.")
                .addProperty("type", new StringSchema().format("uri-reference").example("about:blank"))
                .addProperty("title", new StringSchema())
                .addProperty("status", new IntegerSchema().minimum(java.math.BigDecimal.valueOf(400)).maximum(java.math.BigDecimal.valueOf(599)))
                .addProperty("detail", new StringSchema())
                .addProperty("instance", new StringSchema().format("uri-reference").example("/customers/123"))
                .addProperty("code", new StringSchema().description("Código estável da falha; veja os exemplos da operação."))
                .addProperty("errors", new ArraySchema().items(new Schema<>().$ref("#/components/schemas/FieldError")))
                .required(List.of("type", "title", "status", "detail", "instance", "code"));
        components.addSchemas("FieldError", fieldError).addSchemas("ApiProblem", problem);
    }

    @Bean
    OpenAPI customerOpenApi() {
        return new OpenAPI().info(new Info().title("Datum Customer API").version("1.0")
                .description("Clientes e consulta de Score. Leituras: USER ou ADMIN; escritas: ADMIN, conforme realm_access.roles. "
                        + "Obtenha um access token com Authorization Code + PKCE no Postman e cole apenas o token em Authorize. "
                        + "Os exemplos usam dados fictícios; substitua o ID pelo retornado no cadastro. JWT deve ter assinatura RS256, issuer/audiência e validade corretos. "
                        + "CUSTOMER_CREATED e solicitações de status são assíncronos e possuem contratos próprios em contracts/events. "
                        + "Esta API não recebe eventos por HTTP."))
                .servers(List.of(new Server().url("/").description("Mesma origem da interface, inclusive na porta publicada pelo Docker")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT").description("Access token emitido pelo Keycloak. Cole somente o JWT, sem prefixo Bearer; não use refresh token nem ID token.")))
                .security(List.of(new SecurityRequirement().addList("bearerAuth")));
    }

    @Bean
    OpenApiCustomizer customerResponses() {
        return api -> {
            // Adicionar junto das referencias: springdoc pode descartar schemas
            // ainda nao referenciados durante a inferencia inicial dos controllers.
            addProblemSchemas(api.getComponents());
            api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> describe(path, operation)));
        };
    }

    private void describe(String path, Operation operation) {
        String id = operation.getOperationId();
        boolean write = List.of("createCustomer", "replaceCustomer", "deleteCustomer").contains(id);
        operation.addExtension("x-required-roles", write ? List.of("ADMIN") : List.of("USER", "ADMIN"));
        operation.setSecurity(List.of(new SecurityRequirement().addList("bearerAuth")));
        String instance = path.replace("{id}", "123");
        var responses = new ApiResponses();
        switch (id) {
            case "createCustomer" -> {
                operation.setRequestBody(request("CreateCustomerRequest", ApiExamples.CREATE));
                responses.addApiResponse("201", success("Cliente criado; cadastro e outbox confirmados no banco. Broker/notificação não são aguardados.", "CustomerResponse", ApiExamples.CUSTOMER)
                        .addHeaderObject("Location", new Header().description("URI relativa do cliente criado.")
                                .required(true).schema(new StringSchema().format("uri-reference").example("/customers/123"))));
                responses.addApiResponse("400", invalidBody(instance, false));
                responses.addApiResponse("409", duplicateCpf(instance));
            }
            case "replaceCustomer" -> {
                operation.setRequestBody(request("UpdateCustomerRequest", ApiExamples.UPDATE));
                responses.addApiResponse("200", success("Cliente substituído; preserva ID/createdAt. No-op conserva a versão e os horários.", "CustomerResponse", ApiExamples.UPDATED_CUSTOMER));
                responses.addApiResponse("400", invalidBody(instance, true));
                responses.addApiResponse("404", customerNotFound(instance));
                responses.addApiResponse("409", duplicateCpf(instance));
            }
            case "deleteCustomer" -> {
                responses.addApiResponse("204", new ApiResponse().description("Exclusão lógica confirmada, sem corpo. Repetição retorna 404."));
                responses.addApiResponse("400", invalidRequest(instance, true));
                responses.addApiResponse("404", customerNotFound(instance));
            }
            case "findCustomer" -> {
                responses.addApiResponse("200", success("Cliente disponível, incluindo status INACTIVE; excluídos não são retornados.", "CustomerResponse", ApiExamples.CUSTOMER));
                responses.addApiResponse("400", invalidRequest(instance, true));
                responses.addApiResponse("404", customerNotFound(instance));
            }
            case "listCustomers", "searchCustomers" -> {
                var response = success("Página sem excluídos, ordenada por ID. Ausência de resultados retorna página vazia, nunca 404.", "CustomerPageResponse", ApiExamples.PAGE);
                response.getContent().get("application/json").addExamples("empty", new Example().value(json(ApiExamples.EMPTY_PAGE)));
                responses.addApiResponse("200", response);
                responses.addApiResponse("400", invalidRequest(instance, false));
            }
            case "findCustomerScore" -> {
                responses.addApiResponse("200", success("Score atual do provedor, sem CPF na resposta e sem persistência local.", "CustomerScoreResponse", ApiExamples.SCORE)
                        .addHeaderObject("Cache-Control", new Header().description("Impede armazenamento da resposta em cache.")
                                .schema(new StringSchema().example("no-store"))));
                responses.addApiResponse("400", invalidRequest(instance, true));
                var notFound = customerNotFound(instance).description("CUSTOMER_NOT_FOUND: cliente ausente/excluído, sem chamar o provedor. SCORE_NOT_FOUND: cliente existe, mas o provedor retornou 404.");
                example(notFound, "SCORE_NOT_FOUND", problem(404, "Score não encontrado", "Não há Score disponível para este cliente.", "SCORE_NOT_FOUND", instance));
                responses.addApiResponse("404", notFound);
                responses.addApiResponse("502", error(502, "Resposta de Score inválida", "Não foi possível obter uma resposta válida para a consulta de Score.", "SCORE_INVALID_RESPONSE", instance,
                        "Resposta inválida/incompatível do provedor, inclusive identidade divergente, JSON inválido ou status não mapeado como transitório."));
                responses.addApiResponse("503", error(503, "Consulta de Score indisponível", "A consulta de Score está temporariamente indisponível.", "SCORE_UNAVAILABLE", instance,
                        "Falha de conexão ou falha transitória do provedor após tentativas limitadas; não significa cliente inexistente."));
                responses.addApiResponse("504", error(504, "Tempo da consulta de Score excedido", "A consulta de Score excedeu o tempo permitido.", "SCORE_TIMEOUT", instance,
                        "Timeout da chamada ao provedor; não há retry de timeout nesta versão."));
            }
            default -> throw new IllegalStateException("Operação sem contrato OpenAPI explícito: " + id);
        }
        responses.addApiResponse("401", error(401, "Autenticação necessária", "Informe um token Bearer válido.", "AUTHENTICATION_REQUIRED", instance,
                "Token ausente, malformado, expirado ou inválido. Nenhuma regra de negócio é executada.")
                .addHeaderObject("WWW-Authenticate", new Header().schema(new StringSchema().example("Bearer"))));
        responses.addApiResponse("403", error(403, "Acesso negado", "Seu perfil não permite esta operação.", "ACCESS_DENIED", instance,
                write ? "Token válido sem ADMIN; inclusive USER. Rejeição ocorre antes da aplicação." : "Token válido sem USER/ADMIN; roles ausentes ou não reconhecidas não concedem acesso.")
                .addHeaderObject("WWW-Authenticate", new Header().schema(new StringSchema().example("Bearer error=\"insufficient_scope\""))));
        responses.addApiResponse("500", error(500, "Erro interno", "Não foi possível concluir a operação.", "INTERNAL_ERROR", instance,
                "Falha interna não prevista, incluindo indisponibilidade do banco. Resposta sanitizada; a API atual não transforma falha de banco em 503."));
        operation.setResponses(responses);
    }

    private RequestBody request(String schema, String example) {
        return new RequestBody().required(true).description("Todos os quatro campos são obrigatórios. Campos desconhecidos, inclusive id/version/datas, são recusados.")
                .content(content("application/json", schema, "valid", json(example)));
    }

    private ApiResponse success(String description, String schema, String example) {
        return new ApiResponse().description(description).content(content("application/json", schema, "success", json(example)));
    }

    private Content content(String type, String schema, String name, Object value) {
        return new Content().addMediaType(type, new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + schema))
                .addExamples(name, new Example().value(value)));
    }

    private ApiResponse error(int status, String title, String detail, String code, String instance, String description) {
        return new ApiResponse().description(description).content(content("application/problem+json", "ApiProblem", code,
                problem(status, title, detail, code, instance)));
    }

    private Map<String, Object> problem(int status, String title, String detail, String code, String instance) {
        return Map.of("type", "about:blank", "title", title, "status", status, "detail", detail, "instance", instance, "code", code);
    }

    private ApiResponse customerNotFound(String instance) {
        return error(404, "Cliente não encontrado", "Cliente não encontrado.", "CUSTOMER_NOT_FOUND", instance, "Cliente inexistente ou excluído logicamente.");
    }

    private ApiResponse duplicateCpf(String instance) {
        return error(409, "CPF já cadastrado", "Já existe um cliente cadastrado com este CPF.", "CPF_ALREADY_REGISTERED", instance,
                "CPF já reservado, inclusive por cliente excluído. A operação é revertida, sem gravar um segundo evento de criação.");
    }

    private ApiResponse invalidRequest(String instance, boolean id) {
        return error(400, id ? "Identificador inválido" : "Parâmetros inválidos", id ? "O identificador deve ser um número inteiro positivo."
                        : "Verifique name, status, page e size. Use page >= 0, size entre 1 e 100 e status ACTIVE ou INACTIVE.",
                "INVALID_REQUEST", instance, id ? "ID inválido: precisa ser inteiro positivo no intervalo Long."
                        : "Parâmetros ausentes/inválidos, name vazio ou acima de 150 caracteres, status fora do enum, paginação inválida ou offset incompatível.");
    }

    private ApiResponse invalidBody(String instance, boolean id) {
        var response = error(400, "Dados inválidos", "Revise os campos informados.", "VALIDATION_ERROR", instance,
                "VALIDATION_ERROR: campos obrigatórios, limites, e-mail ou CPF inválido (incluindo dígitos verificadores). INVALID_JSON: corpo ausente/malformado, campos desconhecidos ou enum inválido."
                        + (id ? " INVALID_REQUEST: ID inválido." : ""));
        var validation = new java.util.LinkedHashMap<>(problem(400, "Dados inválidos", "Revise os campos informados.", "VALIDATION_ERROR", instance));
        validation.put("errors", List.of(Map.of("field", "name", "message", "Nome é obrigatório.")));
        example(response, "VALIDATION_ERROR", validation);
        example(response, "INVALID_JSON", problem(400, "Corpo inválido", "Informe um JSON válido com os campos esperados e status ACTIVE ou INACTIVE.", "INVALID_JSON", instance));
        if (id) example(response, "INVALID_REQUEST", problem(400, "Identificador inválido", "O identificador deve ser um número inteiro positivo.", "INVALID_REQUEST", instance));
        return response;
    }

    private void example(ApiResponse response, String name, Object value) {
        response.getContent().get("application/problem+json").addExamples(name, new Example().value(value));
    }

    private Object json(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Exemplo OpenAPI inválido.");
        }
    }
}
