package com.loresentry.content.manuscript;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class Manuscripts {

    private final ProjectKeys keys;

    Manuscripts(ProjectKeys keys) {
        this.keys = keys;
    }

    public byte[] seal(UUID projectId, UUID subjectId, Purpose purpose, String text) {
        return text == null ? null : sealBytes(projectId, subjectId, purpose, text.getBytes(StandardCharsets.UTF_8));
    }

    public String open(UUID projectId, UUID subjectId, Purpose purpose, byte[] envelope) {
        return envelope == null ? null
                : new String(openBytes(projectId, subjectId, purpose, envelope), StandardCharsets.UTF_8);
    }

    public byte[] sealBytes(UUID projectId, UUID subjectId, Purpose purpose, byte[] plain) {
        return Envelope.seal(keys.key(projectId), Envelope.aad(projectId, subjectId, purpose), plain);
    }

    public byte[] openBytes(UUID projectId, UUID subjectId, Purpose purpose, byte[] envelope) {
        return Envelope.open(keys.key(projectId), Envelope.aad(projectId, subjectId, purpose), envelope);
    }

    public byte[] mac(UUID projectId, String text) {
        return Envelope.mac(keys.key(projectId), text);
    }

    public String wrappedKey(UUID projectId) {
        return base64(keys.wrappedKey(projectId).orElseGet(() -> keys.requireWrappedKey(projectId)));
    }

    public static String base64(byte[] bytes) {
        return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
    }

    public static byte[] fromBase64(String text) {
        return text == null ? null : Base64.getDecoder().decode(text);
    }
}
