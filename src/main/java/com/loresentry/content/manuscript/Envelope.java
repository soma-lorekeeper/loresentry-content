package com.loresentry.content.manuscript;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class Envelope {

    static final byte FORMAT = 1;

    private static final int NONCE_BYTES = 12;

    private static final int TAG_BITS = 128;

    private static final int MAX_PLAIN_BYTES = 32 * 1024 * 1024;

    private static final byte[] MAC_LABEL = "lore-sentry:mac:v1".getBytes(StandardCharsets.UTF_8);

    private static final SecureRandom RANDOM = new SecureRandom();

    private Envelope() {
    }

    public static String aad(UUID projectId, UUID subjectId, Purpose purpose) {
        return "lore-sentry:v1:" + projectId + ":" + subjectId + ":" + purpose.wire();
    }

    public static boolean isEnvelope(byte[] bytes) {
        return bytes != null && bytes.length > 0 && bytes[0] == FORMAT;
    }

    public static byte[] seal(byte[] key, String aad, byte[] plain) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(deflate(plain));
            return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length).put(FORMAT).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException failure) {
            throw new EnvelopeException("Cannot seal manuscript", failure);
        }
    }

    public static byte[] open(byte[] key, String aad, byte[] envelope) {
        if (!isEnvelope(envelope) || envelope.length < 1 + NONCE_BYTES + TAG_BITS / 8) {
            throw new EnvelopeException("Not a manuscript envelope", null);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, envelope, 1, NONCE_BYTES));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return inflate(cipher.doFinal(envelope, 1 + NONCE_BYTES, envelope.length - 1 - NONCE_BYTES));
        } catch (GeneralSecurityException failure) {
            throw new EnvelopeException("Cannot open manuscript", failure);
        }
    }

    public static byte[] mac(byte[] key, String text) {
        try {
            return hmac(hmac(key, MAC_LABEL), text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            throw new EnvelopeException("Cannot compute manuscript mac", failure);
        }
    }

    private static byte[] hmac(byte[] key, byte[] message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message);
    }

    private static byte[] deflate(byte[] plain) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(plain);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, plain.length / 2));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] compressed) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 3);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (read == 0 && !inflater.finished() && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new EnvelopeException("Truncated manuscript", null);
                }
                if (out.size() + read > MAX_PLAIN_BYTES) {
                    throw new EnvelopeException("Manuscript exceeds the size limit", null);
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (DataFormatException failure) {
            throw new EnvelopeException("Corrupt manuscript", failure);
        } finally {
            inflater.end();
        }
    }

    public static final class EnvelopeException extends RuntimeException {

        EnvelopeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
