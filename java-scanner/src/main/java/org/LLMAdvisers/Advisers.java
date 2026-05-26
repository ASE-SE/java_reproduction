package org.LLMAdvisers;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.ExperimentExecutor.ExampleHandler;
import org.callTreeGenerator.*;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;

/**
 * 使用LLM处理exception处理相关判断任务的核心类
 * static部分包含完整功能实现，可被直接调用
 * 为了实验记录和实验中间状态留存，实验中使用实例化Advisers实现实验记录的存取
 */
public class Advisers {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    private static final int SYNTAX_RETRY = 2;
    private static String FILE_PATH = "src/main/java/org/LLMAdvisers/PromptTemplate.json";
    private static String API_TRIAGE_SYSTEM = "api triage system";
    private static String THROW_TRIAGE_SYSTEM = "throw triage system";
    private static String REPAIR_SYSTEM_PROMPT = "repair system output format description";
    private static String BACKGROUND = "background introduction";
    private static String QUESTION = "question";
    private static String INPUT_FORMAT = "input format description";
    private static String API_QUESTION = "api caught or not question";
    private static String API_FORMAT = "api input format description";
    private static String THROW_QUESTION = "throw catch or not question";
    private static String THROW_FORMAT = "throw input format description";
    private static String BASELINE_INPUT_FORMAT = "baseline input format description";
    private static Map<String, String> promptTemplates;
    // triage 打分阈值: 候选异常需 score >= 对应阈值 才进修复阶段。score 为单分,
    // 已综合考虑可达性 + 危害性。从 config.properties 读, 默认 0.3。
    // API 与 throw 分开 (语义不同); throw 未配置则回退用 API 的值。
    private static double API_THRESHOLD = 0.3;
    private static double THROW_THRESHOLD = 0.3;

