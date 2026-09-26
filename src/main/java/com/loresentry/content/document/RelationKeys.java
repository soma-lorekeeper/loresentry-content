package com.loresentry.content.document;

import java.util.Map;

/**
 * 관계 키는 <b>가리키는 쪽의 분류</b>를 이름에 담는다. 캐릭터 A가 장소 B를 가리키면 A에 저장되는
 * 키는 {@code related_place}이고, 같은 관계를 B에서 보면 키는 {@code related_character}다.
 *
 * <p>그래서 관계를 양방향으로 만들려면 서버가 이 표를 알아야 한다. 프론트의 같은 표
 * ({@code loresentry-frontend/src/services/api/mapping.ts})와 한 글자도 어긋나면 안 된다 —
 * 어긋난 키는 화면에서 아무 속성에도 속하지 않아 조용히 사라진다.
 *
 * <p>{@code LOCATION}만 코드와 키의 이름이 다르다. 대문자 변환으로 유추하지 않고 표로 둔 이유다.
 */
final class RelationKeys {

    private static final Map<String, String> BY_FOLDER_CODE = Map.of(
            "MANUSCRIPT", "related_manuscript",
            "CHARACTER", "related_character",
            "LOCATION", "related_place",
            "ORGANIZATION", "related_organization",
            "ITEM", "related_item",
            "EVENT", "related_event",
            "WORLDVIEW", "related_worldview");

    private RelationKeys() {
    }

    /**
     * 이 분류의 문서를 가리킬 때 쓰는 키. 모르는 분류면 {@code null}이다 — 키를 짐작해서 만들면
     * 화면이 읽지 못하는 관계가 쌓인다.
     */
    static String pointingAt(String folderCode) {
        return folderCode == null ? null : BY_FOLDER_CODE.get(folderCode);
    }
}
