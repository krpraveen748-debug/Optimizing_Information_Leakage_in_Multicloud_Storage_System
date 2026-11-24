package com.praveen.multicloud.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AesGcmEncryptionServiceTest {
    private static final String KEY_ID = "test-key-v1";

    @Test
    void encryptsAndDecryptsWithTwelveByteNonceAndAuthenticationTag() {
        AesGcmEncryptionService encryption = newEncryptionService(KEY_ID);
        byte[] plaintext = "authenticated chunk payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] aad = AesGcmEncryptionService.chunkAssociatedData(
                ChunkingService.sha256(plaintext), plaintext.length, KEY_ID);

        AesGcmEncryptionService.EncryptedPayload encrypted = encryption.encrypt(plaintext, aad);
        byte[] nonce = Base64.getDecoder().decode(encrypted.nonceBase64());

        assertEquals(12, nonce.length);
        assertEquals(plaintext.length + 16, encrypted.ciphertext().length);
        assertEquals(KEY_ID, encrypted.keyId());
        assertArrayEquals(plaintext, encryption.decrypt(
                encrypted.ciphertext(), encrypted.nonceBase64(), encrypted.keyId(), aad));
    }

    @Test
    void usesFreshNoncesAndProducesDifferentCiphertextForRepeatedPlaintext() {
        AesGcmEncryptionService encryption = newEncryptionService(KEY_ID);
        byte[] plaintext = {1, 2, 3, 4};
        byte[] aad = AesGcmEncryptionService.chunkAssociatedData(
                ChunkingService.sha256(plaintext), plaintext.length, KEY_ID);

        AesGcmEncryptionService.EncryptedPayload first = encryption.encrypt(plaintext, aad);
        AesGcmEncryptionService.EncryptedPayload second = encryption.encrypt(plaintext, aad);

        assertNotEquals(first.nonceBase64(), second.nonceBase64());
        assertFalseArrayEquals(first.ciphertext(), second.ciphertext());
    }

    @Test
    void rejectsTamperedCiphertextInvalidTagsAndWrongKeys() {
        AesGcmEncryptionService encryption = newEncryptionService(KEY_ID);
        byte[] plaintext = {10, 20, 30, 40};
        byte[] aad = AesGcmEncryptionService.chunkAssociatedData(
                ChunkingService.sha256(plaintext), plaintext.length, KEY_ID);
        AesGcmEncryptionService.EncryptedPayload encrypted = encryption.encrypt(plaintext, aad);

        byte[] tampered = encrypted.ciphertext();
        tampered[tampered.length - 1] ^= 0x01;
        assertThrows(AesGcmEncryptionService.CiphertextIntegrityException.class, () ->
                encryption.decrypt(tampered, encrypted.nonceBase64(), KEY_ID, aad));

        byte[] invalidTag = Arrays.copyOf(encrypted.ciphertext(), encrypted.ciphertext().length - 1);
        assertThrows(AesGcmEncryptionService.CiphertextIntegrityException.class, () ->
                encryption.decrypt(invalidTag, encrypted.nonceBase64(), KEY_ID, aad));

        AesGcmEncryptionService wrongKey = newEncryptionService(KEY_ID);
        assertThrows(AesGcmEncryptionService.CiphertextIntegrityException.class, () ->
                wrongKey.decrypt(encrypted.ciphertext(), encrypted.nonceBase64(), KEY_ID, aad));
    }

    @Test
    void rejectsMissingOrMalformedKeyConfigurationAndUnknownKeyIds() {
        AesGcmEncryptionService missing = new AesGcmEncryptionService("", "", new SecureRandom());
        AesGcmEncryptionService.EncryptionConfigurationException missingActiveKeyId =
                assertThrows(AesGcmEncryptionService.EncryptionConfigurationException.class, missing::activeKeyId);
        assertTrue(missingActiveKeyId.getMessage().contains("APP_ENCRYPTION_ACTIVE_KEY_ID"));

        AesGcmEncryptionService missingKeyRing =
                new AesGcmEncryptionService("", KEY_ID, new SecureRandom());
        AesGcmEncryptionService.EncryptionConfigurationException missingKeyRingError =
                assertThrows(AesGcmEncryptionService.EncryptionConfigurationException.class,
                        missingKeyRing::activeKeyId);
        assertTrue(missingKeyRingError.getMessage().contains("APP_ENCRYPTION_KEY_RING"));

        AesGcmEncryptionService configured = newEncryptionService(KEY_ID);
        byte[] ciphertext = configured.encrypt(new byte[]{1}, new byte[]{2}).ciphertext();
        assertThrows(AesGcmEncryptionService.EncryptionKeyUnavailableException.class, () ->
                configured.decrypt(ciphertext, Base64.getEncoder().encodeToString(new byte[12]),
                        "not-configured", new byte[]{2}));
    }

    @Test
    void retainsOldKeysForDecryptionAndAuthenticatesTheKeyId() {
        byte[] oldKey = new byte[32];
        byte[] newKey = new byte[32];
        new SecureRandom().nextBytes(oldKey);
        new SecureRandom().nextBytes(newKey);
        String oldKeyId = "old-key";
        String newKeyId = "new-key";
        String oldKeyEncoded = Base64.getEncoder().encodeToString(oldKey);
        String newKeyEncoded = Base64.getEncoder().encodeToString(newKey);
        Arrays.fill(oldKey, (byte) 0);
        Arrays.fill(newKey, (byte) 0);

        AesGcmEncryptionService previous = new AesGcmEncryptionService(
                oldKeyId + "=" + oldKeyEncoded, oldKeyId, new SecureRandom());
        AesGcmEncryptionService rotated = new AesGcmEncryptionService(
                oldKeyId + "=" + oldKeyEncoded + ";" + newKeyId + "=" + newKeyEncoded,
                newKeyId, new SecureRandom());
        byte[] plaintext = {4, 3, 2, 1};
        byte[] oldAad = AesGcmEncryptionService.chunkAssociatedData(
                ChunkingService.sha256(plaintext), plaintext.length, oldKeyId);
        AesGcmEncryptionService.EncryptedPayload encrypted = previous.encrypt(plaintext, oldAad);

        assertArrayEquals(plaintext, rotated.decrypt(
                encrypted.ciphertext(), encrypted.nonceBase64(), oldKeyId, oldAad));
        String aliasKeyId = "old-key-alias";
        AesGcmEncryptionService alias = new AesGcmEncryptionService(
                oldKeyId + "=" + oldKeyEncoded + ";" + aliasKeyId + "=" + oldKeyEncoded,
                aliasKeyId, new SecureRandom());
        byte[] aliasAad = AesGcmEncryptionService.chunkAssociatedData(
                ChunkingService.sha256(plaintext), plaintext.length, aliasKeyId);
        assertThrows(AesGcmEncryptionService.CiphertextIntegrityException.class, () ->
                alias.decrypt(encrypted.ciphertext(), encrypted.nonceBase64(), aliasKeyId, aliasAad));
    }

    @Test
    void encryptsAndReconstructsLargeInputChunkByChunk() throws Exception {
        AesGcmEncryptionService encryption = newEncryptionService(KEY_ID);
        ChunkingService chunking = new ChunkingService(64 * 1024);
        byte[] content = new byte[2 * 1024 * 1024 + 137];
        new SecureRandom().nextBytes(content);
        ByteArrayOutputStream reconstructed = new ByteArrayOutputStream(content.length);
        Set<String> nonces = new HashSet<>();

        ChunkingService.Summary summary = chunking.process(
                new java.io.ByteArrayInputStream(content),
                chunk -> {
                    byte[] aad = AesGcmEncryptionService.chunkAssociatedData(
                            chunk.hash(), chunk.data().length, encryption.activeKeyId());
                    AesGcmEncryptionService.EncryptedPayload encrypted = encryption.encrypt(chunk.data(), aad);
                    assertTrue(nonces.add(encrypted.nonceBase64()));
                    reconstructed.write(encryption.decrypt(
                            encrypted.ciphertext(), encrypted.nonceBase64(), encrypted.keyId(), aad));
                });

        assertEquals(33, summary.chunkCount());
        assertEquals(content.length, summary.totalBytes());
        assertEquals(ChunkingService.sha256(content), summary.sha256());
        assertArrayEquals(content, reconstructed.toByteArray());
    }

    private static AesGcmEncryptionService newEncryptionService(String keyId) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String keyRing = keyId + "=" + Base64.getEncoder().encodeToString(key);
        Arrays.fill(key, (byte) 0);
        return new AesGcmEncryptionService(keyRing, keyId, new SecureRandom());
    }

    private static void assertFalseArrayEquals(byte[] first, byte[] second) {
        org.junit.jupiter.api.Assertions.assertFalse(Arrays.equals(first, second));
    }
}
