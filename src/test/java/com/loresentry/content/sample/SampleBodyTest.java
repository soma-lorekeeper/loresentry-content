package com.loresentry.content.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SampleBodyTest {

    @Test
    void keepsManuscriptLinesAsPlainParagraphs() {
        JsonNode body = SampleBody.paragraphs(List.of("# 제목이 아니다", "- 목록도 아니다"));

        assertThat(body.get("schema_version").intValue()).isEqualTo(1);
        JsonNode content = body.get("doc").get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("type").stringValue()).isEqualTo("paragraph");
        assertThat(content.get(0).get("content").get(0).get("text").stringValue()).isEqualTo("# 제목이 아니다");
        assertThat(content.get(1).get("content").get(0).get("text").stringValue()).isEqualTo("- 목록도 아니다");
    }

    @Test
    void readsHeadingsListsQuotesAndCoreMarks() {
        JsonNode content = SampleBody.blocks(List.of(
                "> 별은 마음이 향하는 곳이다.",
                "> 둘째 줄",
                "# 개요",
                "## 버릇",
                "### 작은 제목",
                "- **나이** 열아홉",
                "- 둘째 항목",
                "[core] 명성은 밤에만 읽을 수 있다.",
                "그냥 문단"))
                .get("doc").get("content");

        assertThat(content).hasSize(7);
        assertThat(content.get(0).get("type").stringValue()).isEqualTo("blockquote");
        assertThat(content.get(0).get("content")).hasSize(2);
        assertThat(content.get(1).get("type").stringValue()).isEqualTo("heading");
        assertThat(content.get(1).get("attrs").get("level").intValue()).isEqualTo(1);
        assertThat(content.get(1).get("content").get(0).get("text").stringValue()).isEqualTo("개요");
        assertThat(content.get(2).get("attrs").get("level").intValue()).isEqualTo(2);
        assertThat(content.get(3).get("attrs").get("level").intValue()).isEqualTo(3);

        JsonNode list = content.get(4);
        assertThat(list.get("type").stringValue()).isEqualTo("bulletList");
        assertThat(list.get("content")).hasSize(2);
        JsonNode first = list.get("content").get(0);
        assertThat(first.get("type").stringValue()).isEqualTo("listItem");
        JsonNode inline = first.get("content").get(0).get("content");
        assertThat(inline.get(0).get("text").stringValue()).isEqualTo("나이");
        assertThat(inline.get(0).get("marks").get(0).get("type").stringValue()).isEqualTo("bold");
        assertThat(inline.get(1).get("text").stringValue()).isEqualTo(" 열아홉");
        assertThat(inline.get(1).has("marks")).isFalse();

        JsonNode core = content.get(5);
        assertThat(core.get("type").stringValue()).isEqualTo("paragraph");
        assertThat(core.get("attrs").get("core").booleanValue()).isTrue();
        assertThat(core.get("content").get(0).get("text").stringValue()).isEqualTo("명성은 밤에만 읽을 수 있다.");

        assertThat(content.get(6).has("attrs")).isFalse();
    }

    @Test
    void startsANewListAfterAnotherBlock() {
        JsonNode content = SampleBody.blocks(List.of("- 하나", "문단", "- 둘")).get("doc").get("content");

        assertThat(content).extracting(node -> node.get("type").stringValue())
                .containsExactly("bulletList", "paragraph", "bulletList");
    }

    @Test
    void rejectsLinesTheEditorCannotShow() {
        assertThat(SampleBody.problem("# 개요")).isNull();
        assertThat(SampleBody.problem("- **나이** 열아홉")).isNull();
        assertThat(SampleBody.problem("[core] 명성은 밤에만 읽는다.")).isNull();

        assertThat(SampleBody.problem("")).isNotNull();
        assertThat(SampleBody.problem("- ")).isNotNull();
        assertThat(SampleBody.problem("**굵게가 닫히지 않음")).isNotNull();
        assertThat(SampleBody.problem("[core] # 제목")).isNotNull();
        assertThat(SampleBody.problem("[core] - 항목")).isNotNull();
    }
}
