package com.loadflex.server.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "loadflex.grid.spark-scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class GridSparkBatchScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GridSparkBatchScheduler.class);

    private final Path projectRoot;
    private final Path sparkScript;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "grid-spark-batch-launcher");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Process activeProcess;

    public GridSparkBatchScheduler(
            @Value("${loadflex.grid.project-root:.}") String projectRoot,
            @Value("${loadflex.grid.spark-script:./scripts/Run-SparkThresholds.ps1}") String sparkScript) {
        this.projectRoot = Paths.get(projectRoot).toAbsolutePath().normalize();
        Path configuredScript = Paths.get(sparkScript);
        this.sparkScript = (configuredScript.isAbsolute()
                        ? configuredScript
                        : this.projectRoot.resolve(configuredScript))
                .normalize();
    }

    @Scheduled(
            initialDelayString = "${loadflex.grid.spark-initial-delay-ms:1800000}",
            fixedDelayString = "${loadflex.grid.spark-interval-ms:1800000}")
    public void scheduleBatch() {
        if (!running.compareAndSet(false, true)) {
            LOGGER.warn("上一次Spark批处理仍在运行，本轮调度跳过");
            return;
        }
        executor.submit(this::runSparkBatch);
    }

    private void runSparkBatch() {
        try {
            if (!Files.isRegularFile(sparkScript)) {
                throw new IllegalStateException("Spark启动脚本不存在：" + sparkScript);
            }
            LOGGER.info("开始执行周期Spark动态阈值批处理：{}", sparkScript);
            ProcessBuilder builder = new ProcessBuilder(
                            "powershell.exe",
                            "-NoProfile",
                            "-ExecutionPolicy",
                            "Bypass",
                            "-File",
                            sparkScript.toString())
                    .directory(projectRoot.toFile())
                    .inheritIO();
            activeProcess = builder.start();
            int exitCode = activeProcess.waitFor();
            if (exitCode == 0) {
                LOGGER.info("周期Spark动态阈值批处理执行成功");
            } else {
                LOGGER.error("周期Spark动态阈值批处理失败，退出代码={}", exitCode);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("周期Spark动态阈值批处理被中断");
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("无法执行周期Spark动态阈值批处理", exception);
        } finally {
            activeProcess = null;
            running.set(false);
        }
    }

    @PreDestroy
    public void stop() {
        Process process = activeProcess;
        if (process != null && process.isAlive()) {
            process.descendants()
                    .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                    .forEach(ProcessHandle::destroy);
            process.destroy();
        }
        executor.shutdownNow();
    }
}
