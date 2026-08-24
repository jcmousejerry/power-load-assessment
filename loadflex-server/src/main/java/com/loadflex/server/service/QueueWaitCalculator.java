package com.loadflex.server.service;

import java.util.List;

final class QueueWaitCalculator {

    private QueueWaitCalculator() {}

    static long estimateWaitSeconds(long runningRemainingSeconds, List<Integer> tasksAheadRuntimeSeconds) {
        long waitSeconds = Math.max(0, runningRemainingSeconds);
        for (Integer runtimeSeconds : tasksAheadRuntimeSeconds) {
            if (runtimeSeconds != null) {
                waitSeconds += Math.max(0, runtimeSeconds);
            }
        }
        return waitSeconds;
    }
}
