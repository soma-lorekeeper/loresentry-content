package com.loresentry.content.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 영어판은 한국어판을 옮긴 것이지 다른 예시가 아니다. 언어만 바꾼 사용자가 다른 문서, 다른 관계를 보게
 * 되면 안 되므로, 글을 뺀 모양이 두 파일에서 같아야 한다.
 */
class SampleSeedTest {

    private final SampleSeed korean = SampleSeed.load(SampleSeed.KOREAN);

    private final SampleSeed english = SampleSeed.load(SampleSeed.ENGLISH);

    @Test
    void englishHasTheSameDocumentsInTheSameOrder() {
        // 문단 수까지 본다. 번역하며 문단을 합치거나 나누면 본문 모양이 달라진다.
        assertThat(manuscripts(english)).isEqualTo(manuscripts(korean));
        assertThat(settings(english)).isEqualTo(settings(korean));
    }

    @Test
    void englishHasTheSameRelations() {
        assertThat(relations(english)).isEqualTo(relations(korean));
    }

    @Test
    void englishIsTranslatedThroughout() {
        List<String> texts = texts(english);

        assertThat(texts).filteredOn(SampleSeedTest::hasHangul).isEmpty();
        assertThat(texts).filteredOn(String::isBlank).isEmpty();
        assertThat(english.relations()).extracting(SampleSeed.Link::description)
                .filteredOn(SampleSeedTest::hasHangul).isEmpty();
    }

    /** 에피소드마다 원고의 키와 문단 수. */
    private static List<List<String>> manuscripts(SampleSeed seed) {
        return seed.episodes().stream()
                .map(episode -> episode.manuscripts().stream()
                        .map(manuscript -> manuscript.key() + " x" + manuscript.body().size())
                        .toList())
                .toList();
    }

    private static List<String> settings(SampleSeed seed) {
        return seed.settings().stream()
                .map(setting -> setting.key() + " " + setting.folderCode() + " x" + setting.body().size())
                .toList();
    }

    /** 설명은 있고 없음만 맞춘다. 빈 설명은 칩에 글이 없다는 뜻이라 번역해도 비어 있어야 한다. */
    private static List<String> relations(SampleSeed seed) {
        return seed.relations().stream()
                .map(link -> link.from() + " - " + link.to()
                        + (link.description() == null || link.description().isEmpty() ? "" : " described"))
                .toList();
    }

    /** 관계 설명을 뺀, 비어 있으면 안 되는 글 전부. */
    private static List<String> texts(SampleSeed seed) {
        List<String> texts = new ArrayList<>(List.of(seed.name(), seed.description()));
        for (SampleSeed.Episode episode : seed.episodes()) {
            texts.add(episode.title());
            for (SampleSeed.Manuscript manuscript : episode.manuscripts()) {
                texts.add(manuscript.title());
                texts.add(manuscript.description());
                texts.addAll(manuscript.body());
            }
        }
        for (SampleSeed.Setting setting : seed.settings()) {
            texts.add(setting.title());
            texts.add(setting.description());
            texts.addAll(setting.body());
        }
        return texts;
    }

    private static boolean hasHangul(String text) {
        return text.codePoints()
                .anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL);
    }
}