    static {
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(FILE_PATH)) {
            // 定义Map的类型
            Type mapType = new TypeToken<Map<String, String>>() {}.getType();
            // 读取JSON文件并转换为Map<String, String>
            promptTemplates = gson.fromJson(reader, mapType);
        } catch (IOException e) {
            e.printStackTrace();
        }
        try (java.io.FileInputStream fis = new java.io.FileInputStream("config.properties")) {
            java.util.Properties prop = new java.util.Properties();
            prop.load(fis);
            API_THRESHOLD = Double.parseDouble(
                    prop.getProperty("TRIAGE_API_THRESHOLD", "0.3").trim());
            String tt = prop.getProperty("TRIAGE_THROW_THRESHOLD", "").trim();
            THROW_THRESHOLD = tt.isEmpty() ? API_THRESHOLD : Double.parseDouble(tt);
        } catch (IOException | NumberFormatException e) {
            LOGGER.warning("triage threshold load failed, using default 0.3: " + e.getMessage());
            API_THRESHOLD = 0.3;
            THROW_THRESHOLD = 0.3;
        }
    }

    public static String constructApiTriageSystemPrompt() {
        return promptTemplates.get(API_TRIAGE_SYSTEM);
    }

    public static String constructThrowTriageSystemPrompt() {
        return promptTemplates.get(THROW_TRIAGE_SYSTEM);
    }

    public static String constructRepairSystemPrompt() {
        return promptTemplates.get(REPAIR_SYSTEM_PROMPT);
    }

    public static Map<String, String> handleMainQuestion(MethodTreeNode root, List<UncaughtExceptionInfo> exceptionResults){
        LOGGER.info("handling main question");
        Map<String, String> result = new HashMap<>();
        // 允许解析失败重试
        int retryCount = 0;
        RuntimeException lastException = null;
        while (retryCount < SYNTAX_RETRY) {
            try {
                // 构建prompt
                LLMApiCaller LLMApiCaller1 = new LLMApiCaller()
                        .addMessage(Advisers.constructRepairSystemPrompt(), "system")
                        .addMessage(Advisers.constructQuestionPrompt(root, exceptionResults), "user");
                // 调用LLM
                try {
                    LLMApiCaller1.call();
                } catch (IOException e) {
                    // todo 抛出并处理调用失败
                    throw new RuntimeException(e);
                }
                // 解析结果
                result = LLMResponse.parseTaggedMessage(LLMApiCaller1.getLastAssistantMessage());
                validateMethodResult(result.get("result"));
                break;
            } catch (RuntimeException e) {
                lastException = e;
                retryCount++;
                // todo 抛出并处理解析失败
                if (retryCount < SYNTAX_RETRY) {
                    LOGGER.fine("main question parse failed: " + e.getMessage() + " retry (" + retryCount + "/" + SYNTAX_RETRY + ")...");
                } else {
                    // 不再重试，抛出异常 todo 抛出并处理调用失败
                    LOGGER.warning("main question abort");
                    throw lastException;
                }
            }
        }
        LOGGER.info("handle main question succeed");
        return result;
    }
    public static String constructQuestionPrompt(MethodTreeNode root, List<UncaughtExceptionInfo> exceptionResults) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptTemplates.get(BACKGROUND));
        sb.append(promptTemplates.get(QUESTION));
        sb.append(promptTemplates.get(INPUT_FORMAT));
        sb.append("\n");
        sb.append("<code snippet>\n");                                    // 中文：<代码片段>
        sb.append("```\n").append(root.getCode()).append("\n```\n");
        sb.append("<end>\n\n");
        int i = 1;
        for (UncaughtExceptionInfo uei: exceptionResults) {
            sb.append("<call ").append(i).append(">\n");                  // 中文：<函数调用 N>
            if (uei instanceof UncaughtExceptionInfoAPI) {
                // 中文：代码片段中的函数：<simpleName>
                sb.append("Function in snippet: ").append(uei.getNodeRoute().get(1).getSimpleName()).append("\n");
                // 中文：在经过 N 重调用后，最终在函数:<simpleName>中
                sb.append("After ").append(uei.getNodeRoute().size() - 1).append(" levels of call, in function: ").append(uei.getMethodTreeNode().getSimpleName()).append("\n");
                // 中文：可能抛出运行时异常:<exceptions> (附 triage likelihood 分数)
                sb.append("May throw runtime exceptions (with triage score 0-1, higher = more worth catching): ")
                  .append(uei.getCheckedUncaughtExceptionsScoredString()).append("\n");
                if (uei.hasDescription()) {
                    sb.append("Exception description: ").append(uei.getDescription()).append("\n");
                }
            } else if (uei instanceof UncaughtExceptionInfoThrow) {
                UncaughtExceptionInfoThrow ueiThrow = (UncaughtExceptionInfoThrow) uei;
                List<ThrowExceptionInfo> shown = ueiThrow.getCheckedUncaughtThrowExceptionInfo();
                if (shown == null) {
                    shown = ueiThrow.getUncaughtThrowExceptionInfo();
                }
                if (shown == null || shown.isEmpty()) {
                    sb.append("<end ").append(i).append(">\n");
                    i++;
                    continue;
                }
                if (uei.getNodeRoute().size() > 1) {
                    sb.append("Function in snippet: ").append(uei.getNodeRoute().get(1).getSimpleName()).append("\n");
                    sb.append("After ").append(uei.getNodeRoute().size() - 1)
                      .append(" levels of call, inside function `").append(uei.getMethodTreeNode().getSimpleName())
                      .append("`, the following throw statement(s) may propagate:\n");
                } else {
                    // route.size() == 1 → throw 在 target 方法自身（之前被静默丢弃，现在保留）
                    sb.append("Throw statement(s) inside this method itself may propagate to the caller:\n");
                }
                for (ThrowExceptionInfo t : shown) {
                    String stmt = t.getThrowStatementString();
                    if (stmt != null) stmt = stmt.replaceAll("\\s+", " ").trim();
                    sb.append("  - `").append(stmt).append("`");
                    Double s = ueiThrow.getThrowScore(t);
                    if (s != null) {
                        sb.append(" (triage score ").append(s).append(")");
                    }
                    sb.append("\n");
                }
                if (uei.hasDescription()) {
                    sb.append("Exception description: ").append(uei.getDescription()).append("\n");
                }
            }
            sb.append("<end ").append(i).append(">\n");                   // 中文：<end N>
            i++;
        }
        return sb.toString();
    }

    public static String handleUncaughtExceptionInfo(UncaughtExceptionInfo uncaughtExceptionInfo) {
        if (uncaughtExceptionInfo instanceof UncaughtExceptionInfoAPI) {
            return handleAPI((UncaughtExceptionInfoAPI) uncaughtExceptionInfo);
        } else if (uncaughtExceptionInfo instanceof UncaughtExceptionInfoThrow) {
            return handleThrow((UncaughtExceptionInfoThrow) uncaughtExceptionInfo);
        } else {
            LOGGER.severe("UncaughtExceptionInfo type error.");
            throw new IllegalStateException("UncaughtExceptionInfo type error");
        }
    }

    public static String handleAPI(UncaughtExceptionInfoAPI uncaughtExceptionInfo) {
        LOGGER.info("handling api by LLM: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
        // 提取UncaughtExceptionInfoAPI中信息构建prompt
        LLMApiCaller LLMApiCaller1;
        try {
            LLMApiCaller1 = new LLMApiCaller()
                    .addMessage(constructApiTriageSystemPrompt(), "system")
                    .addMessage(constructApiQuestionPrompt(uncaughtExceptionInfo), "user");
        } catch (RuntimeException ignore) {
            LOGGER.warning("handling api by LLM failed: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName() + " because : " + ignore.getMessage());
            return null;
        }
        // 请求LLM得到回复
        try {
            LLMApiCaller1.call();
        } catch (IOException ioException) {
            LOGGER.warning(ioException.getMessage() + ", abort handle api " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
            uncaughtExceptionInfo.setCheckedUncaughtExceptions(uncaughtExceptionInfo.getUncaughtExceptions());
            return null;
        }
        // 解析回复: 按 index 拿 likelihood, > 阈值 的候选保留 (全限定名原样, 不回匹配)
        try {
            String rawMessage = LLMApiCaller1.getLastAssistantMessage();
            String remark = LLMResponse.extractTag(rawMessage, "remark", false);
            if (!remark.isEmpty()) {
                uncaughtExceptionInfo.setDescription(remark);
            }
            selectApiByScore(uncaughtExceptionInfo, rawMessage);
        } catch (RuntimeException throwable) {
            LOGGER.warning(throwable.getMessage() + ", abort handle api " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
            uncaughtExceptionInfo.setCheckedUncaughtExceptions(uncaughtExceptionInfo.getUncaughtExceptions());
            return null;
        }
        LOGGER.info("api handle success: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
        return LLMApiCaller1.getLastAssistantMessage();
    }

    // 候选异常的稳定有序列表 (字典序), 索引 = 列表位置 + 1, prompt 与解析共用以保证 index 对齐。
    static List<String> orderedExceptions(UncaughtExceptionInfoAPI uei) {
        List<String> ordered = new ArrayList<>(uei.getUncaughtExceptions());
        Collections.sort(ordered);
        return ordered;
    }

    // 从打分回复里按 index 选出 likelihood > 阈值 的全限定名, 并把分数一并存到 uei
    // (供修复 prompt 显示)。解析不出任何分数 (极罕见) → 保守保留全部候选, 不丢 (无分数)。
    private static void selectApiByScore(UncaughtExceptionInfoAPI uei, String rawMessage) {
        List<String> ordered = orderedExceptions(uei);
        Map<Integer, Double> scores = LLMResponse.parseScores(rawMessage);
        if (scores.isEmpty()) {
            LOGGER.warning("api scores unparseable, keeping all candidates for "
                    + uei.getMethodTreeNode().getQualifiedName());
            uei.setCheckedUncaughtExceptions(new HashSet<>(ordered));
            uei.setCheckedExceptionScores(new HashMap<>());
            return;
        }
        Set<String> kept = new HashSet<>();
        Map<String, Double> keptScores = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            Double s = scores.get(i + 1); // 1-based index -> 综合 score
            if (s != null && s >= API_THRESHOLD) {
                kept.add(ordered.get(i));
                keptScores.put(ordered.get(i), s);
            }
        }
        uei.setCheckedUncaughtExceptions(kept);
        uei.setCheckedExceptionScores(keptScores);
    }

    public static String constructApiQuestionPrompt(UncaughtExceptionInfo uncaughtExceptionInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptTemplates.get(API_QUESTION).replaceAll("\\{str1\\}", Matcher.quoteReplacement(uncaughtExceptionInfo.getMethodTreeNode().getSimpleName())));
        sb.append(promptTemplates.get(API_FORMAT));
        sb.append("\n");
        sb.append("<code snippet>\n");                                    // 中文：<代码片段>
        sb.append("```\n").append(uncaughtExceptionInfo.getSecondLastNode().getCode()).append("\n```\n");
        sb.append("<end>\n\n");
        sb.append("<api javadoc>\n");                                     // 中文：<api方法文档>
        sb.append(((ArchivedMethodTreeNode) uncaughtExceptionInfo.getMethodTreeNode()).getJavaDoc());
        sb.append("<end>\n\n");
        sb.append("<candidate exceptions>\n");
        List<String> ordered = orderedExceptions((UncaughtExceptionInfoAPI) uncaughtExceptionInfo);
        for (int i = 0; i < ordered.size(); i++) {
            sb.append(i + 1).append(". ").append(ordered.get(i)).append("\n");
        }
        sb.append("<end>\n\n");
        return sb.toString();
    }

    public static String handleThrow(UncaughtExceptionInfoThrow uncaughtExceptionInfo) {
        LOGGER.info("handling throw by LLM: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
        // 一次调用给所有 throw 打分 (不再 per-throw 循环)
        LLMApiCaller caller = new LLMApiCaller()
                .addMessage(constructThrowTriageSystemPrompt(), "system")
                .addMessage(constructThrowQuestionPrompt(uncaughtExceptionInfo), "user");
        try {
            caller.call();
        } catch (IOException ioException) {
            LOGGER.warning(ioException.getMessage() + ", abort handle throw " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
            uncaughtExceptionInfo.setCheckedUncaughtThrowExceptionInfo(new ArrayList<>(uncaughtExceptionInfo.getUncaughtThrowExceptionInfo()));
            return null;
        }
        try {
            String rawMessage = caller.getLastAssistantMessage();
            String remark = LLMResponse.extractTag(rawMessage, "remark", false);
            if (!remark.isEmpty()) {
                uncaughtExceptionInfo.setDescription(remark);
            }
            uncaughtExceptionInfo.setCheckedUncaughtThrowExceptionInfo(
                    selectThrowByScore(uncaughtExceptionInfo, rawMessage));
            LOGGER.info("throw handle success: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
            return rawMessage;
        } catch (RuntimeException throwable) {
            LOGGER.warning(throwable.getMessage() + ", abort handle throw " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
            uncaughtExceptionInfo.setCheckedUncaughtThrowExceptionInfo(new ArrayList<>(uncaughtExceptionInfo.getUncaughtThrowExceptionInfo()));
            return null;
        }
    }

    // 按 index 选出 likelihood > 阈值 的 throw, 并把分数存到 uei (供修复 prompt 显示)。
    // 解析不出分数 → 保守保留全部 (无分数)。
    private static List<ThrowExceptionInfo> selectThrowByScore(UncaughtExceptionInfoThrow uei, String rawMessage) {
        List<ThrowExceptionInfo> all = uei.getUncaughtThrowExceptionInfo();
        Map<Integer, Double> scores = LLMResponse.parseScores(rawMessage);
        if (scores.isEmpty()) {
            LOGGER.warning("throw scores unparseable, keeping all throws for "
                    + uei.getMethodTreeNode().getQualifiedName());
            uei.setThrowScores(new java.util.LinkedHashMap<>());
            return new ArrayList<>(all);
        }
        List<ThrowExceptionInfo> kept = new ArrayList<>();
        java.util.LinkedHashMap<ThrowExceptionInfo, Double> keptScores = new java.util.LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            Double s = scores.get(i + 1); // 1-based -> 综合 score
            if (s != null && s >= THROW_THRESHOLD) {
                kept.add(all.get(i));
                keptScores.put(all.get(i), s);
            }
        }
        uei.setThrowScores(keptScores);
        return kept;
    }

    public static String constructThrowQuestionPrompt(UncaughtExceptionInfoThrow uncaughtExceptionInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptTemplates.get(THROW_QUESTION));
        sb.append(promptTemplates.get(THROW_FORMAT));
        sb.append("\n");
        sb.append("<code snippet>\n");                                    // 中文：<代码片段>
        sb.append("```\n").append(uncaughtExceptionInfo.getMethodTreeNode().getCode()).append("\n```\n");
        sb.append("<end>\n\n");
        sb.append("<candidate throws>\n");
        List<ThrowExceptionInfo> throwsList = uncaughtExceptionInfo.getUncaughtThrowExceptionInfo();
        for (int i = 0; i < throwsList.size(); i++) {
            String stmt = throwsList.get(i).getThrowStatementString();
            if (stmt != null) stmt = stmt.replaceAll("\\s+", " ").trim();
            sb.append(i + 1).append(". ").append(stmt).append("\n");
        }
        sb.append("<end>\n\n");
        return sb.toString();
    }

    private ExampleHandler.ExampleData exampleData;

    public Advisers(ExampleHandler.ExampleData exampleData) {
        this.exampleData = exampleData;
    }

    public Map<String, String> handleMainQuestion(MethodTreeNode root, List<UncaughtExceptionInfo> exceptionResults, String experimentMark) {
        // 把候选集签名拼进 cache key：候选集变了就不再吃旧答案。
        // 直接对最终 prompt 取 md5（包含 root 代码 + 全部 <call> 块），等价于"只要喂给 LLM 的输入变了，就重算"。
        String prompt = constructQuestionPrompt(root, exceptionResults);
        String expectedName = experimentMark + "+" + root.getSimpleName() + "+" + ExampleHandler.md5Hash(prompt);
        String existData = ExampleHandler.getDataRecord(exampleData, expectedName);
        if (existData == null) {
            // 若无已记录数据，则按常规流程调取LLM并做记录
            LOGGER.info("handling main question by LLM: " + root.getSimpleName());
            Map<String, String> result;
            try {
                result = handleMainQuestion(root, exceptionResults);
            } catch (RuntimeException e) {
                LOGGER.warning("handling main question by LLM failed: " + root.getSimpleName());
                return null;
            }
            String dataToRecord = serializeTaggedMessage(result);
            if (ExampleHandler.writeDataRecord(exampleData, dataToRecord, expectedName)) {
                LOGGER.info("experiment data wrote to: " + expectedName);
            } else {
                LOGGER.warning("experiment data failed to wrote to: " + expectedName);
            }
            LOGGER.info("main question handle success: " + root.getSimpleName());
            return result;
        } else {
            // 若存在对应记录，则只执行常规流程后半解析部分，直接从记录数据开始，代替LLM
            LOGGER.info("handling main question by recorded data: " + root.getSimpleName());
            Map<String, String> result = LLMResponse.parseTaggedMessage(existData);
            LOGGER.info("main question handle success: " + root.getSimpleName());
            return result;
        }
    }

    private static String serializeTaggedMessage(Map<String, String> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("<analysis>\n").append(data.getOrDefault("analysis", "")).append("\n</analysis>\n");
        sb.append("<answer>\n").append(data.getOrDefault("answer", "")).append("\n</answer>\n");
        sb.append("<result>\n").append(data.getOrDefault("result", "")).append("\n</result>\n");
        sb.append("<remark>\n").append(data.getOrDefault("remark", "")).append("\n</remark>\n");
        return sb.toString();
    }

    /**
     * handleUncaughtExceptionInfo(UncaughtExceptionInfo)的重载，除去添加了实验中间过程数据读写外，与原方法表现一致
     * 若有必要需从原方法同步代码
     * @param uncaughtExceptionInfo 待处理异常抛出
     * @param experimentMark 实验标签
     * @return 由LLM或记录中得到的回复
     */
    public String handleUncaughtExceptionInfo(UncaughtExceptionInfo uncaughtExceptionInfo, String experimentMark) {
        String expectedName = experimentMark + "+" + uncaughtExceptionInfo.getHash();
        String existData = ExampleHandler.getDataRecord(exampleData, expectedName);
        if (existData == null) {
            // 若无已记录数据，则按常规流程调取LLM并作记录
            String dataToRecord = handleUncaughtExceptionInfo(uncaughtExceptionInfo);
            if (dataToRecord != null) {
                if (ExampleHandler.writeDataRecord(exampleData, dataToRecord, expectedName)) {
                    LOGGER.info("experiment data wrote to: " + expectedName);
                } else {
                    LOGGER.warning("experiment data failed to wrote to: " + expectedName);
                }
            } else {
                LOGGER.warning("experiment data failed to wrote to: " + expectedName);
            }
            return dataToRecord;
        } else {
            // 若存在对应记录，则只执行常规流程后半解析部分，直接从记录数据开始，代替LLM
            if (uncaughtExceptionInfo instanceof UncaughtExceptionInfoAPI) {
                LOGGER.info("handling api by recorded data: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
                // 从缓存的打分回复按当前阈值重新过滤 (改阈值零 LLM 成本)
                try {
                    String remark = LLMResponse.extractTag(existData, "remark", false);
                    if (!remark.isEmpty()) {
                        uncaughtExceptionInfo.setDescription(remark);
                    }
                    selectApiByScore((UncaughtExceptionInfoAPI) uncaughtExceptionInfo, existData);
                } catch (RuntimeException throwable) {
                    LOGGER.warning(throwable.getMessage() + ", abort handle api " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
                    uncaughtExceptionInfo.setCheckedUncaughtExceptions(uncaughtExceptionInfo.getUncaughtExceptions());
                    return null;
                }
                LOGGER.info("api handle success: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
            } else if (uncaughtExceptionInfo instanceof UncaughtExceptionInfoThrow) {
                LOGGER.info("handling throw by recorded data: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
                UncaughtExceptionInfoThrow throwInfo = (UncaughtExceptionInfoThrow) uncaughtExceptionInfo;
                try {
                    String remark = LLMResponse.extractTag(existData, "remark", false);
                    if (!remark.isEmpty()) {
                        throwInfo.setDescription(remark);
                    }
                    // 从缓存的打分回复按当前阈值重新过滤 (改阈值零 LLM 成本)
                    throwInfo.setCheckedUncaughtThrowExceptionInfo(selectThrowByScore(throwInfo, existData));
                } catch (RuntimeException throwable) {
                    LOGGER.warning(throwable.getMessage() + ", abort handle throw " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
                    throwInfo.setCheckedUncaughtThrowExceptionInfo(new ArrayList<>(throwInfo.getUncaughtThrowExceptionInfo()));
                    return null;
                }
                LOGGER.info("throw handle success: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
            }
            return existData;
        }
    }

    public static Map<String, String> handleLLMBaseline(String methodText){
        LOGGER.info("handling llm baseline question");
        Map<String, String> result = new HashMap<>();
        // 允许解析失败重试
        int retryCount = 0;
        RuntimeException lastException = null;
        while (retryCount < SYNTAX_RETRY) {
            try {
                // 构建prompt
                LLMApiCaller LLMApiCaller1 = new LLMApiCaller()
                        .addMessage(Advisers.constructRepairSystemPrompt(), "system")
                        .addMessage(Advisers.constructBaselinePrompt(methodText), "user");
                // 调用LLM
                try {
                    LLMApiCaller1.call();
                } catch (IOException e) {
                    // todo 抛出并处理调用失败
                    throw new RuntimeException(e);
                }
                // 解析结果
                result = LLMResponse.parseTaggedMessage(LLMApiCaller1.getLastAssistantMessage());
                validateMethodResult(result.get("result"));
                break;
            } catch (RuntimeException e) {
                lastException = e;
                retryCount++;
                // todo 抛出并处理解析失败
                if (retryCount < SYNTAX_RETRY) {
                    LOGGER.fine("llm baseline question parse failed: " + e.getMessage() + " retry (" + retryCount + "/" + SYNTAX_RETRY + ")...");
                } else {
                    // 不再重试，抛出异常 todo 抛出并处理调用失败
                    LOGGER.warning("llm baseline question abort");
                    throw lastException;
                }
            }
        }
        LOGGER.info("handle llm baseline question succeed");
        return result;
    }

    public static String constructBaselinePrompt(String methodText) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptTemplates.get(BACKGROUND));
        sb.append(promptTemplates.get(QUESTION));
        sb.append(promptTemplates.get(BASELINE_INPUT_FORMAT));
        sb.append("\n");
        sb.append("<code snippet>\n");                                    // 中文：<代码片段>
        sb.append("```\n").append(methodText).append("\n```\n");
        sb.append("<end>\n\n");
        return sb.toString();
    }

    private static void validateMethodResult(String methodResult) {
        String code = LLMResponse.stripCode(methodResult).trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("empty <result> method code");
        }
        // 廉价的大括号平衡 pre-check：明显问题不必走 JDT。
        int balance = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                balance++;
            } else if (c == '}') {
                balance--;
            }
            if (balance < 0) {
                throw new IllegalArgumentException("unbalanced braces in <result> method code");
            }
        }
        if (balance != 0) {
            throw new IllegalArgumentException("unbalanced braces in <result> method code");
        }
        // JDT 解析为类体声明，要求至少有一个 MethodDeclaration，且没有语法错误。
        // 失败会被 handleMainQuestion / handleLLMBaseline 当作 RuntimeException 触发 SYNTAX_RETRY。
        try {
            ASTParser parser = ASTParser.newParser(AST.JLS21);
            parser.setKind(ASTParser.K_CLASS_BODY_DECLARATIONS);
            parser.setStatementsRecovery(false);
            parser.setSource(code.toCharArray());
            ASTNode root = parser.createAST(null);
            if (!(root instanceof TypeDeclaration)) {
                throw new IllegalArgumentException("<result> not parseable as a class body declaration");
            }
            TypeDeclaration td = (TypeDeclaration) root;
            boolean hasMethod = false;
            for (Object decl : td.bodyDeclarations()) {
                if (decl instanceof MethodDeclaration) {
                    hasMethod = true;
                    break;
                }
            }
            if (!hasMethod) {
                throw new IllegalArgumentException("<result> contains no method declaration");
            }
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (RuntimeException re) {
            throw new IllegalArgumentException("<result> JDT parse failure: " + re.getMessage());
        }
    }

}
