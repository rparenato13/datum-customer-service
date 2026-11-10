package br.com.datum.customer.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtSecurityProperties.class)
public class SecurityConfiguration {

    @Bean
    OAuth2TokenValidator<Jwt> jwtValidator(JwtSecurityProperties properties) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuerUri()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(properties.audience())),
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, subject -> subject != null && !subject.isBlank()));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtSecurityProperties properties, OAuth2TokenValidator<Jwt> jwtValidator) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(jwtValidator);
        return decoder;
    }

    @Bean
    SecurityProblemHandler securityProblemHandler(ObjectMapper mapper) {
        return new SecurityProblemHandler(mapper);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProblemHandler errors,
            @Value("${customer.documentation.enabled:true}") boolean documentationEnabled) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    if (documentationEnabled) {
                        String[] documentation = {"/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config",
                                "/swagger-ui.html", "/swagger-ui/**"};
                        authorize.requestMatchers(HttpMethod.GET, documentation).permitAll();
                        authorize.requestMatchers(HttpMethod.HEAD, documentation).permitAll();
                    }
                    authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/metrics", "/actuator/metrics/*", "/actuator/prometheus").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.HEAD, "/actuator/metrics", "/actuator/metrics/*", "/actuator/prometheus").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/customers", "/customers/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.HEAD, "/customers", "/customers/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/customers").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/customers/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/customers/*").hasRole("ADMIN")
                        .anyRequest().denyAll();
                })
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .oauth2ResourceServer(oauth -> oauth.authenticationEntryPoint(errors).accessDeniedHandler(errors)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRoles())))
                .build();
    }

    private JwtAuthenticationConverter keycloakRoles() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var access = jwt.getClaims().get("realm_access");
            if (!(access instanceof Map<?, ?> map) || !(map.get("roles") instanceof Collection<?> roles)) {
                return List.of();
            }
            return roles.stream().filter(String.class::isInstance).map(String.class::cast)
                    .filter(Set.of("USER", "ADMIN")::contains).distinct()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role)).toList();
        });
        return converter;
    }
}
