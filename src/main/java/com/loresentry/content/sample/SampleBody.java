package com.loresentry.content.sample;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

final class SampleBody {

    static final String CORE = "[core] ";

    private static final String ITEM = "- ";

    private static final String QUOTE = "> ";

    private static final String BOLD = "**";

    private static final Pattern HEADING = Pattern.compile("(#{1,3}) (.+)");

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private SampleBody() {
    }

    static ObjectNode paragraphs(List<String> lines) {
        ArrayNode content = NODES.arrayNode();
        for (String line : lines) {
            ObjectNode paragraph = content.addObject().put("type", "paragraph");
            paragraph.putArray("content").addObject().put("type", "text").put("text", line);
        }
        return body(content);
    }

    static ObjectNode blocks(List<String> lines) {
        ArrayNode content = NODES.arrayNode();
        ArrayNode group = null;
        String groupType = null;
        for (String line : lines) {
            String type = line.startsWith(ITEM) ? "bulletList" : line.startsWith(QUOTE) ? "blockquote" : null;
            if (type == null) {
                group = null;
                groupType = null;
                content.add(block(line));
                continue;
            }
            if (!type.equals(groupType)) {
                group = content.addObject().put("type", type).putArray("content");
                groupType = type;
            }
            ObjectNode paragraph = paragraph(inline(line.substring(2)));
            if ("bulletList".equals(type)) {
                group.addObject().put("type", "listItem").putArray("content").add(paragraph);
            } else {
                group.add(paragraph);
            }
        }
        return body(content);
    }

    static String problem(String line) {
        if (line == null || line.isBlank()) {
            return "blank line";
        }
        String text = line;
        if (line.startsWith(CORE)) {
            text = line.substring(CORE.length());
            if (text.startsWith(ITEM) || text.startsWith(QUOTE) || HEADING.matcher(text).matches()) {
                return "core mark on a non-paragraph: " + line;
            }
        } else if (line.startsWith(ITEM) || line.startsWith(QUOTE)) {
            text = line.substring(2);
        } else {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                text = heading.group(2);
            }
        }
        if (text.isBlank()) {
            return "empty block: " + line;
        }
        if ((text.length() - text.replace(BOLD, "").length()) / BOLD.length() % 2 != 0) {
            return "unbalanced bold: " + line;
        }
        return null;
    }

    private static ObjectNode block(String line) {
        if (line.startsWith(CORE)) {
            ObjectNode paragraph = NODES.objectNode().put("type", "paragraph");
            paragraph.putObject("attrs").put("core", true);
            paragraph.set("content", inline(line.substring(CORE.length())));
            return paragraph;
        }
        Matcher heading = HEADING.matcher(line);
        if (heading.matches()) {
            ObjectNode node = NODES.objectNode().put("type", "heading");
            node.putObject("attrs").put("level", heading.group(1).length());
            node.set("content", inline(heading.group(2)));
            return node;
        }
        return paragraph(inline(line));
    }

    private static ObjectNode paragraph(ArrayNode inline) {
        ObjectNode paragraph = NODES.objectNode().put("type", "paragraph");
        paragraph.set("content", inline);
        return paragraph;
    }

    private static ArrayNode inline(String text) {
        ArrayNode nodes = NODES.arrayNode();
        String[] parts = text.split(Pattern.quote(BOLD), -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            ObjectNode node = nodes.addObject().put("type", "text").put("text", parts[i]);
            if (i % 2 == 1) {
                node.putArray("marks").addObject().put("type", "bold");
            }
        }
        return nodes;
    }

    private static ObjectNode body(ArrayNode content) {
        ObjectNode body = NODES.objectNode();
        body.put("schema_version", 1);
        body.putObject("doc").put("type", "doc").set("content", content);
        return body;
    }
}
