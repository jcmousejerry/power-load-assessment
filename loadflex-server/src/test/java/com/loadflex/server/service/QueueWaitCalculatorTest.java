package com.loadflex.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class QueueWaitCalculatorTest {

    @Test
    void returnsRunningRemainingTimeWhenNoTaskIsAhead() {
        assertThat(QueueWaitCalculator.estimateWaitSeconds(20, List.of())).isEqualTo(20);
    }

    @Test
    void addsOnlyTheEstimatedRuntimeOfTasksAhead() {
        assertThat(QueueWaitCalculator.estimateWaitSeconds(20, List.of(30, 10, 5)))
                .isEqualTo(65);
    }

    @Test
    void treatsInvalidDurationsAsZero() {
        assertThat(QueueWaitCalculator.estimateWaitSeconds(-1, java.util.Arrays.asList(10, null, -2)))
                .isEqualTo(10);
    }
}
