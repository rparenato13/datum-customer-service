package br.com.datum.customer.security;

import br.com.datum.customer.application.CustomerService;
import br.com.datum.customer.domain.CustomerStatus;
import br.com.datum.customer.infrastructure.persistence.CustomerEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("smoke")
@SecurityTestFixture
@ExtendWith(OutputCaptureExtension.class)
class CustomerSecurityTest {

    @Autowired
    TestRestTemplate http;

    @MockitoBean
    CustomerService service;

    @BeforeEach
    void stubApplication() {
        // O contexto pode ser reutilizado pelo smoke que configura interceptor ADMIN.
        http.getRestTemplate().setInterceptors(List.of());
        var customer = new CustomerEntity("Cliente de Teste", "52998224725", "cliente@example.test");
        ReflectionTestUtils.setField(customer, "id", 1L);
        ReflectionTestUtils.setField(customer, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        ReflectionTestUtils.setField(customer, "updatedAt", customer.getCreatedAt());
        var page = new PageImpl<>(List.of(customer), PageRequest.of(0, 20), 1);
        when(service.findById(anyLong())).thenReturn(customer);
        when(service.create(anyString(), anyString(), anyString(), any(CustomerStatus.class))).thenReturn(customer);
        when(service.update(anyLong(), anyString(), anyString(), anyString(), any(CustomerStatus.class))).thenReturn(customer);
        when(service.list(nullable(CustomerStatus.class), anyInt(), anyInt())).thenReturn(page);
        when(service.search(anyString(), nullable(CustomerStatus.class), anyInt(), anyInt())).thenReturn(page);
    }

    @ParameterizedTest
    @MethodSource("protectedRequests")
    void shouldRequireAuthenticationForEveryCustomerOperation(HttpMethod method, String path) {
        assertProblem(request(method, path, null), HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        verifyNoInteractions(service);
    }

    static Stream<Arguments> protectedRequests() {
        return Stream.concat(readRequests(), writeRequests());
    }

    static Stream<Arguments> readRequests() {
        return Stream.of(Arguments.of(HttpMethod.GET, "/customers"),
                Arguments.of(HttpMethod.GET, "/customers/1"),
                Arguments.of(HttpMethod.GET, "/customers/search?name=cliente"),
                Arguments.of(HttpMethod.GET, "/customers?status=ACTIVE"));
    }

    static Stream<Arguments> writeRequests() {
        return Stream.of(Arguments.of(HttpMethod.POST, "/customers"),
                Arguments.of(HttpMethod.PUT, "/customers/1"),
                Arguments.of(HttpMethod.DELETE, "/customers/1"));
    }

    @ParameterizedTest
    @MethodSource("authorizedReads")
    void shouldAllowUserAndAdminToRead(String role, HttpMethod method, String path) {
        var response = request(method, path, TestJwtSupport.token(role));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
    }

    static Stream<Arguments> authorizedReads() {
        return Stream.of("USER", "ADMIN").flatMap(role -> readRequests().map(arguments ->
                Arguments.of(role, arguments.get()[0], arguments.get()[1])));
    }

    @ParameterizedTest
    @MethodSource("writeRequests")
    void shouldRejectEveryUserWriteBeforeCallingApplication(HttpMethod method, String path) {
        assertProblem(request(method, path, TestJwtSupport.token("USER")), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("writeRequests")
    void shouldAllowAdminWritesWithoutSessionOrCsrfToken(HttpMethod method, String path) {
        var expected = method == HttpMethod.POST ? HttpStatus.CREATED
                : method == HttpMethod.DELETE ? HttpStatus.NO_CONTENT : HttpStatus.OK;
        var response = request(method, path, TestJwtSupport.token("ADMIN"));
        assertEquals(expected, response.getStatusCode());
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
    }

    @ParameterizedTest
    @MethodSource("invalidTokens")
    void shouldRejectInvalidJwt(String description, String token, CapturedOutput output) {
        var response = request(HttpMethod.GET, "/customers", token);
        assertProblem(response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        assertFalse(response.getBody().toString().contains(token));
        assertFalse(output.getAll().contains(token));
        verifyNoInteractions(service);
    }

    static Stream<Arguments> invalidTokens() {
        var now = Instant.now();
        return Stream.of(
                Arguments.of("malformado", "not-a-jwt"),
                Arguments.of("assinatura incorreta", TestJwtSupport.wrongSignature()),
                Arguments.of("kid desconhecido", TestJwtSupport.unknownKey()),
                Arguments.of("algoritmo nao permitido", TestJwtSupport.wrongAlgorithm()),
                Arguments.of("sem assinatura", TestJwtSupport.unsigned()),
                Arguments.of("issuer incorreto", TestJwtSupport.token("ADMIN", claims -> claims.issuer("https://outro.test"))),
                Arguments.of("issuer ausente", TestJwtSupport.token("ADMIN", claims -> claims.issuer(null))),
                Arguments.of("audiencia incorreta", TestJwtSupport.token("ADMIN", claims -> claims.audience("outra-api"))),
                Arguments.of("audiencia ausente", TestJwtSupport.token("ADMIN", claims -> claims.claim("aud", null))),
                Arguments.of("expirado", TestJwtSupport.token("ADMIN", claims -> claims.expirationTime(Date.from(now.minusSeconds(180))))),
                Arguments.of("nbf futuro", TestJwtSupport.token("ADMIN", claims -> claims.notBeforeTime(Date.from(now.plusSeconds(180))))),
                Arguments.of("sem expiracao", TestJwtSupport.token("ADMIN", claims -> claims.expirationTime(null))),
                Arguments.of("sem subject", TestJwtSupport.token("ADMIN", claims -> claims.subject(null))),
                Arguments.of("subject vazio", TestJwtSupport.token("ADMIN", claims -> claims.subject(" "))));
    }

    @ParameterizedTest
    @MethodSource("tokensWithoutRecognizedRoles")
    void shouldRejectValidTokenWithoutRecognizedRole(String token) {
        assertProblem(request(HttpMethod.GET, "/customers", token), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        verifyNoInteractions(service);
    }

    static Stream<String> tokensWithoutRecognizedRoles() {
        return Stream.of(
                TestJwtSupport.token(null),
                TestJwtSupport.token("ROLE_ADMIN"),
                TestJwtSupport.token("admin"),
                TestJwtSupport.token(null, claims -> claims.claim("realm_access", "ADMIN")),
                TestJwtSupport.token(null, claims -> claims.claim("realm_access", Map.of("roles", "ADMIN"))),
                TestJwtSupport.token(null, claims -> claims.claim("realm_access", Map.of("roles", List.of("UNKNOWN", 42)))),
                TestJwtSupport.token(null, claims -> claims.claim("scope", "ADMIN")
                        .claim("resource_access", Map.of("datum-local", Map.of("roles", List.of("ADMIN"))))));
    }

    @Test
    void shouldRejectRoleTampering(CapturedOutput output) {
        var user = TestJwtSupport.token("USER").split("\\.");
        var admin = TestJwtSupport.token("ADMIN").split("\\.");
        var tampered = user[0] + "." + admin[1] + "." + user[2];
        assertProblem(request(HttpMethod.GET, "/customers", tampered), HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        assertFalse(output.getAll().contains(tampered));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void shouldKeepHealthPublic(String method) {
        assertEquals(HttpStatus.OK, request(HttpMethod.valueOf(method), "/actuator/health", null).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void shouldNotAcceptTokenFromQueryStringOrSessionCookie() {
        var token = TestJwtSupport.token("ADMIN");
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, "JSESSIONID=session-invalida");
        var response = http.exchange("/customers?access_token=" + token, HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertProblem(response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        assertFalse(response.getBody().toString().contains(token));
        verifyNoInteractions(service);
    }

    @Test
    void shouldNotEnableBasicAuthentication() {
        var headers = new HttpHeaders();
        headers.setBasicAuth("api-admin", "admin_local_only");
        assertProblem(http.exchange("/customers", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class),
                HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        verifyNoInteractions(service);
    }

    @Test
    void shouldDenyUnconfiguredRoutesEvenToAdmin() {
        assertProblem(request(HttpMethod.GET, "/unconfigured", TestJwtSupport.token("ADMIN")),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        verifyNoInteractions(service);
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String token) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        Object body = method == HttpMethod.POST || method == HttpMethod.PUT
                ? Map.of("name", "Cliente de Teste", "cpf", "52998224725", "email", "cliente@example.test", "status", "ACTIVE") : null;
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private void assertProblem(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertEquals(status, response.getStatusCode());
        assertNotNull(response.getHeaders().getContentType());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON.getSubtype(), response.getHeaders().getContentType().getSubtype());
        assertNotNull(response.getBody());
        assertEquals(status.value(), response.getBody().path("status").asInt());
        assertEquals(code, response.getBody().path("code").asText());
        assertNotNull(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        assertFalse(response.getBody().has("trace"));
        assertFalse(response.getBody().has("exception"));
    }
}
