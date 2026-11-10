package br.com.datum.customer.infrastructure.integrations.score;

import br.com.datum.customer.application.score.ScoreClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScoreClientProperties.class)
public class ScoreClientConfiguration {

    @Bean(destroyMethod = "close")
    HttpClient scoreHttpClient(ScoreClientProperties properties) {
        return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }

    @Bean
    ScoreClient scoreClient(ScoreClientProperties properties, ObjectMapper mapper,
            @Qualifier("scoreHttpClient") HttpClient httpClient) {
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        // Cliente dedicado: nao propaga headers de autenticacao da requisicao recebida.
        var rest = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
        return new HttpScoreClient(rest, mapper, properties);
    }
}
