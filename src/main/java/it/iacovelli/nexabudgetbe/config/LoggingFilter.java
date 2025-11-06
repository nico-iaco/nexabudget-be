package it.iacovelli.nexabudgetbe.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter per aggiungere informazioni di contesto ai log tramite MDC (Mapped Diagnostic Context).
 * Questo permette di tracciare le richieste attraverso tutti i log dell'applicazione.
 */
@Component
public class LoggingFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String REQUEST_ID_MDC_KEY = "requestId";
    private static final String USERNAME_MDC_KEY = "username";
    private static final String ENDPOINT_MDC_KEY = "endpoint";
    private static final String HTTP_METHOD_MDC_KEY = "httpMethod";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            // Genera o recupera il request ID
            String requestId = request.getHeader(REQUEST_ID_HEADER);
            if (requestId == null || requestId.isEmpty()) {
                requestId = UUID.randomUUID().toString();
            }
            MDC.put(REQUEST_ID_MDC_KEY, requestId);
            MDC.put(ENDPOINT_MDC_KEY, request.getRequestURI());
            MDC.put(HTTP_METHOD_MDC_KEY, request.getMethod());

            // Aggiungi il request ID alla risposta
            response.setHeader(REQUEST_ID_HEADER, requestId);

            // Aggiungi informazioni sull'utente se autenticato
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated() &&
                    !"anonymousUser".equals(authentication.getPrincipal())) {
                MDC.put(USERNAME_MDC_KEY, authentication.getName());
                // Se hai un metodo per ottenere lo userId, aggiungilo qui
            }

            filterChain.doFilter(request, response);
        } finally {
            // Pulisci MDC per evitare memory leak
            MDC.clear();
        }
    }
}

