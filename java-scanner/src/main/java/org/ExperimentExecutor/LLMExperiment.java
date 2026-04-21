package org.ExperimentExecutor;

import org.LLMAdvisers.Advisers;
import org.LLMAdvisers.LLMResponse;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.*;

public class LLMExperiment {

    private static final Logger LOGGER = Logger.getLogger("MAIN");
    private static final String EXPERIMENT_MARK;
    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            prop.load(fis);
            EXPERIMENT_MARK = prop.getProperty("EXPERIMENT_MARK");
        } catch (IOException e) {
            LOGGER.severe("config loading failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    public static void main(String[] args) throws IOException {
        loggerInit();
        LOGGER.info("experiment process start.");
        // 获取原始数据
        List<ExampleHandler.ExampleData> experimentData = ExampleHandler.getRawFinalResultData();
        for (ExampleHandler.ExampleData data : experimentData) {
            if (! ExampleHandler.dataInLLMResult(data)) {
                ExampleHandler.addRawLLMResultData(handleDataByLLM(data));
                ExampleHandler.writeData2File(ExampleHandler.getRawLLMResultData(), ExampleHandler.LLM_BASELINE_OUTPUT_PATH);
            }
        }
        LOGGER.info("finished");
    }

    public static void mainContent() throws IOException {
        loggerInit();
        // 获取原始数据
        List<ExampleHandler.ExampleData> experimentData = ExampleHandler.getRawFinalResultData();

        // 创建一个调度线程池，用于定时添加任务
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        // 创建一个用于处理任务的线程池
        // 线程池大小可以根据需要调整
        int processingThreads = Math.min(Runtime.getRuntime().availableProcessors(), 4);
        var executor = Executors.newFixedThreadPool(processingThreads);

        // 使用读写锁保护对结果数据的并发访问
        ReadWriteLock rwLock = new ReentrantReadWriteLock();

        // 追踪已处理的任务数和总任务数
        AtomicInteger processedCount = new AtomicInteger(0);
        int totalTasks = 0;

        // 计算需要处理的任务总数
        for (ExampleHandler.ExampleData data : experimentData) {
            if (!ExampleHandler.dataInLLMResult(data)) {
                totalTasks++;
            }
        }

        final int finalTotalTasks = totalTasks;

        // 处理任务计数器
        AtomicInteger taskIndex = new AtomicInteger(0);

        // 上次任务提交时间
        final long[] lastTaskSubmitTime = {System.currentTimeMillis()};

        // 创建任务处理器，不使用固定速率调度，而是在每次执行后决定下次执行的延迟
        Runnable taskProcessor = new Runnable() {
            @Override
            public void run() {
                int currentIndex = taskIndex.getAndIncrement();
                if (currentIndex < experimentData.size()) {
                    ExampleHandler.ExampleData data = experimentData.get(currentIndex);

                    // 检查数据是否已经在结果中
                    boolean needsProcessing = false;

                    // 获取读锁检查数据
                    rwLock.readLock().lock();
                    try {
                        needsProcessing = !ExampleHandler.dataInLLMResult(data);
                    } finally {
                        rwLock.readLock().unlock();
                    }

                    long currentTime = System.currentTimeMillis();
                    long delay;

                    if (needsProcessing) {
                        // 提交任务到执行器
                        executor.submit(() -> {
                            try {
                                // 处理数据
                                ExampleHandler.ExampleData result = handleDataByLLM(data);

                                // 获取写锁更新结果
                                rwLock.writeLock().lock();
                                try {
                                    ExampleHandler.addRawLLMResultData(result);
                                    ExampleHandler.writeData2File(
                                            ExampleHandler.getRawLLMResultData(),
                                            ExampleHandler.LLM_BASELINE_OUTPUT_PATH
                                    );
                                } finally {
                                    rwLock.writeLock().unlock();
                                }

                                // 更新进度
                                int completed = processedCount.incrementAndGet();
                                System.out.printf("处理进度: %d/%d (%.2f%%)\n",
                                        completed, finalTotalTasks,
                                        (completed * 100.0) / finalTotalTasks);

                                // 检查是否所有任务都已完成
                                if (completed >= finalTotalTasks) {
                                    shutdown(scheduler, executor);
                                }
                            } catch (Exception e) {
                                System.err.println("处理数据时出错: " + e.getMessage());
                                e.printStackTrace();
                            }
                        });

                        // 记录本次提交时间
                        lastTaskSubmitTime[0] = currentTime;

                        // 需要处理的数据，等待3秒再处理下一条
                        delay = 3000;
                        System.out.println("提交了新任务，等待3秒后检查下一条数据");
                    } else {
                        // 如果数据已经处理，只等待0.01秒就继续下一个
                        delay = 10; // 0.01秒 = 10毫秒
                        System.out.println("数据 #" + currentIndex + " 已经在结果中，跳过处理，0.01秒后检查下一条");
                    }

                    // 安排下一次执行
                    scheduler.schedule(this, delay, TimeUnit.MILLISECONDS);
                } else {
                    // 所有数据都已经检查过，可以关闭调度器
                    LOGGER.info("所有数据已经检查完成，关闭调度器");
                    shutdown(scheduler, executor);
                }
            }
        };

        // 初始延迟0秒开始第一次执行
        scheduler.schedule(taskProcessor, 0, TimeUnit.MILLISECONDS);
    }

    /**
     * 关闭执行器和调度器
     */
    private static void shutdown(ScheduledExecutorService scheduler,
                                 java.util.concurrent.ExecutorService executor) {
        // 关闭调度器
        scheduler.shutdown();

        // 等待所有任务完成后关闭执行器
        executor.shutdown();
        try {
            // 等待最多1分钟让任务完成
            if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("所有数据处理完成");
    }

    private static void loggerInit() throws IOException{
        // 设置日志级别为 ALL，允许输出更详细的日志
        LOGGER.setLevel(Level.ALL);
        // 创建文件处理器
        Handler fileHandler = new FileHandler("LLMExperiment_Main" + EXPERIMENT_MARK + ".log", true);
        fileHandler.setLevel(Level.ALL); // 设置 Handler 级别
        // 设置日志格式
        fileHandler.setFormatter(new SimpleFormatter());
        // 将 Handler 添加到 Logger
        LOGGER.addHandler(fileHandler);
        LOGGER.setLevel(Level.ALL); // 设置 Logger 级别
    }

    private static ExampleHandler.ExampleData handleDataByLLM(ExampleHandler.ExampleData data) {
        ExampleHandler.ExampleData result = new ExampleHandler.ExampleData(data);
        Map<String, String> mainQuestionResult;
        try {
            mainQuestionResult = Advisers.handleLLMBaseline(data.getMethodBefore());
        } catch (RuntimeException ignore) {
            mainQuestionResult = null;
        }
        if (mainQuestionResult == null) {
            data.setLabel(-1);
        } else {
            result.setMethodResult(LLMResponse.stripCode(mainQuestionResult.get("result")));
        }
        return Main.analyzeExperimentDiff(result);
    }
}
