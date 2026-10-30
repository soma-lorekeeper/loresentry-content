package com.loresentry.content.manuscript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EnvelopeTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode vectors() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/manuscript-envelope-vectors.json")) {
            return json.readTree(in);
        }
    }

    @Test
    void opensEveryCrossLanguageVector() throws Exception {
        JsonNode vectors = vectors();
        byte[] key = HexFormat.of().parseHex(vectors.get("key_hex").asString());
        for (JsonNode vector : vectors.get("vectors")) {
            Purpose purpose = Purpose.valueOf(vector.get("purpose").asString().toUpperCase());
            String aad = Envelope.aad(UUID.fromString(vector.get("project_id").asString()),
                    UUID.fromString(vector.get("subject_id").asString()), purpose);
            byte[] envelope = HexFormat.of().parseHex(vector.get("envelope_hex").asString());

            assertThat(new String(Envelope.open(key, aad, envelope), StandardCharsets.UTF_8))
                    .isEqualTo(vector.get("plaintext").asString());
            String otherPurpose = aad.substring(0, aad.lastIndexOf(':') + 1) + "version_snapshot";
            assertThatThrownBy(() -> Envelope.open(key, otherPurpose, envelope))
                    .isInstanceOf(Envelope.EnvelopeException.class);
        }
    }

    @Test
    void macMatchesTheVector() throws Exception {
        JsonNode vectors = vectors();
        byte[] key = HexFormat.of().parseHex(vectors.get("key_hex").asString());

        assertThat(HexFormat.of().formatHex(Envelope.mac(key, vectors.get("vectors").get(0).get("plaintext").asString())))
                .isEqualTo(vectors.get("body_text_mac_hex").asString());
    }

    @Test
    void sealsWithAFreshNonceAndOpensBack() {
        byte[] key = new byte[32];
        String aad = Envelope.aad(UUID.randomUUID(), UUID.randomUUID(), Purpose.BODY_TEXT);
        byte[] plain = "서윤은 천천히 문을 밀었다.".repeat(1000).getBytes(StandardCharsets.UTF_8);

        byte[] first = Envelope.seal(key, aad, plain);
        byte[] second = Envelope.seal(key, aad, plain);

        assertThat(first[0]).isEqualTo((byte) 1);
        assertThat(first).isNotEqualTo(second);
        assertThat(first.length).isLessThan(plain.length);
        assertThat(Envelope.open(key, aad, first)).isEqualTo(plain);
        assertThat(Envelope.open(key, aad, Envelope.seal(key, aad, new byte[0]))).isEmpty();
    }

    @Test
    void refusesTamperedAndOversizedEnvelopes() {
        byte[] key = new byte[32];
        String aad = Envelope.aad(UUID.randomUUID(), UUID.randomUUID(), Purpose.BODY_TEXT);
        byte[] sealed = Envelope.seal(key, aad, "본문".getBytes(StandardCharsets.UTF_8));
        sealed[sealed.length - 1] ^= 1;
        byte[] huge = Envelope.seal(key, aad, new byte[32 * 1024 * 1024 + 1]);

        assertThatThrownBy(() -> Envelope.open(key, aad, sealed)).isInstanceOf(Envelope.EnvelopeException.class);
        assertThatThrownBy(() -> Envelope.open(key, aad, "{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(Envelope.EnvelopeException.class);
        assertThatThrownBy(() -> Envelope.open(key, aad, huge)).isInstanceOf(Envelope.EnvelopeException.class);
    }
}
