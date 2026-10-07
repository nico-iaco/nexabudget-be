package it.iacovelli.nexabudgetbe.security;

import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Autenticazione ottenuta tramite header {@code X-Api-Key}. Tipo distinto dalla sessione JWT così che le
 * operazioni sensibili (creazione di nuove chiavi, cambio email/password) possano essere negate a una chiave:
 * una chiave trapelata non deve poter generare altre chiavi né prendere il controllo dell'account.
 */
public class ApiKeyAuthenticationToken extends UsernamePasswordAuthenticationToken {

    public ApiKeyAuthenticationToken(User user) {
        super(user, null, user.getAuthorities());
    }

    public static boolean isCurrentRequestApiKeyAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof ApiKeyAuthenticationToken;
    }
}
