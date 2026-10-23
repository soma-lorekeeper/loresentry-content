package com.loresentry.content.refresh.confirm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import com.loresentry.content.refresh.DraftSnapshot;

/** 확정은 작업본마다 처음 뜬 관계와 합의한 관계의 차이만 쌍 단위로 적용한다. */
class RelationDeltaTest {

    private static final String A = "00000000-0000-0000-0000-00000000000a";
    private static final String B = "00000000-0000-0000-0000-00000000000b";
    private static final String C = "00000000-0000-0000-0000-00000000000c";
    private static final String NEW = "new:ITEM:은색 검";

    private static DraftSnapshot.Relation to(String target, String description) {
        return target.startsWith("new:")
                ? new DraftSnapshot.Relation("related_item", null, target, description)
                : new DraftSnapshot.Relation("related_character", UUID.fromString(target), null, description);
    }

    @Test
    void addsRemovesAndRewordsOnlyWhatTheDraftChanged() {
        RelationDelta.Plan plan = RelationDelta.merge(List.of(new RelationDelta.Side(A,
                List.of(to(B, "그대로"), to(C, "옛 설명")),
                List.of(to(C, "새 설명"), to(NEW, "검")))));

        assertThat(plan.conflicts()).isEmpty();
        assertThat(plan.ops()).containsExactlyInAnyOrder(
                new RelationDelta.Op(A, C, true, "새 설명"),
                new RelationDelta.Op(A, NEW, true, "검"),
                new RelationDelta.Op(A, B, false, null));
    }

    @Test
    void theSameChangeFromBothEndsIsAppliedOnce() {
        RelationDelta.Plan plan = RelationDelta.merge(List.of(
                new RelationDelta.Side(A, List.of(), List.of(to(B, "친구"))),
                new RelationDelta.Side(B, List.of(), List.of(to(A, "친구")))));

        assertThat(plan.ops()).containsExactly(new RelationDelta.Op(A, B, true, "친구"));
        assertThat(plan.conflicts()).isEmpty();
    }

    @Test
    void aDraftThatLeavesThePairAloneHasNoSay() {
        RelationDelta.Plan plan = RelationDelta.merge(List.of(
                new RelationDelta.Side(A, List.of(), List.of(to(B, "친구"))),
                new RelationDelta.Side(B, List.of(), List.of())));

        assertThat(plan.ops()).containsExactly(new RelationDelta.Op(A, B, true, "친구"));
    }

    @Test
    void differentChangesToOnePairConflict() {
        RelationDelta.Plan plan = RelationDelta.merge(List.of(
                new RelationDelta.Side(A, List.of(to(B, "")), List.of()),
                new RelationDelta.Side(B, List.of(to(A, "")), List.of(to(A, "적")))));

        assertThat(plan.ops()).isEmpty();
        assertThat(plan.conflicts()).hasSize(1);
        assertThat(plan.conflicts().getFirst().one()).isEqualTo(A);
        assertThat(plan.conflicts().getFirst().other()).isEqualTo(B);
    }
}
