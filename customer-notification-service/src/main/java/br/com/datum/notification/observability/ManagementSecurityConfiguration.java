package br.com.datum.notification.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class ManagementSecurityConfiguration {
    @Bean
    UserDetailsService managementUsers(
            @Value("${NOTIFICATION_MANAGEMENT_PASSWORD:notification_monitor_local_only}") String password) {
        return new InMemoryUserDetailsManager(User.withUsername("notification_monitor")
                .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password)).roles("MONITOR").build());
    }

    @Bean
    SecurityFilterChain managementSecurity(HttpSecurity http, ObjectMapper mapper) throws Exception {
        return http.securityMatcher("/actuator/**")
                .csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/metrics", "/actuator/metrics/*", "/actuator/prometheus").hasRole("MONITOR")
                        .requestMatchers(HttpMethod.HEAD, "/actuator/metrics", "/actuator/metrics/*", "/actuator/prometheus").hasRole("MONITOR")
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, error) -> {
                    response.setHeader("WWW-Authenticate", "Basic realm=\"datum-monitoring\"");
                    problem(response, mapper, 401, "AUTHENTICATION_REQUIRED");
                }))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> {
                            response.setHeader("WWW-Authenticate", "Basic realm=\"datum-monitoring\"");
                            problem(response, mapper, 401, "AUTHENTICATION_REQUIRED");
                        })
                        .accessDeniedHandler((request, response, error) -> problem(response, mapper, 403, "ACCESS_DENIED")))
                .build();
    }

    private static void problem(HttpServletResponse response, ObjectMapper mapper, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of("type", "about:blank", "title", "Acesso às métricas",
                "status", status, "code", code, "correlationId", CorrelationContext.id()));
    }
}
