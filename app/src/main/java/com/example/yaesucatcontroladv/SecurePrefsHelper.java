package com.example.yaesucatcontroladv;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import android.util.Log;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Helper pentru gestionarea setărilor criptate (parole).
 * Folosește Jetpack Security (EncryptedSharedPreferences) legat de KeyStore-ul hardware.
 */
public class SecurePrefsHelper {
    private static final String TAG = "SecurePrefsHelper";
    private static final String SECURE_PREFS_NAME = "yaesu_secure_prefs";
    
    private static SharedPreferences encryptedPrefs;

    private static synchronized SharedPreferences getPrefs(Context context) {
        if (encryptedPrefs == null) {
            try {
                MasterKey masterKey = new MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();

                encryptedPrefs = EncryptedSharedPreferences.create(
                        context,
                        SECURE_PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
            } catch (GeneralSecurityException | IOException e) {
                Log.e(TAG, "Eroare la inițializarea EncryptedSharedPreferences", e);
            }
        }
        return encryptedPrefs;
    }

    public static void savePassword(Context context, String key, String value) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs != null) {
            prefs.edit().putString(key, value).apply();
        }
    }

    public static String getPassword(Context context, String key, String defaultValue) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs != null) {
            return prefs.getString(key, defaultValue);
        }
        return defaultValue;
    }

    public static void removePassword(Context context, String key) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs != null) {
            prefs.edit().remove(key).apply();
        }
    }
}