package com.loadflex.server.service;

import com.loadflex.common.mapper.TaskAttemptMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TaskRuntimeEstimator {

    private final TaskAttemptMapper taskAttemptMapper;

    public TaskRuntimeEstimator(TaskAttemptMapper taskAttemptMapper) {
        this.taskAttemptMapper = taskAttemptMapper;
    }

    public RuntimeEstimate estimate(String taskType) {
        List<Integer> runtimes = new ArrayList<>(taskAttemptMapper.selectRecentSuccessfulRuntimes(taskType));
        if (runtimes.isEmpty()) {
            int defaultSeconds = defaultRuntimeSeconds(taskType);
            return new RuntimeEstimate(defaultSeconds, defaultSeconds * 2, "LOW");
        }

        Collections.sort(runtimes);
        int p50Seconds = percentile(runtimes, 0.50);
        int p90Seconds = Math.max(p50Seconds, percentile(runtimes, 0.90));
        String confidence = runtimes.size() >= 20 ? "HIGH" : runtimes.size() >= 5 ? "MEDIUM" : "LOW";
        return new RuntimeEstimate(Math.max(1, p50Seconds), Math.max(1, p90Seconds), confidence);
    }

    private int percentile(List<Integer> sortedValues, double percentile) {
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        int boundedIndex = Math.max(0, Math.min(sortedValues.size() - 1, index));
        return sortedValues.get(boundedIndex);
    }

    private int defaultRuntimeSeconds(String taskType) {
        switch (taskType) {
            case "PROFILE":
                return 3;
            case "FEATURE":
                return 4;
            case "CLUSTER":
                return 5;
            case "FORECAST":
                return 6;
            case "BASELINE":
                return 4;
            default:
                return 4;
        }
    }

    public record RuntimeEstimate(int p50Seconds, int p90Seconds, String confidence) {}
}
