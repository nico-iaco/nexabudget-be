package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.config.CryptoConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CryptoConverter with AES-GCM encryption.
 */
class CryptoConverterTest {

    private CryptoConverter converter;

    @BeforeEach
    void setUp() {
        System.setProperty("crypto.encryption.key", "MySecureEncryptionKey1234567890123456");
        converter = new CryptoConverter();
    }

    @Test
    void testEncryptDecryptV2Format() {
        String plaintext = "MyBinanceApiKey123456789";
        String encrypted = converter.convertToDatabaseColumn(plaintext);

        assertNotNull(encrypted);
        assertTrue(encrypted.startsWith("v2:"), "Encrypted value should be in v2 format");

        String decrypted = converter.convertToEntityAttribute(encrypted);
        assertEquals(plaintext, decrypted, "Decrypted value should match original plaintext");
    }

    @Test
    void testEncryptProducesUniqueOutputs() {
        String plaintext = "SameValue";
        String encrypted1 = converter.convertToDatabaseColumn(plaintext);
        String encrypted2 = converter.convertToDatabaseColumn(plaintext);

        assertNotEquals(encrypted1, encrypted2, "Each encryption should produce different output due to random IV");
    }

    @Test
    void testLegacyFormatDecryption() {
        String plaintext = "LegacyEncryptedValue";
        String keyStr = "MySecureEncryptionKey1234567890123456";
        byte[] keyBytes = keyStr.getBytes(StandardCharsets.UTF_8);
        byte[] normalizedKey = new byte[32];
        System.arraycopy(keyBytes, 0, normalizedKey, 0, Math.min(keyBytes.length, 32));

        javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(normalizedKey, "AES");
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            String legacyEncrypted = Base64.getEncoder().encodeToString(encrypted);

            String decrypted = converter.convertToEntityAttribute(legacyEncrypted);
            assertEquals(plaintext, decrypted, "Legacy format should still be decryptable");
        } catch (Exception e) {
            fail("Legacy decryption should not throw exception: " + e.getMessage());
        }
    }

    @Test
    void testEncryptNullValue() {
        String result = converter.convertToDatabaseColumn(null);
        assertNull(result, "Encrypting null should return null");
    }

    @Test
    void testDecryptNullValue() {
        String result = converter.convertToEntityAttribute(null);
        assertNull(result, "Decrypting null should return null");
    }

    @Test
    void testMissingEncryptionKeyThrowsException() {
        System.clearProperty("crypto.encryption.key");
        System.clearProperty("CRYPTO_ENCRYPTION_KEY");

        CryptoConverter newConverter = new CryptoConverter();
        assertThrows(Exception.class, () -> newConverter.convertToDatabaseColumn("test"),
                "Should throw exception when encryption key is missing");
    }

    @Test
    void testShortEncryptionKeyThrowsException() {
        System.setProperty("crypto.encryption.key", "ShortKey");

        CryptoConverter newConverter = new CryptoConverter();
        assertThrows(Exception.class, () -> newConverter.convertToDatabaseColumn("test"),
                "Should throw exception when encryption key is too short");
    }
}
