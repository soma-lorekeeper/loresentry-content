package com.loresentry.content.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * graph-rag 가 준 평문 수정안을 에디터 본문으로 되돌린다. 바뀌지 않은 블록은 서식째 남고, 바뀐 줄만 서식 없는 문단이
 * 된다. 어떤 경우든 결과를 다시 평문으로 뽑으면 입력과 같아야 한다.
 */
class BodyFromTextTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode body(String content) {
        return json.readTree("{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":" + content + "}}");
    }

    private static JsonNode blocks(JsonNode body) {
        return body.path("doc").path("content");
    }

    private static String text(JsonNode body) {
        return BodyText.extract(body.get("doc"));
    }

    private static final String HEADING =
            "{\"type\":\"heading\",\"attrs\":{\"level\":2},\"content\":[{\"type\":\"text\",\"text\":\"유나\"}]}";
    private static final String BOLD_LINE = "{\"type\":\"paragraph\",\"content\":["
            + "{\"type\":\"text\",\"marks\":[{\"type\":\"bold\"}],\"text\":\"파란 망토를 입는다.\"}]}";

    private static String paragraph(String text) {
        return "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}";
    }

    @Test
    void keepsTheWholeBodyWhenTheTextIsUnchanged() {
        JsonNode reference = body("[" + HEADING + "," + BOLD_LINE + "]");

        JsonNode result = BodyFromText.fromText(text(reference), reference);

        assertThat(result).isEqualTo(reference);
    }

    @Test
    void replacesOnlyTheChangedLineWithAPlainParagraph() {
        JsonNode reference = body("[" + HEADING + "," + BOLD_LINE + "," + paragraph("검은 검을 든다.") + "]");

        JsonNode result = BodyFromText.fromText("유나\n파란 망토를 입는다.\n은색 검을 든다.", reference);

        assertThat(blocks(result)).isEqualTo(json.readTree(
                "[" + HEADING + "," + BOLD_LINE + "," + paragraph("은색 검을 든다.") + "]"));
    }

    @Test
    void insertsAndDeletesLinesAroundKeptBlocks() {
        JsonNode reference = body("[" + HEADING + "," + paragraph("지울 줄") + "," + BOLD_LINE + "]");

        JsonNode result = BodyFromText.fromText("유나\n파란 망토를 입는다.\n새 줄", reference);

        assertThat(blocks(result)).isEqualTo(json.readTree(
                "[" + HEADING + "," + BOLD_LINE + "," + paragraph("새 줄") + "]"));
    }

    @Test
    void reusesAListOnlyWhenEveryItemSurvives() {
        String list = "{\"type\":\"bulletList\",\"content\":["
                + "{\"type\":\"listItem\",\"content\":[" + paragraph("검") + "]},"
                + "{\"type\":\"listItem\",\"content\":[" + paragraph("망토") + "]}]}";
        JsonNode reference = body("[" + paragraph("소지품") + "," + list + "]");

        assertThat(blocks(BodyFromText.fromText("소지품\n검\n망토\n반지", reference))).isEqualTo(json.readTree(
                "[" + paragraph("소지품") + "," + list + "," + paragraph("반지") + "]"));
        assertThat(blocks(BodyFromText.fromText("소지품\n은색 검\n망토", reference))).isEqualTo(json.readTree(
                "[" + paragraph("소지품") + "," + paragraph("은색 검") + "," + paragraph("망토") + "]"));
    }

    @Test
    void reusesAParagraphWithAHardBreakAsOneBlock() {
        String broken = "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"앞\"},"
                + "{\"type\":\"hardBreak\"},{\"type\":\"text\",\"text\":\"뒤\"}]}";
        JsonNode reference = body("[" + broken + "]");

        assertThat(blocks(BodyFromText.fromText("앞\n뒤\n끝", reference)))
                .isEqualTo(json.readTree("[" + broken + "," + paragraph("끝") + "]"));
    }

    @Test
    void keepsLeadingEmptyBlocksAndTurnsEmptyLinesIntoEmptyParagraphs() {
        JsonNode reference = body("[{\"type\":\"paragraph\"}," + paragraph("첫 줄") + "]");

        JsonNode result = BodyFromText.fromText("첫 줄\n\n셋째 줄", reference);

        assertThat(blocks(result)).isEqualTo(json.readTree("[{\"type\":\"paragraph\"},"
                + paragraph("첫 줄") + ",{\"type\":\"paragraph\"}," + paragraph("셋째 줄") + "]"));
        assertThat(text(result)).isEqualTo("첫 줄\n\n셋째 줄");
    }

    @Test
    void buildsPlainParagraphsWithoutAReference() {
        assertThat(blocks(BodyFromText.fromText("민수는\n유나의 동생이다.", null)))
                .isEqualTo(json.readTree("[" + paragraph("민수는") + "," + paragraph("유나의 동생이다.") + "]"));
        assertThat(blocks(BodyFromText.fromText("", null))).isEqualTo(json.readTree("[{\"type\":\"paragraph\"}]"));
        assertThat(BodyFromText.fromText("\n\n앞 줄바꿈", null).path("schema_version").asInt()).isEqualTo(1);
    }

    @Test
    void alwaysExtractsBackToTheGivenText() {
        String list = "{\"type\":\"orderedList\",\"content\":["
                + "{\"type\":\"listItem\",\"content\":[" + paragraph("하나") + "]},"
                + "{\"type\":\"listItem\",\"content\":[" + paragraph("둘") + "]}]}";
        JsonNode reference = body("[{\"type\":\"paragraph\"}," + HEADING + ",{\"type\":\"horizontalRule\"},"
                + list + "," + BOLD_LINE + "]");

        for (String updated : List.of("", "유나", "유나\n\n하나\n둘\n파란 망토를 입는다.", "둘\n하나\n유나",
                "유나\n\n하나\n셋\n둘\n\n\n파란 망토를 입는다.\n", "\n\n유나\n하나")) {
            assertThat(text(BodyFromText.fromText(updated, reference)))
                    .isEqualTo(updated.replaceFirst("^\n+", ""));
        }
    }

    @Test
    void fallsBackToPlainParagraphsInTheMiddleWhenTheTextIsTooLarge() {
        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        before.add("같은 머리");
        after.add("같은 머리");
        for (int i = 0; i < 2_100; i++) {
            before.add("옛 줄 " + i);
            after.add("새 줄 " + i);
        }
        StringBuilder content = new StringBuilder("[");
        for (int i = 0; i < before.size(); i++) {
            content.append(i == 0 ? "" : ",").append(paragraph(before.get(i)));
        }
        JsonNode reference = body(content.append("]").toString());

        JsonNode result = BodyFromText.fromText(String.join("\n", after), reference);

        assertThat(text(result)).isEqualTo(String.join("\n", after));
        assertThat(blocks(result).get(0)).isEqualTo(json.readTree(paragraph("같은 머리")));
    }
}
