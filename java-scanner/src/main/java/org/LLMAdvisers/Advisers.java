package org.LLMAdvisers;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.ExperimentExecutor.ExampleHandler;
import org.callTreeGenerator.*;

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
    private static String SYSTEM_PROMPT = "system output format description";
    private static String BACKGROUND = "background introduction";
    private static String QUESTION = "question";
    private static String INPUT_FORMAT = "input format description";
    private static String API_QUESTION = "api caught or not question";
    private static String API_FORMAT = "api input format description";
    private static String THROW_QUESTION = "throw catch or not question";
    private static String THROW_FORMAT = "throw input format description";
    private static String BASELINE_INPUT_FORMAT = "baseline input format description";
    private static Map<String, String> promptTemplates;

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
    }

    public static String constructSystemPrompt() {
        return promptTemplates.get(SYSTEM_PROMPT);
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
                        .addMessage(Advisers.constructSystemPrompt(), "system")
                        .addMessage(Advisers.constructQuestionPrompt(root, exceptionResults), "user");
                // 调用LLM
                try {
                    LLMApiCaller1.call();
                } catch (IOException e) {
                    // todo 抛出并处理调用失败
                    throw new RuntimeException(e);
                }
                // 解析结果
                Map<String, Object> jsonResult = LLMApiCaller1.getDeepSeekResponse().getJsonMessage();
                for (String key: jsonResult.keySet()) {
                    result.put(key, (String) jsonResult.get(key));
                }
                break;
            } catch (JsonSyntaxException | ClassCastException e) {
                lastException = e;
                retryCount++;
                // todo 抛出并处理解析失败
                if (retryCount < SYNTAX_RETRY) {
                    LOGGER.fine("main question parse failed: " + e.getMessage() + " retry (" + retryCount + "/" + SYNTAX_RETRY + ")...");
                } else {
                    // 不再重试，抛出异常 todo 抛出并处理调用失败
                    LOGGER.warning("main question abort");
                    throw new RuntimeException(lastException);
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
                // 中文：可能抛出运行时异常:<exceptions>
                sb.append("May throw runtime exceptions: ").append(uei.getCheckedUncaughtExceptionsString()).append("\n");
            } else if (uei instanceof UncaughtExceptionInfoThrow && uei.getNodeRoute().size() > 1) {
                // 中文：代码片段中的函数：<simpleName>
                sb.append("Function in snippet: ").append(uei.getNodeRoute().get(1).getSimpleName()).append("\n");
                // 中文：在经过 N 重调用后，使用throw语句`<stmt>`抛出异常
                sb.append("After ").append(uei.getNodeRoute().size() - 1).append(" levels of call, the throw statement `").append(((UncaughtExceptionInfoThrow) uei).getCheckedUncaughtThrowExceptionInfo()).append("` throws an exception\n");
            }
            if (uei.hasDescription()) {
                // 中文：异常说明：<desc>
                sb.append("Exception description: ").append(uei.getDescription()).append("\n");
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
                    .addMessage(constructSystemPrompt(), "system")
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
        // 解析回复
        Set<String> checkedUncaughtExceptions = new HashSet<>();
        try {
            Map<String, Object> jsonResult = LLMApiCaller1.getDeepSeekResponse().getJsonMessage();
            assert jsonResult.get("remark") instanceof String;
            if (!jsonResult.get("remark").equals("")) {
                uncaughtExceptionInfo.setDescription((String) jsonResult.get("remark"));
            }
            assert jsonResult.get("result") instanceof ArrayList;
            List<String> checkedUncaughtExceptionsSimpleNames = (ArrayList<String>) jsonResult.get("result");
            for (String simpleName: checkedUncaughtExceptionsSimpleNames) {
                for (String qualifiedName: uncaughtExceptionInfo.getUncaughtExceptions()) {
                    if (qualifiedName.endsWith(simpleName)) {
                        checkedUncaughtExceptions.add(qualifiedName);
                    }
                }
            }
        } catch (JsonSyntaxException | ClassCastException | AssertionError throwable) {
            LOGGER.warning(throwable.getMessage() + ", abort handle api " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
            uncaughtExceptionInfo.setCheckedUncaughtExceptions(uncaughtExceptionInfo.getUncaughtExceptions());
            return null;
        }
        // 更新UncaughtExceptionInfoAPI
        uncaughtExceptionInfo.setCheckedUncaughtExceptions(checkedUncaughtExceptions);
        LOGGER.info("api handle success: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
        return LLMResponse.getJsonString(LLMApiCaller1.getLastAssistantMessage());
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
        return sb.toString();
    }

    public static String handleThrow(UncaughtExceptionInfoThrow uncaughtExceptionInfo) {
        LOGGER.info("handling throw by LLM: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
        StringBuilder sb = new StringBuilder("[\n");
        // 更新UncaughtExceptionInfoThrow
        uncaughtExceptionInfo.setCheckedUncaughtThrowExceptionInfo(uncaughtExceptionInfo.getUncaughtThrowExceptionInfo());
        // 对每个throw分别提问
        for (int i = 0; i < uncaughtExceptionInfo.getUncaughtThrowExceptionInfo().size(); i++) {
            // 提取UncaughtExceptionInfoThrow中信息构建prompt
            LLMApiCaller LLMApiCaller1 = new LLMApiCaller()
                    .addMessage(constructSystemPrompt(), "system")
                    .addMessage(constructThrowQuestionPrompt(uncaughtExceptionInfo, i), "user");
            // 请求LLM得到回复
            try {
                LLMApiCaller1.call();
            } catch (IOException ioException) {
                LOGGER.warning(ioException.getMessage() + ", abort handle throw " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
                return null;
            }
            // 解析回复
            try {
                Map<String, Object> jsonResult = LLMApiCaller1.getDeepSeekResponse().getJsonMessage();
                assert jsonResult.get("remark") instanceof String;
                uncaughtExceptionInfo.setDescription((String) jsonResult.get("remark"));
                assert jsonResult.get("result") instanceof String;
                assert jsonResult.get("result") != null;
                if (jsonResult.get("result").equals("no catch")) {
                    uncaughtExceptionInfo.removeCheckedUncaughtThrowExceptionInfo(uncaughtExceptionInfo.getUncaughtThrowExceptionInfo().get(i));
                }
                sb.append(LLMResponse.getJsonString(LLMApiCaller1.getLastAssistantMessage()))
                        .append(",\n");
            } catch (JsonSyntaxException | ClassCastException | AssertionError throwable) {
                LOGGER.warning(throwable.getMessage() + ", abort handle throw " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
                return null;
            }
        }
        sb.delete(sb.length() - 2, sb.length())
                .append("\n]");
        LOGGER.info("throw handle success: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
        return sb.toString();
    }
    public static String constructThrowQuestionPrompt(UncaughtExceptionInfoThrow uncaughtExceptionInfo, int index) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptTemplates.get(THROW_QUESTION).replaceAll("\\{str1\\}", Matcher.quoteReplacement(uncaughtExceptionInfo.getUncaughtThrowExceptionInfo().get(index).getThrowStatementString())));
        sb.append(promptTemplates.get(THROW_FORMAT).replaceAll("\\{str1\\}", Matcher.quoteReplacement(uncaughtExceptionInfo.getUncaughtThrowExceptionInfo().get(index).getThrowStatementString())));
        sb.append("\n");
        sb.append("<code snippet>\n");                                    // 中文：<代码片段>
        sb.append("```\n").append(uncaughtExceptionInfo.getMethodTreeNode().getCode()).append("\n```\n");
        sb.append("<end>\n\n");
        return sb.toString();
    }

    private ExampleHandler.ExampleData exampleData;

    public Advisers(ExampleHandler.ExampleData exampleData) {
        this.exampleData = exampleData;
    }

    public Map<String, String> handleMainQuestion(MethodTreeNode root, List<UncaughtExceptionInfo> exceptionResults, String experimentMark) {
        String expectedName = experimentMark + "+" + root.getSimpleName();
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
            String dataToRecord = new Gson().toJson(result);
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
            Map<String, Object> jsonResult = LLMResponse.parseJsonMessage(LLMResponse.getJsonString(existData));
            Map<String, String> result = new HashMap<>();
            for (String key: jsonResult.keySet()) {
                result.put(key, (String) jsonResult.get(key));
            }
            LOGGER.info("main question handle success: " + root.getSimpleName());
            return result;
        }
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
                // 解析回复
                Set<String> checkedUncaughtExceptions = new HashSet<>();
                try {
                    Map<String, Object> jsonResult = LLMResponse.parseJsonMessage(LLMResponse.getJsonString(existData));
                    assert jsonResult.get("remark") instanceof String;
                    if (!jsonResult.get("remark").equals("")) {
                        uncaughtExceptionInfo.setDescription((String) jsonResult.get("remark"));
                    }
                    assert jsonResult.get("result") instanceof ArrayList;
                    List<String> checkedUncaughtExceptionsSimpleNames = (ArrayList<String>) jsonResult.get("result");
                    for (String simpleName : checkedUncaughtExceptionsSimpleNames) {
                        for (String qualifiedName : uncaughtExceptionInfo.getUncaughtExceptions()) {
                            if (qualifiedName.endsWith(simpleName)) {
                                checkedUncaughtExceptions.add(qualifiedName);
                            }
                        }
                    }
                } catch (JsonSyntaxException | ClassCastException | AssertionError throwable) {
                    LOGGER.warning(throwable.getMessage() + ", abort handle api " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
                    uncaughtExceptionInfo.setCheckedUncaughtExceptions(uncaughtExceptionInfo.getUncaughtExceptions());
                    return null;
                }
                // 更新UncaughtExceptionInfoAPI
                uncaughtExceptionInfo.setCheckedUncaughtExceptions(checkedUncaughtExceptions);
                LOGGER.info("api handle success: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
            } else if (uncaughtExceptionInfo instanceof UncaughtExceptionInfoThrow) {
                LOGGER.info("handling throw by recorded data: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
                List<String> disassembledData = ExampleHandler.disassemblyJsonListString(existData);
                assert disassembledData.size() == ((UncaughtExceptionInfoThrow) uncaughtExceptionInfo).getUncaughtThrowExceptionInfo().size();
                for (int i = 0; i < disassembledData.size(); i++) {
                    // 解析回复
                    try {
                        Map<String, Object> jsonResult = LLMResponse.parseJsonMessage(LLMResponse.getJsonString(disassembledData.get(i)));
                        assert jsonResult.get("remark") instanceof String;
                        uncaughtExceptionInfo.setDescription((String) jsonResult.get("remark"));
                        assert jsonResult.get("result") instanceof Boolean;
                        if (jsonResult.get("result").equals(false)) {
                            ((UncaughtExceptionInfoThrow) uncaughtExceptionInfo).removeCheckedUncaughtThrowExceptionInfo(((UncaughtExceptionInfoThrow) uncaughtExceptionInfo).getUncaughtThrowExceptionInfo().get(i));
                        }
                    } catch (JsonSyntaxException | ClassCastException | AssertionError throwable) {
                        LOGGER.warning(throwable.getMessage() + ", abort handle throw " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
                        return null;
                    }
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
                        .addMessage(Advisers.constructSystemPrompt(), "system")
                        .addMessage(Advisers.constructBaselinePrompt(methodText), "user");
                // 调用LLM
                try {
                    LLMApiCaller1.call();
                } catch (IOException e) {
                    // todo 抛出并处理调用失败
                    throw new RuntimeException(e);
                }
                // 解析结果
                Map<String, Object> jsonResult = LLMApiCaller1.getDeepSeekResponse().getJsonMessage();
                for (String key: jsonResult.keySet()) {
                    result.put(key, (String) jsonResult.get(key));
                }
                break;
            } catch (JsonSyntaxException | ClassCastException e) {
                lastException = e;
                retryCount++;
                // todo 抛出并处理解析失败
                if (retryCount < SYNTAX_RETRY) {
                    LOGGER.fine("llm baseline question parse failed: " + e.getMessage() + " retry (" + retryCount + "/" + SYNTAX_RETRY + ")...");
                } else {
                    // 不再重试，抛出异常 todo 抛出并处理调用失败
                    LOGGER.warning("llm baseline question abort");
                    throw new RuntimeException(lastException);
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

}
