package com.loresentry.content.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 추출 규칙은 프론트의 {@code bodyToPlainText} 와 같아야 한다. 다르면 글자 수가 화면과 서버에서 다르다. */
class BodyTextTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode doc(String content) {
        return json.readTree("{\"type\":\"doc\",\"content\":" + content + "}");
    }

    @Test
    void joinsBlocksWithNewlinesAndInlineTextWithout() {
        JsonNode body = doc("""
                [{"type":"paragraph","content":[
                    {"type":"text","text":"회귀를 "},
                    {"type":"text","marks":[{"type":"bold"}],"text":"반복"},
                    {"type":"text","text":"한다."}]},
                 {"type":"paragraph","content":[{"type":"text","text":"둘째 문단"}]}]
                """);

        assertThat(BodyText.extract(body)).isEqualTo("회귀를 반복한다.\n둘째 문단");
    }

    @Test
    void treatsAHardBreakAsANewline() {
        JsonNode body = doc("""
                [{"type":"paragraph","content":[
                    {"type":"text","text":"앞"},{"type":"hardBreak"},{"type":"text","text":"뒤"}]}]
                """);

        assertThat(BodyText.extract(body)).isEqualTo("앞\n뒤");
    }

    @Test
    void separatesListItems() {
        JsonNode body = doc("""
                [{"type":"bulletList","content":[
                    {"type":"listItem","content":[{"type":"paragraph","content":[
                        {"type":"text","text":"하나"}]}]},
                    {"type":"listItem","content":[{"type":"paragraph","content":[
                        {"type":"text","text":"둘"}]}]}]}]
                """);

        assertThat(BodyText.extract(body)).isEqualTo("하나\n둘");
    }

    @Test
    void readsAnEmptyParagraphAsNothing() {
        assertThat(BodyText.extract(doc("[{\"type\":\"paragraph\"}]"))).isEmpty();
        assertThat(BodyText.extract(null)).isEmpty();
    }

    @Test
    void countsCodePointsWithoutNewlines() {
        // 예전에는 Markdown 기호까지 세는 String.length() 였다.
        assertThat(BodyText.charCount("가나다\n라마")).isEqualTo(5);
        // 이모지 한 자는 UTF-16 두 칸을 쓰지만 한 글자다.
        assertThat(BodyText.charCount("가😀")).isEqualTo(2);
    }
}
