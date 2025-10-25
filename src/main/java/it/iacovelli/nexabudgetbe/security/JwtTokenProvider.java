package it.iacovelli.nexabudgetbe.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey; // Importa SecretKey
import java.util.Date;

@Component
public class JwtTokenProvider {

    @Value("${app.jwtExpirationInMs}")
    private int jwtExpirationInMs;

    private final SecretKey key;


    public JwtTokenProvider(@Value("${app.jwtSecret}") String jwtSecret) {
        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes()); // Restituisce SecretKey
    }

    public String generateToken(User user) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationInMs);

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .issuedAt(new Date())
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    public String generateToken(Long userId, String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationInMs);

        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .issuedAt(new Date())
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    public Long getUserIdFromJWT(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject() != null ?
                Long.parseLong(Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject()) : null;
    }

    public String getUsernameFromJWT(String token) { // Nuovo metodo
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().get("username", String.class);
    }

    public boolean validateToken(String authToken) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(authToken);
            return true;
        } catch (Exception ex) {
            // Log dell'eccezione (MalformedJwtException, ExpiredJwtException, UnsupportedJwtException, IllegalArgumentException)
            // È buona pratica loggare l'eccezione qui
            // logger.error("Invalid JWT token: {}", ex.getMessage());
        }
        return false;
    }
}
