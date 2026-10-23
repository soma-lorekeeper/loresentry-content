package com.loresentry.content.refresh;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 확정할 때 관계를 무엇을 더하고 뺄지 정한다.
 *
 * <p>관계는 두 문서가 함께 가진 한 행이다. 문서 저장처럼 "이 문서의 관계 목록을 통째로 맞추면" 검토하는 동안 다른
 * 문서가 더한 관계를 지운다. 그래서 작업본마다 <b>처음 뜬 관계(origin)와 합의한 관계의 차이</b>만 구해 쌍 단위로
 * 더하고 뺀다. 같은 쌍을 두 작업본이 다르게 바꾸면(한쪽은 더하고 한쪽은 빼거나, 설명이 다르면) 충돌이다. 한쪽 작업본만
 * 그 쌍을 바꾸면 그 결정을 따른다.
 *
 * <p>끝점은 문자열이다: 기존 문서 id 이거나, 확정 때 만들어질 새 설정 문서의 임시 이름이다.
 */
final class RelationDelta {

    private RelationDelta() {
    }

    /**
     * 작업본 하나의 관계 결정.
     *
     * @param self   이 작업본의 문서
     * @param origin 처음 뜬 관계(새 문서는 빈 목록)
     * @param agreed 합의한 관계
     */
    record Side(String self, List<DraftSnapshot.Relation> origin, List<DraftSnapshot.Relation> agreed) {
    }

    /**
     * 쌍 하나에 할 일. 끝점 순서는 문자열 순이다.
     *
     * @param put         true 면 만들거나 설명을 바꾸고, false 면 지운다
     * @param description 만들 때의 설명
     */
    record Op(String one, String other, boolean put, String description) {
    }

    /** 할 일들과, 두 작업본이 서로 다르게 바꾼 쌍들. */
    record Plan(List<Op> ops, List<Op> conflicts) {
    }

    static Plan merge(List<Side> sides) {
        Map<String, Op> ops = new LinkedHashMap<>();
        Set<String> conflicted = new LinkedHashSet<>();
        List<Op> conflicts = new ArrayList<>();
        for (Side side : sides) {
            Map<String, String> before = descriptions(side.origin());
            Map<String, String> after = descriptions(side.agreed());
            List<Op> changes = new ArrayList<>();
            after.forEach((target, description) -> {
                if (!before.containsKey(target) || !Objects.equals(before.get(target), description)) {
                    changes.add(op(side.self(), target, true, description));
                }
            });
            before.keySet().forEach(target -> {
                if (!after.containsKey(target)) {
                    changes.add(op(side.self(), target, false, null));
                }
            });
            for (Op change : changes) {
                String pair = change.one() + "|" + change.other();
                Op existing = ops.get(pair);
                if (existing == null) {
                    ops.put(pair, change);
                } else if (!existing.equals(change) && conflicted.add(pair)) {
                    conflicts.add(existing);
                }
            }
        }
        conflicted.forEach(ops::remove);
        return new Plan(List.copyOf(ops.values()), List.copyOf(conflicts));
    }

    /** 대상 → 설명. 정규형 작업본이라 대상마다 하나다. */
    private static Map<String, String> descriptions(List<DraftSnapshot.Relation> relations) {
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (DraftSnapshot.Relation relation : relations == null ? List.<DraftSnapshot.Relation>of() : relations) {
            descriptions.putIfAbsent(relation.targetKey(),
                    relation.description() == null ? "" : relation.description());
        }
        return descriptions;
    }

    private static Op op(String self, String target, boolean put, String description) {
        boolean selfFirst = self.compareTo(target) < 0;
        return new Op(selfFirst ? self : target, selfFirst ? target : self, put, put ? description : null);
    }
}
