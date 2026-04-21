package org.ExperimentExecutor;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.callTreeGenerator.ExceptionCharacteristicManager;
import org.callTreeGenerator.Util;
import org.eclipse.jdt.core.dom.IMethodBinding;

import java.io.*;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Logger;

/**
 * 用于处理实验前、实验记录、实验结果等实验数据相关功能，将一次实验抽象为ExampleData类
 */
public class ExampleHandler {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    public static String EXAMPLE_REPO_PATH;
    public static String EXAMPLE_RESULT_PATH;
    public static String EXPERIMENT_DATA_PATH;
    private static List<ExampleData> rawExampleData;
    private static List<ExampleData> rawResultData;
    private static Map<String, List<ExampleData>> exampleData = new HashMap<>(); // 以仓库名为键进行分组记录的实验前数据
    private static Map<String, Set<String>> experimentDataFiles = new HashMap<>(); // 与EXPERIMENT_DATA_PATH中文件同步，记录文件夹与文件名

    public static final boolean LOAD_FINAL_RESULT = false;
    public static final String EXPERIMENT_FINAL_RESULT_PATH = "D:\\Workspace\\uncaught exception\\data\\experiment_result_cleanE0502.json";
    public static final String LLM_BASELINE_OUTPUT_PATH = "D:\\Workspace\\uncaught exception\\data\\experiment_result_clean-blllmE0507.json";
    private static List<ExampleData> rawFinalResultData;
    private static List<ExampleData> rawLLMResultData;


    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            prop.load(fis);
            EXAMPLE_REPO_PATH = prop.getProperty("EXPERIMENT_SOURCE");
            EXAMPLE_RESULT_PATH = prop.getProperty("EXPERIMENT_TARGET");
            EXPERIMENT_DATA_PATH = prop.getProperty("EXPERIMENT_INTERMEDIATE_PROCESS_DATA_PATH");
        } catch (IOException e) {
            LOGGER.severe("config loading failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
        // 创建 Gson 实例
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(EXAMPLE_REPO_PATH)) {
            Type type = new TypeToken<List<ExampleData>>() {}.getType();
            rawExampleData = gson.fromJson(reader, type);
            LOGGER.info("loaded examples from: " + EXAMPLE_REPO_PATH);
        } catch (IOException e) {
            LOGGER.severe("example load error: " + e.getMessage());
            throw new RuntimeException(e);
        }
        for (ExampleData methodData: rawExampleData) {
            if (exampleData.containsKey(methodData.getRepo_id())) {
                exampleData.get(methodData.getRepo_id()).add(methodData);
            } else {
                exampleData.put(methodData.getRepo_id(), new ArrayList<>(List.of(methodData)));
            }
        }
        File file = new File(EXAMPLE_RESULT_PATH);
        if (!file.exists()) {
            LOGGER.warning("Target file does not exist: " + EXAMPLE_RESULT_PATH + ", creating new empty JSON file");
            try {
                // 确保父目录存在
                file.getParentFile().mkdirs();
                // 创建空的JSON列表文件
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write("[]");
                }
                LOGGER.info("Created new empty JSON file: " + EXAMPLE_RESULT_PATH);
            } catch (IOException e) {
                LOGGER.severe("Failed to create new JSON file: " + e.getMessage());
                throw new RuntimeException(e);
            }
            // 初始化为空列表
            rawResultData = new ArrayList<ExampleData>();
        } else {
            // 文件存在，正常读取
            try (FileReader reader = new FileReader(EXAMPLE_RESULT_PATH)) {
                Type type = new TypeToken<List<ExampleData>>() {}.getType();
                rawResultData = gson.fromJson(reader, type);
                LOGGER.info("Loaded examples from: " + EXAMPLE_RESULT_PATH);
            } catch (IOException e) {
                LOGGER.severe("Example load error: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }
        updateExperimentDataRepo();
        if (LOAD_FINAL_RESULT) {
            try (FileReader reader = new FileReader(EXPERIMENT_FINAL_RESULT_PATH)) {
                Type type = new TypeToken<List<ExampleData>>() {}.getType();
                rawFinalResultData = gson.fromJson(reader, type);
                LOGGER.info("loaded examples from: " + EXPERIMENT_FINAL_RESULT_PATH);
            } catch (IOException e) {
                LOGGER.severe("example load error: " + e.getMessage());
                throw new RuntimeException(e);
            }

            try (FileReader reader = new FileReader(LLM_BASELINE_OUTPUT_PATH)) {
                Type type = new TypeToken<List<ExampleData>>() {}.getType();
                rawLLMResultData = gson.fromJson(reader, type);
                LOGGER.info("loaded examples from: " + LLM_BASELINE_OUTPUT_PATH);
            } catch (IOException e) {
                LOGGER.severe("example load error: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }
    }

    public static List<ExampleData> getRawFinalResultData() {
        return rawFinalResultData;
    }

    public static List<ExampleData> getRawLLMResultData() {
        return rawLLMResultData;
    }

    public static void addRawLLMResultData(ExampleData data) {
        rawLLMResultData.add(data);
    }

    /**
     * 根据ExampleHandler的规则，返回下一条实验数据
     * @return 下一条未被实验的数据，若无可选取数据，返回null
     */
    public static ExampleData getNextData(boolean withFilter) {
        if (!withFilter) {
            for (ExampleData exampleData1: rawExampleData) {
                if (dataInResult(exampleData1)) continue;
                return exampleData1;
            }
            return null;
        }
        for (ExampleData exampleData1: rawExampleData) {
            if (dataInResult(exampleData1)) continue;

            // 排除一些无法解析的仓库
            if (exampleData1.getRepo_id().equals("junit-team/junit5") ||
            exampleData1.getRepo_id().equals("OpenLiberty/open-liberty") ||
            exampleData1.getRepo_id().equals("opensourceBIM/BIMserver") ||
            exampleData1.getRepo_id().equals("openjdk/jdk")){
                continue;
            }

            List<String> exceptionTypes = exampleData1.getExceptionTypes();
            boolean runtimeFlag = false;
            for (String exception: exceptionTypes) {
                if (ExceptionCharacteristicManager.isArchivedRuntime(exception)) {
                    runtimeFlag = true;
                    break;
                }
            }
            if (!runtimeFlag) continue;
            return exampleData1;
        }
        return null;
    }

    public static ExampleData getDataByKeys(String targetRepoId, String targetPatch, String targetMethodName) {
        for (ExampleData resultData : rawResultData) {
            if (targetRepoId.equals(resultData.getRepo_id()) &&
                    targetPatch.equals(resultData.getPatch()) &&
                    targetMethodName.equals(resultData.getMethodName())) {
                return resultData;
            }
        }
        return null;
    }

    /**
     * 检查给定的ExampleData是否存在于结果数据中（根据repo_id, patch和methodName匹配）
     * @param data 要检查的数据
     * @return 如果存在匹配的元素返回true，否则返回false
     */
    public static boolean dataInResult(ExampleData data) {
        if (data == null || rawResultData == null || rawResultData.isEmpty()) {
            return false;
        }

        String targetRepoId = data.getRepo_id();
        String targetPatch = data.getPatch();
        String targetMethodName = data.getMethodName();

        // 如果任一关键字段为null，则认为不匹配
        if (targetRepoId == null || targetPatch == null || targetMethodName == null) {
            return false;
        }

        for (ExampleData resultData : rawResultData) {
            if (targetRepoId.equals(resultData.getRepo_id()) &&
                    targetPatch.equals(resultData.getPatch()) &&
                    targetMethodName.equals(resultData.getMethodName())) {
                return true;
            }
        }

        return false;
    }

    public static boolean dataInLLMResult(ExampleData data) {
        if (data == null || rawLLMResultData == null || rawLLMResultData.isEmpty()) {
            return false;
        }

        String targetRepoId = data.getRepo_id();
        String targetPatch = data.getPatch();
        String targetMethodName = data.getMethodName();

        // 如果任一关键字段为null，则认为不匹配
        if (targetRepoId == null || targetPatch == null || targetMethodName == null) {
            return false;
        }

        for (ExampleData resultData : rawLLMResultData) {
            if (targetRepoId.equals(resultData.getRepo_id()) &&
                    targetPatch.equals(resultData.getPatch()) &&
                    targetMethodName.equals(resultData.getMethodName())) {
                return true;
            }
        }

        return false;
    }

    /**
     * 根据EXPERIMENT_DATA_PATH中文件内容，同步experimentDataFiles
     */
    private static void updateExperimentDataRepo() {
        File rootDir = new File(EXPERIMENT_DATA_PATH);
        if (!rootDir.exists() || !rootDir.isDirectory()) {
            LOGGER.severe("Invalid EXPERIMENT_DATA_PATH: " + EXPERIMENT_DATA_PATH);
            throw new IllegalArgumentException("Invalid root directory path: " + EXPERIMENT_DATA_PATH);
        }

        // 遍历一级文件夹
        for (File firstLevelDir : Objects.requireNonNull(rootDir.listFiles(File::isDirectory))) {
            String firstLevelKey = firstLevelDir.getName();
            if (!experimentDataFiles.containsKey(firstLevelKey)) {
                experimentDataFiles.put(firstLevelKey, new HashSet<>());
            }
            // 遍历二级文件夹中的文件夹
            for (File file : Objects.requireNonNull(firstLevelDir.listFiles(File::isDirectory))) {
                String fileName = file.getName();
                experimentDataFiles.get(firstLevelKey).add(fileName);
            }
        }
    }

    /**
     * 将结果ExampleData列表写入JSON文件
     * @throws IOException 如果写入文件失败
     */
    public static void writeResultData() throws IOException {
        // 确保父目录存在
        File outputFile = new File(EXAMPLE_RESULT_PATH);
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
                gson.toJson(rawResultData, writer);
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

    public static void writeData2File(List<ExampleHandler.ExampleData> resultData, String path) throws IOException {
        // 确保父目录存在
        File outputFile = new File(path);
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
                gson.toJson(resultData, writer);
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

//    public static boolean dataInExperimentResult(ExampleData data){
//        updateExperimentDataRepo();
//        if (!experimentDataFiles.containsKey(data.getRepo_id().replaceAll("/", "_"))) {
//            return false;
//        }
//        return experimentDataFiles.get(data.getRepo_id().replaceAll("/", "_"))
//                .contains(data.getQualifiedName());
//    }

    public static boolean isFileExists(String absolutePath) {
        Path path = Paths.get(absolutePath);
        return Files.exists(path) && Files.isRegularFile(path);
    }

    public static String getDataRecord(ExampleData data, String name) {
        String filePath = data.getDataPath() + "\\" + name + ".json";
        if (! isFileExists(filePath)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(filePath));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warning("failed read data record: " + filePath);
        }
        return null;
    }

    public static List<String> disassemblyJsonListString(String string) {
        // 解析JSON数组
        JsonArray jsonArray = JsonParser.parseString(string).getAsJsonArray();

        List<String> jsonObjectStrings = new ArrayList<>();

        // 遍历JSON数组中的每个JSON对象
        for (int i = 0; i < jsonArray.size(); i++) {
            JsonObject jsonObject = jsonArray.get(i).getAsJsonObject();
            // 将JSON对象转换为字符串并添加到列表中
            jsonObjectStrings.add(jsonObject.toString());
        }

        return jsonObjectStrings;
    }

    public static boolean writeDataRecord(ExampleData data, String content, String name) {
        String filePath = data.getDataPath() + "\\" + name + ".json";
        try {
            Path path = Paths.get(filePath);
            // 如果父目录不存在，则创建所有必要的父目录
            Files.createDirectories(path.getParent());
            Files.write(path,
                    content.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            LOGGER.warning("failed save data: " + filePath + ", reason: " + e.getMessage());
        }
        return false;
    }

    public static String md5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));

            BigInteger number = new BigInteger(1, hashBytes);
            StringBuilder hexString = new StringBuilder(number.toString(16));

            while (hexString.length() < 32) {
                hexString.insert(0, '0');
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5算法不可用", e);
        }
    }

    public static Map<String, List<ExampleData>> getExampleData() {
        return exampleData;
    }

    public static Map<String, Set<String>> getExperimentDataFiles() {
        return experimentDataFiles;
    }

    public static void addResults(List<ExampleData> results) {
        if (results == null) return;
        rawResultData.addAll(results);
    }

    public static class ExampleData {
        private int afterTargetStartLine;
        private int beforeTargetEndLine;
        private List<Integer> afterTargetNoNestingLines;
        private String methodBefore;
        private List<String> exceptionTypes;
        private List<Integer> beforeTargetNoNestingLines;
        private int afterTargetEndLine;
        private List<String> catchBlocks;
        private String methodName;
        private String methodAfter;
        private int beforeTargetStartLine;
        private String repo_id;
        private String patch;
        private String file_path;
        private String method_name;
        private String methodResult; // 方法给出的新method
        private int label; // 实例正负例标签，需要catch=1，不需要=0
        private int changed;
        private int resultAfterTargetStartLine;
        private int resultAfterTargetEndLine;
        private int resultBeforeTargetStartLine;
        private int resultBeforeTargetEndLine;
        private List<Integer> resultBeforeTargetNoNestingLines;
        private List<Integer> resultAfterTargetNoNestingLines;
        private List<String> resultExceptionTypes;
        private List<String> resultCatchBlocks;

        public ExampleData(ExampleData other) {
            // 基本类型直接赋值
            this.afterTargetStartLine = other.afterTargetStartLine;
            this.beforeTargetEndLine = other.beforeTargetEndLine;
            this.afterTargetEndLine = other.afterTargetEndLine;
            this.beforeTargetStartLine = other.beforeTargetStartLine;
            this.label = other.label;

            // String类型直接赋值（String不可变，无需深拷贝）
            this.methodBefore = other.methodBefore;
            this.methodName = other.methodName;
            this.methodAfter = other.methodAfter;
            this.repo_id = other.repo_id;
            this.patch = other.patch;
            this.file_path = other.file_path;
            this.method_name = other.method_name;
            this.methodResult = other.methodResult;

            // 集合类型需要创建新集合（深拷贝）
            this.afterTargetNoNestingLines = other.afterTargetNoNestingLines != null ?
                    new ArrayList<>(other.afterTargetNoNestingLines) : null;
            this.exceptionTypes = other.exceptionTypes != null ?
                    new ArrayList<>(other.exceptionTypes) : null;
            this.beforeTargetNoNestingLines = other.beforeTargetNoNestingLines != null ?
                    new ArrayList<>(other.beforeTargetNoNestingLines) : null;
            this.catchBlocks = other.catchBlocks != null ?
                    new ArrayList<>(other.catchBlocks) : null;
        }

        public ExampleData(ExampleData other, IMethodBinding binding) {
            this.label = 0;
            this.afterTargetStartLine = 0;
            this.beforeTargetEndLine = 0;
            this.afterTargetEndLine = 0;
            this.beforeTargetStartLine = 0;
            this.methodBefore = "";
            this.methodName = Util.generateMethodName(binding);
            this.methodAfter = "";
            this.repo_id = other.repo_id;
            this.patch = other.patch;
            this.file_path = other.file_path;
            this.method_name = binding.getName();
            this.methodResult = "";
            this.afterTargetNoNestingLines = new ArrayList<>();
            this.exceptionTypes = new ArrayList<>();
            this.beforeTargetNoNestingLines = new ArrayList<>();
            this.catchBlocks = new ArrayList<>();
        }

        public int getAfterTargetStartLine() {
            return afterTargetStartLine;
        }

        public int getBeforeTargetEndLine() {
            return beforeTargetEndLine;
        }

        public List<Integer> getAfterTargetNoNestingLines() {
            return afterTargetNoNestingLines;
        }

        public String getMethodBefore() {
            return methodBefore;
        }

        public List<String> getExceptionTypes() {
            return exceptionTypes;
        }

        public List<Integer> getBeforeTargetNoNestingLines() {
            return beforeTargetNoNestingLines;
        }

        public int getAfterTargetEndLine() {
            return afterTargetEndLine;
        }

        public List<String> getCatchBlocks() {
            return catchBlocks;
        }

        public String getMethodName() {
            return methodName;
        }

        public String getMethodAfter() {
            return methodAfter;
        }

        public int getBeforeTargetStartLine() {
            return beforeTargetStartLine;
        }

        public String getRepo_id() {
            return repo_id;
        }

        public String getPatch() {
            return patch;
        }

        public String getFile_path() {
            return file_path;
        }

        public String getMethod_name() {
            return method_name;
        }

        public void setMethodBefore(String methodBefore) {
            this.methodBefore = methodBefore;
        }

        public void setMethodAfter(String methodAfter) {
            this.methodAfter = methodAfter;
        }

        public int getChanged() {
            return changed;
        }

        public int getLabel() {
            return label;
        }

        public void setChanged(int changed) {
            this.changed = changed;
            if (changed == 0) {
                resultAfterTargetNoNestingLines = new ArrayList<>();
                resultBeforeTargetNoNestingLines = new ArrayList<>();
                resultCatchBlocks = new ArrayList<>();
                resultExceptionTypes = new ArrayList<>();
                resultAfterTargetEndLine = 0;
                resultAfterTargetStartLine = 0;
                resultBeforeTargetEndLine = 0;
                resultBeforeTargetStartLine = 0;
            }
        }

        public int getResultAfterTargetStartLine() {
            return resultAfterTargetStartLine;
        }

        public void setResultAfterTargetStartLine(int resultAfterTargetStartLine) {
            this.resultAfterTargetStartLine = resultAfterTargetStartLine;
        }

        public int getResultAfterTargetEndLine() {
            return resultAfterTargetEndLine;
        }

        public void setResultAfterTargetEndLine(int resultAfterTargetEndLine) {
            this.resultAfterTargetEndLine = resultAfterTargetEndLine;
        }

        public int getResultBeforeTargetStartLine() {
            return resultBeforeTargetStartLine;
        }

        public void setResultBeforeTargetStartLine(int resultBeforeTargetStartLine) {
            this.resultBeforeTargetStartLine = resultBeforeTargetStartLine;
        }

        public int getResultBeforeTargetEndLine() {
            return resultBeforeTargetEndLine;
        }

        public void setResultBeforeTargetEndLine(int resultBeforeTargetEndLine) {
            this.resultBeforeTargetEndLine = resultBeforeTargetEndLine;
        }

        public List<Integer> getResultBeforeTargetNoNestingLines() {
            return resultBeforeTargetNoNestingLines;
        }

        public void setResultBeforeTargetNoNestingLines(List<Integer> resultBeforeTargetNoNestingLines) {
            this.resultBeforeTargetNoNestingLines = resultBeforeTargetNoNestingLines;
        }

        public List<Integer> getResultAfterTargetNoNestingLines() {
            return resultAfterTargetNoNestingLines;
        }

        public void setResultAfterTargetNoNestingLines(List<Integer> resultAfterTargetNoNestingLines) {
            this.resultAfterTargetNoNestingLines = resultAfterTargetNoNestingLines;
        }

        public List<String> getResultExceptionTypes() {
            return resultExceptionTypes;
        }

        public void setResultExceptionTypes(List<String> resultExceptionTypes) {
            this.resultExceptionTypes = resultExceptionTypes;
        }

        public List<String> getResultCatchBlocks() {
            return resultCatchBlocks;
        }

        public void setResultCatchBlocks(List<String> resultCatchBlocks) {
            this.resultCatchBlocks = resultCatchBlocks;
        }

        public void setMethodResult(String methodResult) {
            this.methodResult = methodResult;
        }

        public String getMethodResult() {
            String input = getRawMethodResult();
            // 检查起始标记
            boolean startsWithBlock = input.startsWith("```\n");
            boolean startsWithJavaBlock = input.startsWith("```java\n");

            // 检查结束标记
            boolean endsWithBlock = input.endsWith("\n```");

            String processed = input;

            // 如果同时有起始和结束标记，则移除它们
            if ((startsWithBlock || startsWithJavaBlock) && endsWithBlock) {
                if (startsWithJavaBlock) {
                    processed = processed.substring(7); // 移除 "```java\n"
                } else {
                    processed = processed.substring(4); // 移除 "```\n"
                }
                processed = processed.substring(0, processed.length() - 3); // 移除 "\n```"
            }

            // 确保以换行符结尾
            if (!processed.endsWith("\n")) {
                processed += "\n";
            }
            return processed;
        }

        public String getRawMethodResult() {
            return Objects.requireNonNullElse(methodResult, "");
        }

        public void setLabel(int label) {
            this.label = label;
        }

        public String getQualifiedName() {
            return new StringBuilder()
                    .append(repo_id.replaceAll("/", "_"))
                    .append("+")
                    .append(RepoHandler.getCommitHashFromPatch(patch), 0, 7)
                    .append("+")
                    .append(method_name)
                    .toString();
        }

        public String getDataPath() {
            return new StringBuilder()
                    .append(EXPERIMENT_DATA_PATH)
                    .append("\\")
                    .append(getRepo_id().replaceAll("/", "_"))
                    .append("\\")
                    .append(getQualifiedName())
                    .toString();
        }

        @Override
        public String toString() {
            return "ExampleData{" +
                    "afterTargetStartLine=" + afterTargetStartLine +
                    ", beforeTargetEndLine=" + beforeTargetEndLine +
                    ", afterTargetNoNestingLines=" + afterTargetNoNestingLines +
                    ", methodBefore='" + methodBefore + '\'' +
                    ", exceptionTypes=" + exceptionTypes +
                    ", beforeTargetNoNestingLines=" + beforeTargetNoNestingLines +
                    ", afterTargetEndLine=" + afterTargetEndLine +
                    ", catchBlocks=" + catchBlocks +
                    ", methodName='" + methodName + '\'' +
                    ", methodAfter='" + methodAfter + '\'' +
                    ", beforeTargetStartLine=" + beforeTargetStartLine +
                    ", repo_id='" + repo_id + '\'' +
                    ", patch='" + patch + '\'' +
                    ", file_path='" + file_path + '\'' +
                    ", method_name='" + method_name + '\'' +
                    '}';
        }
    }
}
