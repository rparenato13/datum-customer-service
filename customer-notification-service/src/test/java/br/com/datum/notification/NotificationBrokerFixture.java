package br.com.datum.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import br.com.datum.notification.application.CustomerCreatedEvent;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class NotificationBrokerFixture {
    static final ObjectMapper JSON = new ObjectMapper();
    static final GenericContainer<?> CONTAINER = new GenericContainer<>("rabbitmq:4.4.0-management")
            .withEnv("RABBITMQ_DEFAULT_USER", "test_admin").withEnv("RABBITMQ_DEFAULT_PASS", "admin_test_only")
            .withEnv("RABBITMQ_DEFAULT_VHOST", "datum").withExposedPorts(5672, 15672)
            .waitingFor(Wait.forHttp("/api/overview").forPort(15672)
                    .withBasicCredentials("test_admin", "admin_test_only"));
    static JsonNode topology;

    static Connection connection() throws Exception {
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(CONTAINER.getHost());
        factory.setPort(CONTAINER.getMappedPort(5672));
        factory.setUsername("test_admin");
        factory.setPassword("admin_test_only");
        factory.setVirtualHost("datum");
        factory.setAutomaticRecoveryEnabled(false);
        factory.setConnectionTimeout(2000);
        factory.setHandshakeTimeout(5000);
        factory.setChannelRpcTimeout(5000);
        return factory.newConnection();
    }

    static void configure() throws Exception {
        try (var input = NotificationBrokerFixture.class.getResourceAsStream("/event-contracts/topology.v1.json")) {
            topology = JSON.readTree(input);
        }
        try (var connection = connection(); var channel = connection.createChannel()) {
            for (var exchange : topology.path("exchanges")) {
                channel.exchangeDeclare(exchange.path("name").asText(), exchange.path("type").asText(), true);
            }
            for (var consumer : topology.path("consumers")) {
                String main = consumer.path("queue").asText();
                declare(channel, main);
                declare(channel, consumer.path("deadQueue").asText());
                channel.queueBind(main, "datum.customer.events", consumer.path("routingKey").asText());
                channel.queueBind(main, "datum.customer.redelivery", main);
                channel.queueBind(consumer.path("deadQueue").asText(), "datum.customer.dead", main);
                policy(main, Map.of("dead-letter-exchange", "datum.customer.dead", "dead-letter-routing-key", main,
                        "dead-letter-strategy", "at-least-once", "delivery-limit", 5));
                policy(consumer.path("deadQueue").asText(), Map.of());
                for (var retry : consumer.path("retries")) {
                    String queue = retry.path("queue").asText();
                    declare(channel, queue);
                    channel.queueBind(queue, "datum.customer.retry", retry.path("routingKey").asText());
                    policy(queue, Map.of("message-ttl", retry.path("ttlMs").asInt(), "dead-letter-exchange",
                            "datum.customer.redelivery", "dead-letter-routing-key", main,
                            "dead-letter-strategy", "at-least-once"));
                }
            }
        }
        put("users/notification_app", Map.of("password", "notification_test_only", "tags", ""));
        put("permissions/datum/notification_app", Map.of("configure", "^$", "write",
                "^datum\\.customer\\.retry$", "read", "^customer-notification-service\\.created\\.v1$"));
        put("topic-permissions/datum/notification_app", Map.of("exchange", "datum.customer.events",
                "write", "^$", "read", "^$"));
    }

    private static void declare(com.rabbitmq.client.Channel channel, String queue) throws Exception {
        channel.queueDeclare(queue, true, false, false, Map.of("x-queue-type", "quorum"));
    }

    private static void policy(String queue, Map<String, Object> extra) throws Exception {
        var definition = new LinkedHashMap<String, Object>();
        definition.put("max-length", 10000);
        definition.put("max-length-bytes", 67108864);
        definition.put("overflow", "reject-publish");
        definition.putAll(extra);
        put("policies/datum/" + queue, Map.of("pattern", "^" + queue.replace(".", "\\.") + "$",
                "apply-to", "quorum_queues", "priority", 10, "definition", definition));
    }

    private static void put(String path, Map<String, Object> body) throws Exception {
        String basic = Base64.getEncoder().encodeToString("test_admin:admin_test_only".getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create("http://" + CONTAINER.getHost() + ":"
                        + CONTAINER.getMappedPort(15672) + "/api/" + path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + basic).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        int code;
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            code = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
        if (code != 201 && code != 204) throw new IllegalStateException("Fixture broker HTTP " + code);
    }

    static void purge() throws Exception {
        try (var connection = connection(); var channel = connection.createChannel()) {
            for (var consumer : topology.path("consumers")) {
                channel.queuePurge(consumer.path("queue").asText());
                channel.queuePurge(consumer.path("deadQueue").asText());
                for (var retry : consumer.path("retries")) channel.queuePurge(retry.path("queue").asText());
            }
        }
    }

    static void publish(CustomerCreatedEvent event, byte[] body, int attempt) throws Exception {
        var properties = new AMQP.BasicProperties.Builder().messageId(event.eventId().toString())
                .correlationId(event.eventId().toString()).type(CustomerCreatedEvent.TYPE)
                .contentType("application/json").contentEncoding("UTF-8").deliveryMode(2);
        if (attempt > 0) {
            properties.headers(Map.of("x-retry-attempt", attempt, "x-death", java.util.List.of(
                    Map.of("reason", "expired", "queue", CustomerCreatedEvent.CONSUMER + ".retry." + attempt))));
        }
        try (var connection = connection(); var channel = connection.createChannel()) {
            channel.confirmSelect();
            channel.basicPublish(attempt == 0 ? "datum.customer.events" : "datum.customer.redelivery",
                    attempt == 0 ? "customer.created.v1" : CustomerCreatedEvent.CONSUMER,
                    true, properties.build(), body);
            channel.waitForConfirmsOrDie(5000);
        }
    }

    static long count(String suffix) throws Exception {
        try (var connection = connection(); var channel = connection.createChannel()) {
            return channel.messageCount(CustomerCreatedEvent.CONSUMER + suffix);
        }
    }

    static GetResponse receiveDead() throws Exception {
        try (var connection = connection(); var channel = connection.createChannel()) {
            return channel.basicGet(CustomerCreatedEvent.CONSUMER + ".dlq", true);
        }
    }
}
