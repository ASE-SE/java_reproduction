package org.ExperimentExecutor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.ASTAnalyzer.MethodDiffAnalyzer;
import org.ASTAnalyzer.TryPatternData;
import org.json.JSONObject;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Logger;

/**
 * 处理实验结果得到统计数据，使用ASTAnalyzer中method级解析实验结果和实验输入间差别，并记录结果
 * 实验结果表示为ExampleHandler.ExampleData
 */
@Deprecated
public class ResultProcessing {
    private static final Logger LOGGER = Logger.getLogger("EXPERIMENT");
    private static final String SOURCE_FILE_PATH = "D:\\Workspace\\uncaught exception\\data\\experiment_result_blllmE0502-c.json";
    private static final String TARGET_FILE_PATH = "D:\\Workspace\\uncaught exception\\data\\experiment_result_blllmE0502-p.json";
    private static List<ExampleHandler.ExampleData> rawData = new ArrayList<>();
    private static Set<String> dict = new HashSet<>();
    private static List<ExampleHandler.ExampleData> rawProcessedData = new ArrayList<>();
    private static Set<String> processedDict = new HashSet<>();
    public static void mainContent(String[] args) throws IOException {
        // 数据读取和准备
        Gson gson = new Gson();
        List<ExampleHandler.ExampleData> rawDuplicatedData;
        try (FileReader reader = new FileReader(SOURCE_FILE_PATH)) {
            Type type = new TypeToken<List<ExampleHandler.ExampleData>>() {}.getType();
            rawDuplicatedData = gson.fromJson(reader, type);
            LOGGER.info("loaded examples from: " + SOURCE_FILE_PATH);
        } catch (IOException e) {
            LOGGER.severe("example load error: " + e.getMessage());
            throw new RuntimeException(e);
        }
        for (ExampleHandler.ExampleData data: rawDuplicatedData) {
            String uniqueName = data.getRepo_id() + data.getPatch() + data.getMethodName();
            if (!dict.contains(uniqueName)) {
                dict.add(uniqueName);
                rawData.add(data);
            }
        }
        createEmptyJsonFileIfNotExists(TARGET_FILE_PATH);
        try (FileReader reader = new FileReader(TARGET_FILE_PATH)) {
            Type type = new TypeToken<List<ExampleHandler.ExampleData>>() {}.getType();
            rawProcessedData = gson.fromJson(reader, type);
            LOGGER.info("loaded examples from: " + TARGET_FILE_PATH);
        } catch (IOException e) {
            LOGGER.severe("example load error: " + e.getMessage());
            throw new RuntimeException(e);
        }
        for (ExampleHandler.ExampleData data: rawProcessedData) {
            String uniqueName = data.getRepo_id() + data.getPatch() + data.getMethodName();
            processedDict.add(uniqueName);
        }

        // 逐条分析数据结果
        for (ExampleHandler.ExampleData data: rawData) {
            if (dataInOutput(data)) continue;
            // 使用ASTA分析数据
            if (Objects.equals(data.getMethodBefore(), data.getMethodResult())) {
                data.setChanged(0);
            } else {
                List<TryPatternData> result = MethodDiffAnalyzer.analyze(data.getMethodBefore(), data.getMethodResult());
                if (result == null || result.isEmpty()) {
                    data.setChanged(0);
                } else {
                    JSONObject jsonObject = result.get(0).getJson();
                    data.setChanged(result.size());
                    data.setResultAfterTargetNoNestingLines(gson.fromJson(
                            jsonObject.get("afterTargetNoNestingLines").toString(),
                            new TypeToken<List<Integer>>(){}.getType()
                    ));
                    data.setResultBeforeTargetNoNestingLines(gson.fromJson(
                            jsonObject.get("beforeTargetNoNestingLines").toString(),
                            new TypeToken<List<Integer>>(){}.getType()
                    ));
                    data.setResultCatchBlocks(gson.fromJson(
                            jsonObject.get("catchBlocks").toString(),
                            new TypeToken<List<String>>(){}.getType()
                    ));
                    data.setResultExceptionTypes(gson.fromJson(
                            jsonObject.get("exceptionTypes").toString(),
                            new TypeToken<List<String>>(){}.getType()
                    ));
                    data.setResultAfterTargetEndLine((Integer) jsonObject.get("afterTargetEndLine"));
                    data.setResultAfterTargetStartLine((Integer) jsonObject.get("afterTargetStartLine"));
                    data.setResultBeforeTargetEndLine((Integer) jsonObject.get("beforeTargetEndLine"));
                    data.setResultBeforeTargetStartLine((Integer) jsonObject.get("beforeTargetStartLine"));
                }
            }
            rawProcessedData.add(data);
            writeProcessedData();
        }
    }

    /**
     * 检查给定的ExampleData是否存在于结果数据中（根据repo_id, patch和methodName匹配）
     * @param data 要检查的数据
     * @return 如果存在匹配的元素返回true，否则返回false
     */
    public static boolean dataInOutput(ExampleHandler.ExampleData data) {
        if (data == null || rawProcessedData == null || rawProcessedData.isEmpty()) {
            return false;
        }
        String uniqueName = data.getRepo_id() + data.getPatch() + data.getMethodName();
        return (processedDict.contains(uniqueName));
    }

    /**
     * 将结果rawProcessedData列表写入JSON文件
     * @throws IOException 如果写入文件失败
     */
    public static void writeProcessedData() throws IOException {
        // 确保父目录存在
        File outputFile = new File(TARGET_FILE_PATH);
        Gson gson = new GsonBuilder()
                .setPrettyPrinting()  // 使JSON格式化输出
                .disableHtmlEscaping() // 禁用HTML转义
                .create();
        Path filePath = outputFile.toPath();
        Path tempPath = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        Path backupPath = filePath.resolveSibling(filePath.getFileName() + ".bak");

        try {
            // 写入临时文件
            try (Writer writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
                gson.toJson(rawProcessedData, writer);
            }

            // 如果原文件存在，先创建备份
            if (Files.exists(filePath)) {
                Files.move(filePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // 原子性移动临时文件为目标文件
            Files.move(tempPath, filePath, StandardCopyOption.ATOMIC_MOVE);

            // 成功后删除备份
            Files.deleteIfExists(backupPath);

        } catch (Exception e) {
            // 恢复备份（如果存在）
            if (Files.exists(backupPath)) {
                try {
                    Files.move(backupPath, filePath, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception restoreEx) {
                    e.addSuppressed(restoreEx);
                }
            }
            throw new IOException("Failed to safely write JSON file", e);

        } finally {
            // 确保清理临时文件
            Files.deleteIfExists(tempPath);
        }
    }

    /**
     * 安全创建空JSON文件（如果存在则不覆盖）
     * @param filePath 目标文件路径
     * @return 是否创建了新文件
     * @throws IOException 如果创建过程中出错
     */
    public static boolean createEmptyJsonFileIfNotExists(String filePath) throws IOException {
        Path path = Paths.get(filePath);

        if (Files.exists(path)) {
            return false;
        }

        Path parentDir = path.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }

        Files.write(path, "[]".getBytes(StandardCharsets.UTF_8));
        return true;
    }
}
