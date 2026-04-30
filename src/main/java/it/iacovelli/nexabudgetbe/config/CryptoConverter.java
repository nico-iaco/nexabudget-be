package it.iacovelli.nexabudgetbe.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Converter JPA per crittografare/decrittografare le chiavi API Binance nel database
 */
@Converter(autoApply = false)
public class CryptoConverter implements AttributeConverter<String, String> {

    private static final String LEGACY_TRANSFORMATION = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String VERSION_PREFIX = "v2:";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private SecretKeySpec getEncryptionKeySpec() {
        String key = System.getenv("CRYPTO_ENCRYPTION_KEY");
        if (key == null || key.isBlank()) {
            key = System.getProperty("crypto.encryption.key");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("CRYPTO_ENCRYPTION_KEY non configurata");
        }
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < KEY_LENGTH_BYTES) {
            throw new IllegalStateException("CRYPTO_ENCRYPTION_KEY deve essere di almeno 32 byte");
        }
        byte[] normalizedKey = Arrays.copyOf(keyBytes, KEY_LENGTH_BYTES);
        return new SecretKeySpec(normalizedKey, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            SecretKeySpec keySpec = getEncryptionKeySpec();
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Errore durante la crittografia", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            SecretKeySpec keySpec = getEncryptionKeySpec();
            if (dbData.startsWith(VERSION_PREFIX)) {
                String encoded = dbData.substring(VERSION_PREFIX.length());
                byte[] payload = Base64.getDecoder().decode(encoded);
                if (payload.length <= GCM_IV_LENGTH_BYTES) {
                    throw new IllegalStateException("Payload crittografato non valido");
                }
                byte[] iv = Arrays.copyOfRange(payload, 0, GCM_IV_LENGTH_BYTES);
                byte[] encrypted = Arrays.copyOfRange(payload, GCM_IV_LENGTH_BYTES, payload.length);
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
                byte[] decrypted = cipher.doFinal(encrypted);
                return new String(decrypted, StandardCharsets.UTF_8);
            }
            Cipher legacyCipher = Cipher.getInstance(LEGACY_TRANSFORMATION);
            legacyCipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decrypted = legacyCipher.doFinal(Base64.getDecoder().decode(dbData));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Errore durante la decrittografia", e);
        }
    }
}

