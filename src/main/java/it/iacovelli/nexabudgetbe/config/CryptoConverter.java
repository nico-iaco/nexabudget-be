package it.iacovelli.nexabudgetbe.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Converter JPA per crittografare/decrittografare le chiavi API Binance nel database
 */
@Converter(autoApply = false)
public class CryptoConverter implements AttributeConverter<String, String> {

    private static final String ALGORITHM = "AES";
    private static final String DEFAULT_KEY = "MySuperSecretKey1234567890123456";

    private String getEncryptionKey() {
        // Prova a leggere dalla variabile d'ambiente o usa il default
        String key = System.getenv("CRYPTO_ENCRYPTION_KEY");
        if (key == null || key.isEmpty()) {
            key = System.getProperty("crypto.encryption.key", DEFAULT_KEY);
        }
        // La chiave deve essere di 16, 24 o 32 byte per AES
        return key.length() >= 32 ? key.substring(0, 32) :
                String.format("%-32s", key).replace(' ', '0');
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            String key = getEncryptionKey();
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
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
            String key = getEncryptionKey();
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(dbData));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Errore durante la decrittografia", e);
        }
    }
}

