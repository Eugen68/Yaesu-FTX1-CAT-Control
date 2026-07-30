package com.example.yaesucatcontroladv;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Helper pentru criptarea/decriptarea parolelor în backup-ul JSON.
 * Folosește AES-GCM-256 cu cheie derivată prin PBKDF2 din parola backup-ului.
 */
public class BackupCryptoHelper {
    private static final int ITERATIONS = 10000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    public static String encrypt(String plainText, String password) throws Exception {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);

        SecretKey key = deriveKey(password, salt);
        
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));

        byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        // Format: salt(16) + iv(12) + cipherText(N) -> Base64
        byte[] combined = new byte[SALT_LENGTH + IV_LENGTH + cipherText.length];
        System.arraycopy(salt, 0, combined, 0, SALT_LENGTH);
        System.arraycopy(iv, 0, combined, SALT_LENGTH, IV_LENGTH);
        System.arraycopy(cipherText, 0, combined, SALT_LENGTH + IV_LENGTH, cipherText.length);

        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    public static String decrypt(String encryptedBase64, String password) throws Exception {
        byte[] combined = Base64.decode(encryptedBase64, Base64.NO_WRAP);
        if (combined.length < SALT_LENGTH + IV_LENGTH) throw new Exception("Invalid encrypted data");

        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[IV_LENGTH];
        byte[] cipherText = new byte[combined.length - SALT_LENGTH - IV_LENGTH];

        System.arraycopy(combined, 0, salt, 0, SALT_LENGTH);
        System.arraycopy(combined, SALT_LENGTH, iv, 0, IV_LENGTH);
        System.arraycopy(combined, SALT_LENGTH + IV_LENGTH, cipherText, 0, cipherText.length);

        SecretKey key = deriveKey(password, salt);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));

        byte[] plainText = cipher.doFinal(cipherText);
        return new String(plainText, StandardCharsets.UTF_8);
    }

    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }
}