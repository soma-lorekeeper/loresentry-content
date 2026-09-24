package com.loresentry.content.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class RanksTest {

    @Test
    void firstValueLeavesRoomOnBothSides() {
        String first = Ranks.first();

        assertThat(Ranks.between(null, first)).isLessThan(first);
        assertThat(Ranks.between(first, null)).isGreaterThan(first);
    }

    @Test
    void landsStrictlyBetweenItsNeighbours() {
        String a = Ranks.first();
        String b = Ranks.between(a, null);

        String middle = Ranks.between(a, b);

        assertThat(middle).isGreaterThan(a).isLessThan(b);
    }

    @Test
    void keepsSplittingTheSameGapWithoutRunningOut() {
        // 같은 자리에 계속 끼워 넣는 경우다. 자리가 없으면 한 글자 늘려서라도 사이를 만들어야 한다.
        String low = "A";
        String high = "B";

        for (int i = 0; i < 200; i++) {
            String next = Ranks.between(low, high);
            assertThat(next).as("iteration %d", i).isGreaterThan(low).isLessThan(high);
            high = next;
        }
    }

    @Test
    void appendingAtTheEndKeepsGrowing() {
        String last = Ranks.first();
        List<String> order = new ArrayList<>(List.of(last));

        for (int i = 0; i < 200; i++) {
            last = Ranks.between(last, null);
            order.add(last);
        }

        assertThat(order).isSorted();
    }

    @Test
    void prependingAtTheStartKeepsShrinking() {
        String head = Ranks.first();
        List<String> reversed = new ArrayList<>(List.of(head));

        for (int i = 0; i < 200; i++) {
            head = Ranks.between(null, head);
            reversed.add(head);
        }

        assertThat(reversed).isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    void handlesTheAdjacentCharacterCase() {
        // '0' 과 '1' 사이에는 넣을 문자가 없다. 한 자리 내려가야 한다.
        String between = Ranks.between("0", "1");

        assertThat(between).isGreaterThan("0").isLessThan("1").startsWith("0");
    }

    @Test
    void refusesNeighboursInTheWrongOrder() {
        assertThatIllegalArgumentException().isThrownBy(() -> Ranks.between("B", "A"));
        assertThatIllegalArgumentException().isThrownBy(() -> Ranks.between("A", "A"));
    }

    @Test
    void usesOnlyCharactersThatSortTheSameInJavaAndPostgresC() {
        // ASCII 오름차순이어야 COLLATE "C" 와 자바 문자열 비교가 같은 순서를 낸다.
        for (int i = 1; i < Ranks.ALPHABET.length(); i++) {
            assertThat(Ranks.ALPHABET.charAt(i)).isGreaterThan(Ranks.ALPHABET.charAt(i - 1));
        }
    }
}
