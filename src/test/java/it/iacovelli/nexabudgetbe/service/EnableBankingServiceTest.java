package it.iacovelli.nexabudgetbe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test (nessun ApplicationContext): verifica solo il parsing della private key PEM e la
 * costruzione del JWT RS256 applicativo usato per autenticarsi contro la Cloud API Enable Banking.
 * Le chiamate HTTP effettive sono fuori scope qui — vedi CLAUDE.md per il pattern di mocking usato
 * dagli altri test che dipendono da servizi esterni (@MockitoBean sul service, non WireMock).
 */
class EnableBankingServiceTest {

    // Chiave RSA generata SOLO per questo test (nessun collegamento a servizi reali).
    private static final String TEST_APP_ID = "test-app-id";
    private static final String TEST_PRIVATE_KEY_PEM = """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDlVG4EKX/9d3iH
            O4QhzcUIzfc1/T/ad7B1U6yEfqCe/qTGph1+RDVrHXg+s9KyCKRm7oFcjiY3gdlQ
            5mVjeGDjYaX32TraKjUf/kn3O3zHE4aSUus0KONgdmL6E8/8gEzTNdWnX1kNfivR
            1XOui3M6TxrGgHJwiVeHB+qV/AasHmxdMHltMIKHgof4aR4fP2pLqbicV1hH7pAf
            Gm5qycRQH+J8GYDpdci/NO/uSUy60NOUbLO03hYRbsvHCuJRRlnDN6p15CVhUCBq
            m3ICEl4FGRZbAohif49jGYOe9nNCINg8H9USl4amz/xGx0tDKrJjtWBJ69wIjL1a
            8ryf/rpLAgMBAAECggEAJIShUEBbhT01EDPGAqwFqDVEHHDLbjqI+uXsHt0bLrZV
            /twM7kJmTzxOHY39CySdKVbpepeYNlaTCHLnykMtCohOKX2KBZubAhE4LgpnADx3
            vW0+zvuD2aSAdloZUJgNo8R61UA1qhRhXfSjizPhkXNvTBTAJHN6YKI+FMwhM98H
            LSJ2JECeHYIJHodu2Evy6fmAzAEa0/ed9JWrvTSYdURY7oSu3Avhptr9Qq54E77h
            MHeU1s5tcwEkmxB3WPkE3iY3d8BlrkG9UA0PK+gzZvTkv4BwbZzfFre+y1NBa4Na
            ZytgBq2HLRn2keUX+m6T3UqnUB7PkhAT+QacZ7qswQKBgQD2LHyggBmjA94pQVd1
            DRK11htVkNi/i4tcki+Mr2x92I0BEvkDmBRg9cBoP+5cuMVaUJ6hzMKhj8eXHJjI
            Z4LOxh92psgbeA+QVQWSYb0Tdck8iEVXJLfR6R0XLwxBdGIT1uzE5WWCSfwbSECx
            ZXFArb2q3hvvVEtqEPCT7DBNGQKBgQDue9LfI6zxBqGO4Q02FCqp6bjnX9UrNJ/9
            YdnmE6p01UKpNvJvwlmDlh8pPzCUTs090auY3SK1uO4oTB/Q4ApwnUB9pYJYPtQm
            EZo1wOzO8mhjen+S0oBKSp8rsxP554hDNHzFv/KVtvlFeXsTKAm/6sslgaQrew8J
            eW+gvwPLAwKBgQDamV6B177sNv8Me22CD33R4rKbJOiUDwJNzbJTp2MbzTRJA+QE
            AQP3pcKZ0EuGIr19GaID46Phe5+s3EP/kYtiuiQuZKPFYLPRYu5zsj8IDMwJ6KhK
            XdytleezMqAbb5G4NF5D6cBeFfy87UglPwN2f9Gw7VK5D414PlqjwFST4QKBgFa2
            NQ0nNpmIlLOTxq70FDMlpTKTmLmV79o8evL6EY9bf1pxfSL/onaC3h+sNyilomCo
            3OC+/wDeFdIXI7ZZz9H0i19kD4mwhoi0+8IxvKjeYPBSuRIUccsRaOCFw2ypL9Vn
            vzpTuYuQNQB61DI565mZcHXZtmyM2QHT4q+q5GErAoGBAMEJnufhXm4QSXMu9VvQ
            HzuGLvpN2j4e/UTd8A5ten7j1Tn2bprpLpfAWTkMjM25bmxsapKV+VN56zWuZi1X
            e+OOlgDT+AICUXix7pBpL3ZPDcpXhw+KLUmBbUxlqMo5cyBaT0IsS1pSwuSuJowf
            yUpKDwyI2r4F3+5zuSUCEZgN
            -----END PRIVATE KEY-----
            """;

    private EnableBankingService enableBankingService;

    @BeforeEach
    void setUp() {
        enableBankingService = new EnableBankingService(new ObjectMapper());
        ReflectionTestUtils.setField(enableBankingService, "baseUrl", "https://api.enablebanking.com");
        ReflectionTestUtils.setField(enableBankingService, "appId", TEST_APP_ID);
        ReflectionTestUtils.setField(enableBankingService, "privateKeyPem", TEST_PRIVATE_KEY_PEM);
        // @PostConstruct non scatta fuori da uno ApplicationContext: invocato manualmente.
        ReflectionTestUtils.invokeMethod(enableBankingService, "init");
    }

    @Test
    void currentToken_isSignedRS256WithExpectedHeaderAndClaims() {
        String token = (String) ReflectionTestUtils.invokeMethod(enableBankingService, "currentToken");
        assertNotNull(token);

        PublicKey publicKey = derivePublicKey();

        Jws<Claims> parsed = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token);

        assertEquals("RS256", parsed.getHeader().getAlgorithm());
        assertEquals(TEST_APP_ID, parsed.getHeader().getKeyId());

        Claims claims = parsed.getPayload();
        assertEquals("enablebanking.com", claims.getIssuer());
        assertTrue(claims.getAudience().contains("api.enablebanking.com"));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
        assertTrue(claims.getExpiration().after(claims.getIssuedAt()));
    }

    @Test
    void currentToken_isCachedUntilNearExpiry() {
        String first = (String) ReflectionTestUtils.invokeMethod(enableBankingService, "currentToken");
        String second = (String) ReflectionTestUtils.invokeMethod(enableBankingService, "currentToken");
        assertEquals(first, second, "il token andrebbe rigenerato solo in prossimità della scadenza");
    }

    /** Deriva la chiave pubblica dalla private key di test per verificare la firma nel test. */
    private PublicKey derivePublicKey() {
        try {
            PrivateKey privateKey = (PrivateKey) ReflectionTestUtils.getField(enableBankingService, "privateKey");
            RSAPrivateCrtKey rsaPrivateKey = (RSAPrivateCrtKey) privateKey;
            RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(rsaPrivateKey.getModulus(), rsaPrivateKey.getPublicExponent());
            return KeyFactory.getInstance("RSA").generatePublic(publicKeySpec);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
