package org.ExperimentExecutor;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.ASTAnalyzer.MethodDiffAnalyzer;
import org.ASTAnalyzer.TryPatternData;
import org.LLMAdvisers.Advisers;
import org.LLMAdvisers.LLMApiCaller;
import org.LLMAdvisers.LLMResponse;
import org.callTreeGenerator.*;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.jdkAnalyzer.ProjectParser;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.*;
import java.util.stream.Collectors;

public class Main {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    private static String EXPERIMENT_MARK;
    // 当 triage 把所有静态候选全部否决时：
    //   false（默认，方案 C）：跳过主问题，直接把 methodBefore 作为 result 返回 → changed=0
    //   true（方案 A）：回退到原始全集，仍调一次主问题（保留原始 fallback 行为，便于 A/B 对比）
    private static boolean TRIAGE_EMPTY_FALLBACK;
    // triage 前的候选数上限：静态候选超过此数时，按调用深度从深到浅修剪到该上限。
    // 0 = 无上限（全留）。默认 100。
    private static int STATIC_CANDIDATE_CAP = 100;
    private static Gson gson = new Gson();
    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            prop.load(fis);
            EXPERIMENT_MARK = prop.getProperty("EXPERIMENT_MARK");
            TRIAGE_EMPTY_FALLBACK = Boolean.parseBoolean(
                    prop.getProperty("TRIAGE_EMPTY_FALLBACK", "false").trim());
            try {
                STATIC_CANDIDATE_CAP = Integer.parseInt(
                        prop.getProperty("STATIC_CANDIDATE_CAP", "100").trim());
            } catch (NumberFormatException nfe) {
                STATIC_CANDIDATE_CAP = 100;
            }
        } catch (IOException e) {
            LOGGER.severe("config loading failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    public static void main(String[] args) throws IOException {
        loggerInit();
        LOGGER.info("experiment process start.");
        if (!ExampleHandler.REPO_FILTER.isEmpty()) {
            LOGGER.info("REPO_FILTER active, only processing: " + ExampleHandler.REPO_FILTER);
        }
        if (ExampleHandler.SAMPLE_LIMIT > 0) {
            LOGGER.info("SAMPLE_LIMIT=" + ExampleHandler.SAMPLE_LIMIT);
        }
        ExampleHandler.ExampleData processingData = ExampleHandler.getNextData(false);
        while (processingData != null) {
            List<ExampleHandler.ExampleData> results = null;
            boolean analysisCrashed = false;
            try {
                results = processSingleExample(processingData);
            } catch (Throwable t) {
                analysisCrashed = true;
                LOGGER.log(Level.SEVERE,
                        "analysis crashed on sample " + processingData.getMethodName()
                                + " in " + processingData.getRepo_id() + "; marking label=-1 and continuing",
                        t);
            }
            if (results == null || results.isEmpty()) {
                // -1 = analysis-side failure (JDT / LLM / our bug); -2 = repo unavailable
                // (clone or checkout failed, typically network). Rerun -2 entries after
                // fixing connectivity; -1 entries are method-level issues.
                int label = analysisCrashed ? -1 : -2;
                LOGGER.warning("no usable result for " + processingData.getMethodName()
                        + " in " + processingData.getRepo_id() + "; marking label=" + label);
                ExampleHandler.ExampleData failed = new ExampleHandler.ExampleData(processingData);
                failed.setLabel(label);
                results = List.of(failed);
            }
            ExampleHandler.addResults(results);
            ExampleHandler.writeResultData();
            processingData = ExampleHandler.getNextData(false);
        }
        LOGGER.info("finished.");
    }

    private static void loggerInit() throws IOException{
        // 设置日志级别为 ALL，允许输出更详细的日志
        LOGGER.setLevel(Level.ALL);
        // 创建文件处理器，第二个参数 true 表示追加模式
        Handler fileHandler = new FileHandler("ExperimentExecutor_Main_" + EXPERIMENT_MARK + ".log", true);
        fileHandler.setLevel(Level.ALL); // 设置 Handler 级别
        // 设置日志格式
        fileHandler.setFormatter(new SimpleFormatter());
        // 将 Handler 添加到 Logger
        LOGGER.addHandler(fileHandler);
        LOGGER.setLevel(Level.ALL); // 设置 Logger 级别
    }

    /**
     * 输入一个实例返回1个带标签的实验结果
     * @param data 一条实验数据
     * @return 期望为1条实验数据
     */
    private static List<ExampleHandler.ExampleData> processSingleExample(ExampleHandler.ExampleData data) throws IOException {
        String repoID = data.getRepo_id();
        String commitHash = RepoHandler.getCommitHashFromPatch(data.getPatch());
        String method_name = data.getMethod_name();
        // 生成待分析项目
        LOGGER.info("start project generation on example: " + data.getMethodName());
//        String name = repoID.replaceAll("/", "_") + commitHash.substring(0, 7) + method_name;
        List<String> generatedFiles = ProjectHandler.generateCommitParentHistoryFiles(
                RepoHandler.getRepository(repoID),
                commitHash);
        if (generatedFiles == null) {
            LOGGER.warning("data with repo_id: " + repoID +" and commitHash: " + commitHash + "and name: " + data.getMethodName() + " process failed");
            return null;
        }

        // 解析源代码
        LOGGER.info("start parse on example: " + data.getMethodName());
        ProjectParser projectParser = new ProjectParser(ProjectHandler.TMP_PROJECT_PATH);
        ExceptionCharacteristicManager exceptionCharacteristicManager = new ExceptionCharacteristicManager();

        // 获取实例
        List<TreeGenerator> treeGenerators = new ArrayList<>(); // 用例对应TreeGenerator
        List<IMethodBinding> targetMethods = new ArrayList<>(); // 用例对应method
        treeGenerators.add(new TreeGenerator(projectParser, exceptionCharacteristicManager));
        targetMethods.add(treeGenerators.get(0).getMethodBindingByFileAndName(data.getFile_path(), data.getMethodName()));
        List<ExampleHandler.ExampleData> exampleData = new ArrayList<>(List.of(new ExampleHandler.ExampleData(data))); // 实验数据，附标签;

        // 进行实验
        LOGGER.info("start experiment on example: " + data.getMethodName());
        List<ExampleHandler.ExampleData> results = new ArrayList<>();
        for (int i = 0; i < targetMethods.size(); i++) {
            if (treeGenerators.get(i) == null || targetMethods.get(i) == null || exampleData.get(i) == null) {
                ExampleHandler.ExampleData data1 = new ExampleHandler.ExampleData(exampleData.get(i));
                LOGGER.warning("experiment failed on example: " + data.getMethodName());
                data1.setLabel(-1);
                results.add(data1);
                continue;
            }
            ExampleHandler.ExampleData result = singleExperiment(treeGenerators.get(i), targetMethods.get(i), exampleData.get(i), exceptionCharacteristicManager);
            results.add(result);
        }
        List<ExampleHandler.ExampleData> processedResults = new ArrayList<>();
        for (ExampleHandler.ExampleData result: results) {
            if (result == null) continue;
            processedResults.add(analyzeExperimentDiff(result));
        }
        LOGGER.info("finish example: " + data.getMethodName());
        return processedResults;
    }

    /**
     * 输入一个实例，进行1个正例的实验，随机采2个负例进行试验，返回三个带标签的实验结果
     * @param data 一条实验数据
     * @return 期望为三条实验数据，若无法采得足够负例则减少负例
     */
    private static List<ExampleHandler.ExampleData> processExample(ExampleHandler.ExampleData data) {
        String repoID = data.getRepo_id();
        String commitHash = RepoHandler.getCommitHashFromPatch(data.getPatch());
        // 生成待分析项目
        LOGGER.info("start project generation on example: " + data.getMethodName());
        List<String> generatedFiles = ProjectHandler.generateCommitParentHistoryFiles(
                RepoHandler.getRepository(repoID),
                commitHash);
        if (generatedFiles == null) {
            LOGGER.warning("data with repo_id: " + repoID +" and commitHash: " + commitHash + "and name: " + data.getMethodName() + " process failed");
            return null;
        }

        // 解析源代码
        LOGGER.info("start parse on example: " + data.getMethodName());
        ProjectParser projectParser = new ProjectParser(ProjectHandler.TMP_PROJECT_PATH);
        ExceptionCharacteristicManager exceptionCharacteristicManager = new ExceptionCharacteristicManager();

        // 获取正负例
        List<TreeGenerator> treeGenerators = new ArrayList<>(); // 用例对应TreeGenerator
        List<IMethodBinding> targetMethods = new ArrayList<>(); // 用例对应method，首个（0）为正例，剩余为负例
        for (int i = 0; i < 3; i++) {
            treeGenerators.add(new TreeGenerator(projectParser, exceptionCharacteristicManager));
        }
        targetMethods.add(treeGenerators.get(0).getMethodBindingByFileAndName(data.getFile_path(), data.getMethodName()));
        List<IMethodBinding> negativeExamples = treeGenerators.get(0).getNegativeMethods();
        List<ExampleHandler.ExampleData> exampleData = new ArrayList<>(List.of(new ExampleHandler.ExampleData(data))); // 实验数据，附标签;
        exampleData.get(0).setLabel(1);
        if (!(negativeExamples == null || negativeExamples.isEmpty())) {
            Collections.shuffle(negativeExamples);
            if (!negativeExamples.isEmpty()) {
                targetMethods.add(negativeExamples.get(0));
                exampleData.add(new ExampleHandler.ExampleData(data, targetMethods.get(1)));
                if (negativeExamples.size() > 1) {
                    targetMethods.add(negativeExamples.get(1));
                    exampleData.add(new ExampleHandler.ExampleData(data, targetMethods.get(2)));
                }
            }
        }

        // 进行实验
        LOGGER.info("start experiment on example: " + data.getMethodName());
        List<ExampleHandler.ExampleData> results = new ArrayList<>();
        for (int i = 0; i < targetMethods.size(); i++) {
            if (treeGenerators.get(i) == null || targetMethods.get(i) == null || exampleData.get(i) == null) {
                ExampleHandler.ExampleData data1 = new ExampleHandler.ExampleData(exampleData.get(i));
                data1.setLabel(-1);
                results.add(data1);
                continue;
            }
            ExampleHandler.ExampleData result = singleExperiment(treeGenerators.get(i), targetMethods.get(i), exampleData.get(i), exceptionCharacteristicManager);
            results.add(result);
        }
        List<ExampleHandler.ExampleData> processedResults = new ArrayList<>();
        for (ExampleHandler.ExampleData result: results) {
            if (result == null) continue;
            processedResults.add(analyzeExperimentDiff(result));
        }
        LOGGER.info("finish example: " + data.getMethodName());
        return processedResults;
    }
    private static ExampleHandler.ExampleData singleExperiment(TreeGenerator treeGenerator, IMethodBinding targetMethod, ExampleHandler.ExampleData data, ExceptionCharacteristicManager exceptionCharacteristicManager) {
        // 生成调用树
        LOGGER.info("start analyze： " + targetMethod.getName());
        treeGenerator.methodBinding2graph(targetMethod);
        DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> graph = treeGenerator.getGraph();
        MethodTreeNode root = treeGenerator.getRoot();
        if (data.getLabel() == 0) {
            data.setMethodBefore(root.getCode());
            data.setMethodAfter(root.getCode());
        }

        // 根据异常抛出捕获遍历树得到未捕获异常
        LOGGER.info("start find exception: " + targetMethod.getName());
        GraphNodeTraversal graphNodeTraversal = new GraphNodeTraversal(exceptionCharacteristicManager);
        List<UncaughtExceptionInfo> exceptionResults = graphNodeTraversal.getSuspiciousThrows(graph, root);
        Integer layer = 10;
        while(STATIC_CANDIDATE_CAP > 0 && exceptionResults.size() > STATIC_CANDIDATE_CAP && layer > 3) {
            Integer finalLayer = layer;
            exceptionResults.removeIf(info -> info.getNodeRoute().size() - 1 > finalLayer);
            layer --;
        }
        LOGGER.fine("root method:\n" + root.getCode());
        StringBuilder sb = new StringBuilder();
        sb.append("found uncaught exceptions:\n");
        for (UncaughtExceptionInfo exceptionResult : exceptionResults) {
            sb.append(exceptionResult);
        }
        LOGGER.fine(sb.toString());

        // 使用大语言模型处理抛出端
        Advisers advisers = new Advisers(data);
        LOGGER.info("start LLM call on method judgment");
        List<UncaughtExceptionInfo> checkedExceptionResults = exceptionResults.parallelStream()
                .peek(uncaughtExceptionInfo -> {
                    advisers.handleUncaughtExceptionInfo(uncaughtExceptionInfo, EXPERIMENT_MARK);
                })
                .filter(UncaughtExceptionInfo::isContainUncaughtExceptions)
                .collect(Collectors.toList());
        if (checkedExceptionResults.isEmpty()) {
            if (TRIAGE_EMPTY_FALLBACK) {
                // 方案 A：回退全集，仍走主问题（保留旧行为，便于对比）
                LOGGER.info("triage left no candidates; TRIAGE_EMPTY_FALLBACK=true, falling back to full set: " + targetMethod.getName());
                checkedExceptionResults = exceptionResults;
            } else {
                // 方案 C：triage 全否决 → 跳过主问题，结果 = 原方法
                // 静态分析 + LLM 语义判断都不认为需要 catch，无需再问主模型
                LOGGER.info("triage left no candidates; skipping main question (TRIAGE_EMPTY_FALLBACK=false): " + targetMethod.getName());
                data.setMethodResult(root.getCode());
                LOGGER.info("finish analyze： " + targetMethod.getName());
                return data;
            }
        }

        // 使用大语言模型处理捕获端
        LOGGER.info("start LLM call on main question");
        Map<String, String> mainQuestionResult = advisers.handleMainQuestion(root, checkedExceptionResults, EXPERIMENT_MARK);
        if (mainQuestionResult == null) {
            data.setLabel(0);
        } else {
            data.setMethodResult(LLMResponse.stripCode(mainQuestionResult.get("result")));
        }

        LOGGER.info("finish analyze： " + targetMethod.getName());
        return data;
    }
    public static ExampleHandler.ExampleData analyzeExperimentDiff(ExampleHandler.ExampleData data) {
        // 使用ASTA分析数据
        if (Objects.equals(data.getMethodBefore(), data.getMethodResult())) {
            data.setChanged(0);
        } else {
            List<TryPatternData> result;
            try {
                result = MethodDiffAnalyzer.analyze(data.getMethodBefore(), data.getMethodResult());
            } catch (NullPointerException ignore) {
                result = null;
            }
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
        return data;
    }

    private static void mainContent() {
        // todo 以仓库为单位的实例选取，需要筛选runtime exception 2. 异常类型筛选 + 负例选择
        Map<String, String> repo = new HashMap<>();
        repo.put("repo_id", "apache/beam");
        repo.put("patch", "<Patch - https://github.com/apache/beam/commit/857eccedc5544ed920e9d445d18b1de74843488d>");
        repo.put("file_path", "sdks/java/core/src/main/java/org/apache/beam/sdk/metrics/Lineage.java");
        repo.put("methodName", "query(MetricResults,Type)");

        // 生成待分析项目
        LOGGER.info("start project generation");
        ProjectHandler.generateCommitParentHistoryFiles(
                RepoHandler.getRepository(repo.get("repo_id")),
                RepoHandler.getCommitHashFromPatch(repo.get("patch")));

        // 解析源代码
        LOGGER.info("start parse");
        ProjectParser projectParser = new ProjectParser(ProjectHandler.TMP_PROJECT_PATH);
        ExceptionCharacteristicManager exceptionCharacteristicManager = new ExceptionCharacteristicManager();
        TreeGenerator treeGenerator = new TreeGenerator(projectParser, exceptionCharacteristicManager);
//        IMethodBinding targetMethod = treeGenerator.getMethodBindingByQualifiedName(methodQualifiedName);
        IMethodBinding targetMethod = treeGenerator.getMethodBindingByFileAndName(repo.get("file_path"), repo.get("methodName"));

        // 生成调用树
        LOGGER.info("start analyze");
        treeGenerator.methodBinding2graph(targetMethod);
        DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> graph = treeGenerator.getGraph();
        MethodTreeNode root = treeGenerator.getRoot();

        // 根据异常抛出捕获遍历树得到未捕获异常
        LOGGER.info("start find exception");
        GraphNodeTraversal graphNodeTraversal = new GraphNodeTraversal(exceptionCharacteristicManager);
        List<UncaughtExceptionInfo> exceptionResults = graphNodeTraversal.getSuspiciousThrows(graph, root);
        LOGGER.fine("root method:\n" + root.getCode());
        StringBuilder sb = new StringBuilder();
        sb.append("found uncaught exceptions:\n");
        for (UncaughtExceptionInfo exceptionResult : exceptionResults) {
            sb.append(exceptionResult);
        }
        LOGGER.fine(sb.toString());

        // 使用大语言模型处理抛出端
        LOGGER.info("start LLM call on method judgment");
        List<UncaughtExceptionInfo> checkedExceptionResults = exceptionResults.parallelStream()
                .peek(uncaughtExceptionInfo -> {
                    Advisers.handleUncaughtExceptionInfo(uncaughtExceptionInfo);
                })
                .filter(UncaughtExceptionInfo::isContainUncaughtExceptions)
                .collect(Collectors.toList());
        if (checkedExceptionResults.isEmpty()) {
            checkedExceptionResults = exceptionResults;
        }

        // 使用大语言模型处理捕获端
        LOGGER.info("start LLM call on main question");
        Map<String, String> mainQuestionResult = Advisers.handleMainQuestion(root, checkedExceptionResults);
        System.out.println(mainQuestionResult);
    }

    private static void test() {
        String result = RepoHandler.getRepository("apache/beam");
        System.out.println(result);
    }
}
