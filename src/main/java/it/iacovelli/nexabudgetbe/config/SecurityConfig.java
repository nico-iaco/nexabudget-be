package it.iacovelli.nexabudgetbe.config;

import io.jsonwebtoken.impl.DefaultClaimsBuilder;
import io.jsonwebtoken.impl.DefaultJwtBuilder;
import io.jsonwebtoken.impl.DefaultJwtHeaderBuilder;
import io.jsonwebtoken.impl.DefaultJwtParserBuilder;
import io.jsonwebtoken.impl.io.StandardCompressionAlgorithms;
import io.jsonwebtoken.impl.security.*;
import it.iacovelli.nexabudgetbe.security.JwtAuthenticationFilter;
import it.iacovelli.nexabudgetbe.service.UserDetailsServiceImpl;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RegisterReflectionForBinding(classes = {
        KeysBridge.class,
        StandardEncryptionAlgorithms.class,
        StandardKeyAlgorithms.class,
        StandardSecureDigestAlgorithms.class,
        StandardCompressionAlgorithms.class,
        StandardKeyOperations.class,
        JwksBridge.class,
        DefaultJwtBuilder.Supplier.class,
        DefaultJwtParserBuilder.Supplier.class,
        DefaultJwtHeaderBuilder.Supplier.class,
        DefaultClaimsBuilder.Supplier.class,
        DefaultDynamicJwkBuilder.Supplier.class,
        DefaultJwkParserBuilder.Supplier.class,
        DefaultJwkSetBuilder.Supplier.class,
        DefaultJwkSetParserBuilder.Supplier.class,
        DefaultKeyOperationBuilder.Supplier.class,
        DefaultKeyOperationPolicyBuilder.Supplier.class
})

public class SecurityConfig {

    private final UserDetailsServiceImpl userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(UserDetailsServiceImpl userDetailsService, JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.userDetailsService = userDetailsService;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-resources/**", "/webjars/**").permitAll()
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider());

        // Aggiungi il UserDetailsService per l'AuthenticationManager
        http.userDetailsService(userDetailsService);

        // Aggiungi il filtro JWT prima del filtro standard di username/password
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
