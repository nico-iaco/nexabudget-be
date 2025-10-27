package it.iacovelli.nexabudgetbe.security;

import io.jsonwebtoken.Jwts;
import it.iacovelli.nexabudgetbe.model.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtTokenProvider {

    @Value("${app.jwtExpirationInMs}")
    private int jwtExpirationInMs;

    private final SecretKey key = Jwts.SIG.HS512.key().build();

    private final Logger logger = LogManager.getLogger(JwtTokenProvider.class);

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

    public String getUsernameFromJWT(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().get("username", String.class);
    }

    public boolean validateToken(String authToken) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(authToken);
            return true;
        } catch (Exception ex) {
            // Log dell'eccezione (MalformedJwtException, ExpiredJwtException, UnsupportedJwtException, IllegalArgumentException)
            // È buona pratica loggare l'eccezione qui
            logger.error("Invalid JWT token: {}", ex.getMessage());
        }
        return false;
    }
}
