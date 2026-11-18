package com.hiking.gymsaas.account.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!bootstrap")
public class PhoneCipher {
    private static final int IV_BYTES = 12;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public PhoneCipher(@Value("${app.wechat.phone-cipher-key:}") String encoded) {
        byte[] raw = encoded.isBlank() ? new byte[0] : Base64.getDecoder().decode(encoded);
        if (!encoded.isBlank() && raw.length != 32) {
            throw new IllegalStateException("PHONE_CIPHER_KEY must encode exactly 32 bytes");
        }
        key = raw.length == 0 ? null : new SecretKeySpec(raw, "AES");
    }

    public boolean configured() { return key != null; }

    public String encrypt(String phone) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            byte[] payload = cipher(Cipher.ENCRYPT_MODE, iv).doFinal(phone.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + payload.length).put(iv).put(payload).array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot encrypt phone number", ex);
        }
    }

    public String mask(String stored) {
        if (!configured() || stored == null || !stored.startsWith("v1:")) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(stored.substring(3));
            if (bytes.length < IV_BYTES + 16) return null;
            String phone = new String(cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(bytes, IV_BYTES))
                    .doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
            return phone.length() < 8 ? null : phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            return null;
        }
    }

    private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, iv));
        return cipher;
    }
}
