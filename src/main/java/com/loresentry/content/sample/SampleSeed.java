package com.loresentry.content.sample;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 예시 프로젝트의 내용. {@code sample/starless-prince.json} 을 그대로 옮긴 모양이다.
 *
 * <p>문서는 {@code key} 로 서로를 가리킨다. id 는 만들 때마다 새로 생기므로 파일에 적을 수 없다.
 *
 * <p>영어판 {@code sample/glass-garden.en.json} 은 아직 이전 예시("The Glass Garden Records")다.
 * 한국어판을 옮긴 영어판이 생기면 두 파일이 같은 모양인지 {@code SampleSeedTest} 가 다시 맞춰 본다.
 */
record SampleSeed(
        String name,
        String description,
        List<Episode> episodes,
        List<Setting> settings,
        List<Link> relations,
        @JsonProperty("not_refreshed") List<String> notRefreshed,
        @JsonProperty("recorded_refresh") RecordedRefresh recordedRefresh) {

    static final String KOREAN = "sample/starless-prince.json";

    static final String ENGLISH = "sample/glass-garden.en.json";

    private static final String IMAGES = "sample/images/";

    private static final Map<String, String> IMAGE_TYPES = Map.of(
            "webp", "image/webp",
            "png", "image/png",
            "jpg", "image/jpeg");

    record Episode(String title, List<Manuscript> manuscripts) {
    }

    record Manuscript(String key, String title, String description, List<String> body) {
    }

    record Setting(
            String key,
            @JsonProperty("folder_code") String folderCode,
            String title,
            String description,
            List<String> body,
            String image) {
    }

    /** 관계는 대칭이므로 한 쌍을 한 번만 적는다. 반대쪽은 저장이 채운다. */
    record Link(String from, String to, String description) {
    }

    record RecordedRefresh(
            @JsonProperty("document_proposals") List<Edit> documentProposals,
            @JsonProperty("new_document_proposals") List<Candidate> newDocumentProposals,
            @JsonProperty("relation_proposals") List<Link> relationProposals) {

        record Edit(String target, @JsonProperty("body_text") String bodyText) {
        }

        record Candidate(
                @JsonProperty("candidate_id") String candidateId,
                @JsonProperty("folder_code") String folderCode,
                String name,
                @JsonProperty("body_text") String bodyText) {
        }

        record Link(String one, String other, String description) {
        }
    }

    static String imageType(String image) {
        return IMAGE_TYPES.get(image.substring(image.lastIndexOf('.') + 1));
    }

    static byte[] imageBytes(String image) {
        try {
            return new ClassPathResource(IMAGES + image).getContentAsByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
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
        Set<String> manuscripts = Set.copyOf(keys);
        for (Setting setting : settings) {
            require(resource, keys.add(setting.key()), "duplicate key " + setting.key());
            for (String line : setting.body()) {
                String problem = SampleBody.problem(line);
                require(resource, problem == null, setting.key() + ": " + problem);
            }
            if (setting.image() != null) {
                require(resource, imageType(setting.image()) != null,
                        setting.key() + ": unsupported image " + setting.image());
                require(resource, new ClassPathResource(IMAGES + setting.image()).exists(),
                        setting.key() + ": missing image " + setting.image());
            }
        }
        if (notRefreshed != null) {
            for (String key : notRefreshed) {
                require(resource, manuscripts.contains(key), "unknown not_refreshed manuscript " + key);
            }
        }
        if (recordedRefresh != null) {
            checkRecordedRefresh(resource, keys, manuscripts);
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

    private void checkRecordedRefresh(String resource, Set<String> keys, Set<String> manuscripts) {
        require(resource, notRefreshed != null, "recorded_refresh needs not_refreshed");
        for (RecordedRefresh.Edit edit : recordedRefresh.documentProposals()) {
            require(resource, keys.contains(edit.target()) && !manuscripts.contains(edit.target()),
                    "recorded edit must target a setting " + edit.target());
        }
        Set<String> candidates = new HashSet<>();
        for (RecordedRefresh.Candidate candidate : recordedRefresh.newDocumentProposals()) {
            require(resource, candidate.candidateId().equals(
                            "new:" + candidate.folderCode() + ":" + candidate.name()),
                    "recorded candidate id " + candidate.candidateId());
            candidates.add(candidate.candidateId());
        }
        for (RecordedRefresh.Link link : recordedRefresh.relationProposals()) {
            for (String end : List.of(link.one(), link.other())) {
                require(resource, keys.contains(end) || candidates.contains(end),
                        "unknown recorded relation end " + end);
            }
        }
    }

    private static void require(String resource, boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(resource + ": " + message);
        }
    }
}
