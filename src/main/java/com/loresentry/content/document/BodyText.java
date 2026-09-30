package com.loresentry.content.document;

import tools.jackson.databind.JsonNode;

/**
 * 에디터 문서 구조에서 순수 텍스트를 뽑는다.
 *
 * <p>검색·글자 수·앞으로의 AI 추출이 이 값을 쓴다. 클라이언트가 보내는 값이 아니라 서버가 본문에서
 * 만드는 값이다 — 보내게 두면 본문과 어긋난 텍스트가 검색에 남는다.
 *
 * <p>규칙은 프론트의 {@code bodyToPlainText} 와 같아야 한다. 다르면 같은 문서의 글자 수가 화면과
 * 서버에서 다르게 보인다.
 */
final class BodyText {

    private BodyText() {
    }

    static String extract(JsonNode doc) {
        StringBuilder text = new StringBuilder();
        appendBlocks(doc == null ? null : doc.get("content"), text);
        return text.toString();
    }

    private static void appendBlocks(JsonNode blocks, StringBuilder text) {
        if (blocks == null || !blocks.isArray()) {
            return;
        }
        for (JsonNode block : blocks) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            appendInline(block, text);
        }
    }

    /** 블록 안에서는 글자를 이어 붙인다. 블록 사이만 줄바꿈이다. */
    private static void appendInline(JsonNode node, StringBuilder text) {
        if (node == null) {
            return;
        }
        String type = node.path("type").asString("");
        if ("text".equals(type)) {
            text.append(node.path("text").asString(""));
            return;
        }
        if ("hardBreak".equals(type)) {
            text.append('\n');
            return;
        }
        JsonNode children = node.get("content");
        if (children == null || !children.isArray()) {
            return;
        }
        boolean nested = "bulletList".equals(type) || "orderedList".equals(type)
                || "blockquote".equals(type) || "listItem".equals(type);
        for (int i = 0; i < children.size(); i++) {
            if (nested && i > 0) {
                text.append('\n');
            }
            appendInline(children.get(i), text);
        }
    }

    /** 글자 수는 줄바꿈을 빼고 코드 포인트로 센다. Markdown 기호를 세던 예전 값과 다르다. */
    static int charCount(String bodyText) {
        return (int) bodyText.codePoints().filter(point -> point != '\n').count();
    }
}
