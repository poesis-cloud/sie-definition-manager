package io.poesis.sie.defman.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${dm.security.oauth2-login-enabled:false}") boolean oauth2LoginEnabled)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth -> auth.requestMatchers("/actuator/health", "/actuator/info")
                                .permitAll()
                                .requestMatchers(HttpMethod.GET, "/api/v1/openapi")
                                .permitAll()
                                .requestMatchers("/api/v1/ascriptions/**")
                                .permitAll()
                                .requestMatchers("/api/v1/definitions/**")
                                .permitAll()
                                .anyRequest()
                                .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        if (oauth2LoginEnabled) {
            http.oauth2Login(Customizer.withDefaults());
        }

        return http.build();
    }
}
