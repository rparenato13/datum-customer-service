package br.com.datum.customer.api.documentation;

public final class ApiExamples {
    private ApiExamples() { }

    public static final String CREATE = """
            {"name":"Cliente de Demonstracao","cpf":"16899535009","email":"cliente@example.test","status":"ACTIVE"}
            """;
    public static final String UPDATE = """
            {"name":"Cliente Atualizado","cpf":"16899535009","email":"atualizado@example.test","status":"INACTIVE"}
            """;
    public static final String CUSTOMER = """
            {"id":123,"name":"Cliente de Demonstracao","cpf":"16899535009","email":"cliente@example.test","status":"ACTIVE",
             "createdAt":"2026-11-10T03:00:00Z","updatedAt":"2026-11-10T03:00:00Z","version":0}
            """;
    public static final String UPDATED_CUSTOMER = """
            {"id":123,"name":"Cliente Atualizado","cpf":"16899535009","email":"atualizado@example.test","status":"INACTIVE",
             "createdAt":"2026-11-10T03:00:00Z","updatedAt":"2026-11-10T03:01:00Z","version":1}
            """;
    public static final String PAGE = """
            {"content":[{"id":123,"name":"Cliente de Demonstracao","cpf":"16899535009","email":"cliente@example.test","status":"ACTIVE",
             "createdAt":"2026-11-10T03:00:00Z","updatedAt":"2026-11-10T03:00:00Z","version":0}],
             "page":0,"size":20,"totalElements":1,"totalPages":1,"first":true,"last":true}
            """;
    public static final String EMPTY_PAGE = """
            {"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0,"first":true,"last":true}
            """;
    public static final String SCORE = """
            {"customerId":123,"score":750,"classification":"LOW_RISK"}
            """;
}
