package com.fezzy.prodigy;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** V38: Secure Conduit biometric unlock. The master password is encrypted by an Android Keystore key that only a fingerprint / face check can use. */
final class ConduitBio {
    interface Cb { void ok(String v); void fail(String m); }
    private static final String ALIAS = "conduit_bio";

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("conduit_bio", Context.MODE_PRIVATE); }

    static boolean can(Context c) {
        try { return BiometricManager.from(c).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS; }
        catch (Throwable t) { return false; }
    }

    static boolean enrolled(Context c) { return sp(c).contains("ct"); }

    static void forget(Context c) {
        try { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry(ALIAS); } catch (Throwable ignored) {}
        sp(c).edit().clear().apply();
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (create) {
            try { ks.deleteEntry(ALIAS); } catch (Throwable ignored) {}
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build());
            return g.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    private static void prompt(FragmentActivity a, Cipher cipher, String title, BiometricPrompt.AuthenticationCallback cb) {
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title).setSubtitle("Notebook Conduit")
                .setNegativeButtonText("Use PIN")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();
        new BiometricPrompt(a, ContextCompat.getMainExecutor(a), cb).authenticate(info, new BiometricPrompt.CryptoObject(cipher));
    }

    static void enroll(final FragmentActivity a, final String secret, final Cb cb) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(true));
            prompt(a, c, "Enable biometric unlock", new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                    try {
                        Cipher k = r.getCryptoObject().getCipher();
                        byte[] ct = k.doFinal(secret.getBytes(StandardCharsets.UTF_8));
                        sp(a).edit().putString("iv", Base64.encodeToString(k.getIV(), Base64.NO_WRAP))
                                .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP)).apply();
                        cb.ok("ok");
                    } catch (Throwable t) { cb.fail("Could not save"); }
                }
                @Override public void onAuthenticationError(int code, CharSequence msg) { cb.fail(String.valueOf(msg)); }
            });
        } catch (Throwable t) { cb.fail("Biometric not available"); }
    }

    static void unlock(final FragmentActivity a, final Cb cb) {
        try {
            SharedPreferences p = sp(a);
            final byte[] iv = Base64.decode(p.getString("iv", ""), Base64.NO_WRAP);
            final byte[] ct = Base64.decode(p.getString("ct", ""), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
            prompt(a, c, "Unlock Notebook Conduit", new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                    try { cb.ok(new String(r.getCryptoObject().getCipher().doFinal(ct), StandardCharsets.UTF_8)); }
                    catch (Throwable t) { cb.fail("Unlock failed"); }
                }
                @Override public void onAuthenticationError(int code, CharSequence msg) { cb.fail(String.valueOf(msg)); }
            });
        } catch (Throwable t) { forget(a); cb.fail("Biometrics changed. Use your password, then turn it on again"); }
    }
}
