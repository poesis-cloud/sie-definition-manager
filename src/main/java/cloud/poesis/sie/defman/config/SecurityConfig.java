package cloud.poesis.sie.defman.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration for the Definition Manager.
 *
 * <p>
 * Stateless, CSRF-disabled, JWT-based resource server. OpenAPI, actuator
 * health/info, and API endpoints are publicly accessible during design phase;
 * all other paths require authentication. OAuth 2 login is opt-in via
 * {@code dm.security.oauth2-login-enabled}.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Builds the security filter chain.
     *
     * @param http               the {@link HttpSecurity} builder
     * @param oauth2LoginEnabled whether OAuth 2 login flow is enabled
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if configuration fails
     */
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
                                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
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
