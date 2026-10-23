package com.loresentry.content.document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 평문 본문을 에디터 본문({@code {"schema_version":1,"doc":{...}}})으로 되돌린다.
 *
 * <p>graph-rag 는 설정 문서 수정안을 평문 본문 전체로 준다({@link BodyText#extract} 와 같은 규칙으로 만든 텍스트를
 * 고친 것). 평문에는 제목·굵게 같은 서식이 없으므로, 기준 본문과 줄 단위로 맞춰 보고 <b>모든 줄이 그대로 남은
 * 블록은 원래 블록을 그대로 쓴다</b>. 바뀌거나 새로 생긴 줄만 서식 없는 문단이 된다. 그래서 고치지 않은 부분의
 * 서식은 유지되고, 사용자는 비교 화면에서 바뀐 줄만 다르게 본다.
 *
 * <p>지켜야 하는 성질: 결과를 {@link BodyText#extract} 하면 입력 평문(앞쪽 줄바꿈을 뺀 것)과 같다.
 */
final class BodyFromText {

    /** 줄 맞추기(LCS) 표의 칸 수 상한. 넘으면 앞뒤 공통 줄만 맞추고 가운데는 모두 새 문단으로 만든다. */
    static final long LCS_CELLS_MAX = 4_000_000L;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private BodyFromText() {
    }

    /**
     * @param text          새 본문 평문. 줄바꿈({@code \n})으로 줄을 나눈다
     * @param referenceBody 비교할 기준 본문(에디터 본문 전체). 없거나 옛 Markdown 문서면 null
     * @return 에디터 본문. 기준 본문은 바꾸지 않는다(재사용 블록은 복사한다)
     */
    static JsonNode fromText(String text, JsonNode referenceBody) {
        // extract 는 첫 글자 앞에 줄바꿈을 만들지 않는다(빈 블록이 앞에 있어도 글자가 생기기 전에는 줄바꿈을
        // 넣지 않는다). 그래서 앞쪽 줄바꿈은 본문으로 표현할 수 없어 뺀다.
        String stripped = text == null ? "" : text.replaceFirst("^\n+", "");
        List<String> newLines = List.of(stripped.split("\n", -1));

        Reference reference = Reference.of(referenceBody);
        int[] match = match(reference.lines, newLines);

        // 새 줄 위치 j 에서 시작하는, 통째로 남은 기준 블록. 블록의 모든 줄이 끊김 없이 이어서 맞아야 재사용한다 —
        // 목록·줄바꿈 문단처럼 여러 줄인 블록이 일부만 바뀌면 그 블록 서식을 살릴 방법이 없다.
        JsonNode[] reusedAt = new JsonNode[newLines.size()];
        int[] reusedLength = new int[newLines.size()];
        for (Reference.Block block : reference.blocks) {
            int first = match[block.start()];
            if (first < 0) {
                continue;
            }
            boolean whole = true;
            for (int k = 1; k < block.length(); k++) {
                if (match[block.start() + k] != first + k) {
                    whole = false;
                    break;
                }
            }
            if (whole) {
                reusedAt[first] = block.node();
                reusedLength[first] = block.length();
            }
        }

        ArrayNode content = NODES.arrayNode();
        // 앞쪽 빈 블록은 글자를 만들지 않으므로 그대로 둬도 결과 평문이 같다. 위쪽 여백을 지키려고 남긴다.
        reference.invisiblePrefix.forEach(node -> content.add(node.deepCopy()));
        int j = 0;
        while (j < newLines.size()) {
            if (reusedAt[j] != null) {
                content.add(reusedAt[j].deepCopy());
                j += reusedLength[j];
            } else {
                content.add(paragraph(newLines.get(j)));
                j++;
            }
        }
        if (content.isEmpty()) {
            content.add(paragraph(""));
        }

        ObjectNode body = NODES.objectNode();
        body.put("schema_version", 1);
        body.putObject("doc").put("type", "doc").set("content", content);
        return body;
    }

    /** 서식 없는 문단 하나. 빈 줄은 내용 없는 문단이다 — 에디터는 빈 텍스트 노드를 받지 않는다. */
    private static ObjectNode paragraph(String line) {
        ObjectNode paragraph = NODES.objectNode().put("type", "paragraph");
        if (!line.isEmpty()) {
            paragraph.putArray("content").addObject().put("type", "text").put("text", line);
        }
        return paragraph;
    }

    /**
     * 기준 줄마다 맞는 새 줄 위치(없으면 -1). 순서를 지키는 최장 공통 부분열(LCS)이다. 앞뒤 공통 줄을 먼저 잘라
     * 보통의 수정(가운데 몇 줄)은 작은 표로 끝난다.
     */
    static int[] match(List<String> reference, List<String> updated) {
        int n = reference.size();
        int m = updated.size();
        int[] match = new int[n];
        Arrays.fill(match, -1);

        int head = 0;
        while (head < n && head < m && reference.get(head).equals(updated.get(head))) {
            match[head] = head;
            head++;
        }
        int tail = 0;
        while (tail < n - head && tail < m - head
                && reference.get(n - 1 - tail).equals(updated.get(m - 1 - tail))) {
            match[n - 1 - tail] = m - 1 - tail;
            tail++;
        }

        int rows = n - head - tail;
        int columns = m - head - tail;
        if (rows == 0 || columns == 0 || (long) rows * columns > LCS_CELLS_MAX) {
            return match;
        }

        // lengths[i][k] = reference[head+i..] 와 updated[head+k..] 의 LCS 길이
        int[][] lengths = new int[rows + 1][columns + 1];
        for (int i = rows - 1; i >= 0; i--) {
            for (int k = columns - 1; k >= 0; k--) {
                lengths[i][k] = reference.get(head + i).equals(updated.get(head + k))
                        ? lengths[i + 1][k + 1] + 1
                        : Math.max(lengths[i + 1][k], lengths[i][k + 1]);
            }
        }
        int i = 0;
        int k = 0;
        while (i < rows && k < columns) {
            if (reference.get(head + i).equals(updated.get(head + k))) {
                match[head + i] = head + k;
                i++;
                k++;
            } else if (lengths[i + 1][k] >= lengths[i][k + 1]) {
                i++;
            } else {
                k++;
            }
        }
        return match;
    }

    /** 기준 본문을 {@link BodyText#extract} 와 같은 규칙으로 펼친 줄 목록과, 줄마다 속한 블록. */
    private record Reference(List<JsonNode> invisiblePrefix, List<Block> blocks, List<String> lines) {

        /** 최상위 블록 하나와 그 블록이 차지하는 줄 범위 [start, start + length). */
        private record Block(JsonNode node, int start, int length) {
        }

        static Reference of(JsonNode body) {
            List<JsonNode> prefix = new ArrayList<>();
            List<Block> blocks = new ArrayList<>();
            List<String> lines = new ArrayList<>();
            JsonNode content = body == null ? null : body.path("doc").get("content");
            if (content == null || !content.isArray()) {
                return new Reference(prefix, blocks, lines);
            }
            boolean visibleSoFar = false;
            for (JsonNode node : content) {
                String text = BodyText.blockText(node);
                // extract 는 글자가 생기기 전의 빈 블록에서 아무것도 만들지 않는다(줄바꿈도 없다).
                if (!visibleSoFar && text.isEmpty()) {
                    prefix.add(node);
                    continue;
                }
                visibleSoFar = true;
                String[] blockLines = text.split("\n", -1);
                blocks.add(new Block(node, lines.size(), blockLines.length));
                lines.addAll(List.of(blockLines));
            }
            return new Reference(prefix, blocks, lines);
        }
    }
}
