package com.loresentry.content.sample;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 예시 프로젝트의 내용. {@code sample/glass-garden.json} 을 그대로 옮긴 모양이다.
 *
 * <p>문서는 {@code key} 로 서로를 가리킨다. id 는 만들 때마다 새로 생기므로 파일에 적을 수 없다.
 *
 * <p>영어판 {@code sample/glass-garden.en.json} 은 키, 폴더, 관계, 순서를 그대로 두고 글만 옮긴
 * 것이다. 두 파일이 같은 모양인지는 {@code SampleSeedTest} 가 맞춰 본다.
 */
record SampleSeed(
        String name,
        String description,
        List<Episode> episodes,
        List<Setting> settings,
        List<Link> relations) {

    static final String KOREAN = "sample/glass-garden.json";

    static final String ENGLISH = "sample/glass-garden.en.json";

    record Episode(String title, List<Manuscript> manuscripts) {
    }

    record Manuscript(String key, String title, String description, List<String> body) {
    }

    record Setting(
            String key,
            @JsonProperty("folder_code") String folderCode,
            String title,
            String description,
            List<String> body) {
    }

    /** 관계는 대칭이므로 한 쌍을 한 번만 적는다. 반대쪽은 저장이 채운다. */
    record Link(String from, String to, String description) {
    }

    static SampleSeed load(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            SampleSeed seed = JsonMapper.builder().build().readValue(in, SampleSeed.class);
            seed.check(resource);
            return seed;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** 틀린 시드는 요청 때가 아니라 기동 때 드러나야 한다. */
    private void check(String resource) {
        Set<String> keys = new HashSet<>();
        for (Episode episode : episodes) {
            for (Manuscript manuscript : episode.manuscripts()) {
                require(resource, keys.add(manuscript.key()), "duplicate key " + manuscript.key());
            }
        }
        for (Setting setting : settings) {
            require(resource, keys.add(setting.key()), "duplicate key " + setting.key());
        }
        Set<String> pairs = new HashSet<>();
        for (Link link : relations) {
            require(resource, keys.contains(link.from()) && keys.contains(link.to()),
                    "unknown relation end " + link.from() + " -> " + link.to());
            require(resource, !link.from().equals(link.to()), "self relation " + link.from());
            String pair = link.from().compareTo(link.to()) < 0
                    ? link.from() + "|" + link.to()
                    : link.to() + "|" + link.from();
            require(resource, pairs.add(pair), "duplicate relation " + pair);
        }
    }

    private static void require(String resource, boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(resource + ": " + message);
        }
    }
}
