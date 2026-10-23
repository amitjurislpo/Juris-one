package com.jurislpo.jurisone.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("board card positions")
class TaskPositionsTest {

    @Test
    @DisplayName("a new card goes to the bottom of its column")
    void append() {
        assertThat(TaskPositions.append(null)).isEqualTo(TaskPositions.GAP);
        assertThat(TaskPositions.append(100L)).isEqualTo(100 + TaskPositions.GAP);
    }

    @Test
    @DisplayName("a dropped card takes the middle, the top or the bottom")
    void between() {
        assertThat(TaskPositions.between(null, null)).isEqualTo(TaskPositions.GAP);
        assertThat(TaskPositions.between(null, TaskPositions.GAP)).isZero();
        assertThat(TaskPositions.between(null, 10L)).isEqualTo(10 - TaskPositions.GAP); // positions may be negative
        assertThat(TaskPositions.between(TaskPositions.GAP, null)).isEqualTo(2 * TaskPositions.GAP);
        assertThat(TaskPositions.between(0L, 10L)).isEqualTo(5L);
        assertThat(TaskPositions.between(Long.MAX_VALUE - 10, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE - 5); // no overflow
    }

    @Test
    @DisplayName("no room between neighbours means the column is renumbered first")
    void noRoom() {
        assertThat(TaskPositions.between(4L, 5L)).isNull();
        assertThat(TaskPositions.between(4L, 4L)).isNull();
        assertThat(TaskPositions.renumbered(0)).isEqualTo(TaskPositions.GAP);
        assertThat(TaskPositions.renumbered(2)).isEqualTo(3 * TaskPositions.GAP);
        assertThat(TaskPositions.between(TaskPositions.renumbered(0), TaskPositions.renumbered(1))).isNotNull();
    }
}
