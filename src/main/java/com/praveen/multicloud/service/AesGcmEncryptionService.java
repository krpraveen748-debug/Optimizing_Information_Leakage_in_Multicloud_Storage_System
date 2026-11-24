package com.praveen.multicloud.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AesGcmEncryptionService {
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BITS = 128;
    private static final Pattern KEY_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final String configuredKeyRing;
    private final String activeKeyId;
    private final SecureRandom secureRandom;
    private volatile Map<String, SecretKeySpec> parsedKeyRing;

    @Autowired
    public AesGcmEncryptionService(
            @Value("${app.encryption.key-ring:}") String configuredKeyRing,
            @Value("${app.encryption.active-key-id:}") String activeKeyId) {
        this(configuredKeyRing, activeKeyId, new SecureRandom());
    }

    AesGcmEncryptionService(String configuredKeyRing, String activeKeyId, SecureRandom secureRandom) {
        this.configuredKeyRing = configuredKeyRing;
        this.activeKeyId = activeKeyId;
        this.secureRandom = secureRandom;
    }

    public String activeKeyId() {
        if (activeKeyId == null || !KEY_ID_PATTERN.matcher(activeKeyId).matches()) {
            throw new EncryptionConfigurationException(
                    "APP_ENCRYPTION_ACTIVE_KEY_ID must be configured with a valid key ID.");
        }
        keyFor(activeKeyId);
        return activeKeyId;
    }

    public EncryptedPayload encrypt(byte[] plaintext, byte[] associatedData) {
        String keyId = activeKeyId();
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyFor(keyId), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData);
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new EncryptedPayload(ciphertext, Base64.getEncoder().encodeToString(nonce), keyId);
        } catch (GeneralSecurityException e) {
            throw new EncryptionOperationException("File chunk encryption failed.", e);
        }
    }

    public byte[] decrypt(
            byte[] ciphertext,
            String encodedNonce,
            String keyId,
            byte[] associatedData) {
        byte[] nonce;
        try {
            nonce = Base64.getDecoder().decode(encodedNonce);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new CiphertextIntegrityException("Encrypted file chunk metadata is invalid.", e);
        }
        if (nonce.length != NONCE_BYTES) {
            throw new CiphertextIntegrityException("Encrypted file chunk nonce is invalid.");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keyFor(keyId), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData);
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            throw new CiphertextIntegrityException("Encrypted file chunk authentication failed.", e);
        } catch (GeneralSecurityException e) {
            throw new EncryptionOperationException("File chunk decryption failed.", e);
        }
    }

    public static byte[] chunkAssociatedData(String plaintextHash, int plaintextSize, String keyId) {
        if (plaintextHash == null || !plaintextHash.matches("[0-9a-f]{64}") || plaintextSize < 0
                || keyId == null || !KEY_ID_PATTERN.matcher(keyId).matches()) {
            throw new IllegalArgumentException("Invalid chunk authentication metadata.");
        }
        String associatedData = "multicloud-storage:chunk:v1:"
                + plaintextHash + ":" + plaintextSize + ":" + keyId;
        return associatedData.getBytes(StandardCharsets.UTF_8);
    }

    private SecretKeySpec keyFor(String keyId) {
        if (keyId == null || !KEY_ID_PATTERN.matcher(keyId).matches()) {
            throw new EncryptionKeyUnavailableException("Encryption key is unavailable.");
        }
        Map<String, SecretKeySpec> keyRing = parseKeyRing();
        SecretKeySpec key = keyRing.get(keyId);
        if (key == null) {
            throw new EncryptionKeyUnavailableException(
                    "The requested encryption key ID is not present in APP_ENCRYPTION_KEY_RING.");
        }
        return key;
    }

    private Map<String, SecretKeySpec> parseKeyRing() {
        Map<String, SecretKeySpec> cached = parsedKeyRing;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (parsedKeyRing != null) {
                return parsedKeyRing;
            }
            parsedKeyRing = readKeyRing();
            return parsedKeyRing;
        }
    }

    private Map<String, SecretKeySpec> readKeyRing() {
        if (configuredKeyRing == null || configuredKeyRing.isBlank()) {
            throw new EncryptionConfigurationException("APP_ENCRYPTION_KEY_RING is not configured.");
        }
        Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
        for (String entry : configuredKeyRing.split(";", -1)) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new EncryptionConfigurationException(
                        "APP_ENCRYPTION_KEY_RING must use key-id=base64-key entries separated by semicolons.");
            }
            String keyId = entry.substring(0, separator);
            if (!KEY_ID_PATTERN.matcher(keyId).matches() || keys.containsKey(keyId)) {
                throw new EncryptionConfigurationException(
                        "APP_ENCRYPTION_KEY_RING contains an invalid or duplicate key ID.");
            }
            byte[] keyBytes;
            try {
                keyBytes = Base64.getDecoder().decode(entry.substring(separator + 1));
            } catch (IllegalArgumentException e) {
                throw new EncryptionConfigurationException(
                        "APP_ENCRYPTION_KEY_RING contains an invalid Base64 key.", e);
            }
            if (keyBytes.length != 32) {
                Arrays.fill(keyBytes, (byte) 0);
                throw new EncryptionConfigurationException(
                        "Each key in APP_ENCRYPTION_KEY_RING must decode to exactly 32 bytes.");
            }
            keys.put(keyId, new SecretKeySpec(keyBytes, "AES"));
            Arrays.fill(keyBytes, (byte) 0);
        }
        return Map.copyOf(keys);
    }

    public record EncryptedPayload(byte[] ciphertext, String nonceBase64, String keyId) {
        public EncryptedPayload {
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }

    public static class EncryptionConfigurationException extends RuntimeException {
        public EncryptionConfigurationException(String message) {
            super(message);
        }

        public EncryptionConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class EncryptionKeyUnavailableException extends RuntimeException {
        public EncryptionKeyUnavailableException(String message) {
            super(message);
        }
    }

    public static class EncryptionOperationException extends RuntimeException {
        public EncryptionOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class CiphertextIntegrityException extends RuntimeException {
        public CiphertextIntegrityException(String message) {
            super(message);
        }

        public CiphertextIntegrityException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
