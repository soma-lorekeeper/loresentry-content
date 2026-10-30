package com.loresentry.content.manuscript;

public enum Purpose {
    BODY_JSON("body_json"),
    BODY_MD("body_md"),
    BODY_TEXT("body_text"),
    VERSION_SNAPSHOT("version_snapshot"),
    DRAFT_LEFT("draft_left"),
    DRAFT_RIGHT("draft_right"),
    DRAFT_ORIGIN("draft_origin"),
    REFRESH_INPUT("refresh_input"),
    REFRESH_RESULT("refresh_result");

    private final String wire;

    Purpose(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
