package com.loresentry.content.refresh;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.loresentry.content.document.DocumentSnapshot;

/**
 * 작업본 스냅샷을 정규형으로 맞춘다.
 *
 * <p>두 작업본이 같은지는 DB 가 jsonb 로 비교한다(배열 순서까지 본다). 그래서 같은 내용은 언제나 같은 모양이어야
 * 한다: 관계는 대상마다 하나(설명이 적힌 쪽을 남긴다 — 문서 저장과 같은 규칙), {@code (relation_key, 대상)} 순서,
 * 설명이 없으면 빈 문자열. 제목·본문·속성은 받은 그대로 둔다(속성 순서는 사용자가 정한 것이다).
 */
final class DraftSnapshots {

    private DraftSnapshots() {
    }

    /** 문서의 지금 상태를 작업본 모양으로. 관계 대상은 모두 기존 문서다. */
    static DraftSnapshot of(DocumentSnapshot document) {
        List<DraftSnapshot.Relation> relations = new ArrayList<>();
        for (DocumentSnapshot.Relation relation : document.relations()) {
            relations.add(new DraftSnapshot.Relation(relation.relationKey(), relation.targetDocumentId(), null,
                    relation.descriptionOrEmpty()));
        }
        return new DraftSnapshot(document.title(), document.body(), document.legacyBodyMd(),
                document.properties() == null ? List.of() : document.properties(), canonicalRelations(relations));
    }

    /** 관계를 바꾼 같은 스냅샷. 관계는 정규형으로 맞춘다. */
    static DraftSnapshot withRelations(DraftSnapshot snapshot, List<DraftSnapshot.Relation> relations) {
        return new DraftSnapshot(snapshot.title(), snapshot.body(), snapshot.legacyBodyMd(),
                snapshot.properties() == null ? List.of() : snapshot.properties(), canonicalRelations(relations));
    }

    /** 대상마다 하나, 설명이 적힌 것을 남기고, (관계 키, 대상) 순으로 정렬한다. 설명 null 은 빈 문자열이다. */
    static List<DraftSnapshot.Relation> canonicalRelations(List<DraftSnapshot.Relation> relations) {
        Map<String, DraftSnapshot.Relation> byTarget = new LinkedHashMap<>();
        for (DraftSnapshot.Relation relation : relations == null ? List.<DraftSnapshot.Relation>of() : relations) {
            DraftSnapshot.Relation normalized = new DraftSnapshot.Relation(relation.relationKey(),
                    relation.targetDocumentId(), relation.targetCandidateId(),
                    relation.description() == null ? "" : relation.description());
            DraftSnapshot.Relation existing = byTarget.get(normalized.targetKey());
            if (existing == null || existing.description().isEmpty()) {
                byTarget.put(normalized.targetKey(), normalized);
            }
        }
        List<DraftSnapshot.Relation> sorted = new ArrayList<>(byTarget.values());
        sorted.sort(Comparator.comparing((DraftSnapshot.Relation relation) ->
                        relation.relationKey() == null ? "" : relation.relationKey())
                .thenComparing(DraftSnapshot.Relation::targetKey));
        return List.copyOf(sorted);
    }
}
