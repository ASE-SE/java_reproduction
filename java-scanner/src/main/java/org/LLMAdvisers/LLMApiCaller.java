package org.LLMAdvisers;

import com.google.gson.Gson;
import okhttp3.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 对DeepSeek api进行调用的工具
 * 单次对话需创建独立的DeepSeekApiCaller实例
 */
public class LLMApiCaller {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    private static String API_URL;
    private static String API_KEY;
    private static String MODEL_NAME;
    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            prop.load(fis);
            API_URL = prop.getProperty("LLM_API_URL");
            API_KEY = prop.getProperty("LLM_API_KEY");
            MODEL_NAME = prop.getProperty("LLM_MODEL_NAME");
        } catch (IOException e) {
            LOGGER.severe("config loading failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    private static final int DEFAULT_TRY_TIMES = 20; // 默认最大重试次数
    private static final long RETRY_DELAY_MS = 1000; // 重试延迟时间（毫秒）
    private static final int DEFAULT_TIMEOUT = 300; // HTTP client等待时间
    private LLMRequest LLMRequest;
    private LLMResponse LLMResponse;
    private Response response;
    private String lastAssistantMessage;

    public LLMApiCaller() {
        LLMRequest = new LLMRequest();
        LLMRequest.setModel(MODEL_NAME);
    }

    public LLMApiCaller addMessage(String content, String role) {
        this.LLMRequest.addMessage(content, role);
        return this;
    }

    public LLMRequest getDeepSeekRequest() {
        return LLMRequest;
    }

    public LLMApiCaller call() throws IOException{
        return call(DEFAULT_TRY_TIMES);
    }
    public LLMApiCaller call(int maxRetries) throws IOException{
        LOGGER.fine("start call " + LLMRequest.hashCode());
        OkHttpClient client = new OkHttpClient().newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
                .build();
        MediaType mediaType = MediaType.parse("application/json");
        RequestBody body = RequestBody.create(
                LLMRequest.toJsonString(),
                mediaType);
        Request request = new Request.Builder()
                .url(API_URL)
                .method("POST", body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .build();
        int retryCount = 0;
        IOException lastException = null;

        while (retryCount < maxRetries) {
            try {
                // 执行请求
                Response response = client.newCall(request).execute();

                // 如果响应成功，直接返回
                if (response.isSuccessful()) {
                    this.response =  response;
                    String responseBody = response.body().string();
                    // 使用GSON解析JSON
                    LLMResponse = new Gson().fromJson(responseBody, LLMResponse.class);
                    // 提取模型回复的语句
                    lastAssistantMessage = LLMResponse.getChoices().get(0).getMessage().getContent();
                    LLMRequest.addMessage(lastAssistantMessage, "assistant");
                    LOGGER.fine("call succeed: " + LLMRequest.hashCode());
                    LOGGER.finer(LLMRequest.hashCode() + " LLM chat as below:\n" + LLMRequest.messageToString());
                    return this;
                } else {
                    // 如果响应失败，关闭响应体并重试
                    response.close();
                    throw new IOException("HTTP请求失败，状态码: " + response.code());
                }
            } catch (IOException e) {
                lastException = e;
                retryCount++;

                // 判断是否需要重试
                if (shouldRetry(e) && retryCount < maxRetries) {
                    LOGGER.fine("call failed: " + e.getMessage() + " retry (" + retryCount + "/" + maxRetries + ")...");
                    try {
                        // 延迟一段时间后重试
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("重试延迟被中断", ie);
                    }
                } else {
                    // 不再重试，抛出异常
                    LOGGER.warning("call abort: " + LLMRequest.hashCode());
                    throw lastException;
                }
            }
        }
        // 重试次数用尽，抛出最后一次异常
        LOGGER.warning("call abort: " + LLMRequest.hashCode());
        throw lastException;
    }
    /**
     * 判断是否需要重试
     */
    private static boolean shouldRetry(IOException e) {
        // 如果是网络连接问题或超时，则重试
        return e instanceof ConnectException ||
                e instanceof SocketTimeoutException ||
                e instanceof UnknownHostException ||
                e.getMessage().startsWith("HTTP请求失败，状态码: ");
    }

    public LLMResponse getDeepSeekResponse() {
        return LLMResponse;
    }

    public Response getResponse() {
        return response;
    }

    public String getLastAssistantMessage() {
        return lastAssistantMessage;
    }

    public String getConversation() {
        return LLMRequest.messageToString();
    }
}
