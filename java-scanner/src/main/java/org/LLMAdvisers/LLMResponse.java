package org.LLMAdvisers;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.util.List;
import java.util.Map;

public class LLMResponse {
    private String id;
    private String object;
    private long created;
    private String model;
    private List<Choice> choices;
    private Usage usage;
    private String system_fingerprint;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getObject() { return object; }
    public void setObject(String object) { this.object = object; }

    public long getCreated() { return created; }
    public void setCreated(long created) { this.created = created; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public List<Choice> getChoices() { return choices; }
    public void setChoices(List<Choice> choices) { this.choices = choices; }

    public Usage getUsage() { return usage; }
    public void setUsage(Usage usage) { this.usage = usage; }

    public String getSystemFingerprint() { return system_fingerprint; }
    public void setSystemFingerprint(String system_fingerprint) { this.system_fingerprint = system_fingerprint; }

    // Inner classes
    public static class Choice {
        private int index;
        private Message message;
        private Object logprobs;
        private String finish_reason;

        // Getters and Setters
        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }

        public Message getMessage() { return message; }
        public void setMessage(Message message) { this.message = message; }

        public Object getLogprobs() { return logprobs; }
        public void setLogprobs(Object logprobs) { this.logprobs = logprobs; }

        public String getFinishReason() { return finish_reason; }
        public void setFinishReason(String finish_reason) { this.finish_reason = finish_reason; }
    }

    public static class Message {
        private String role;
        private String content;

        // Getters and Setters
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    public static class Usage {
        private int prompt_tokens;
        private int completion_tokens;
        private int total_tokens;
        private PromptTokensDetails prompt_tokens_details;
        private int prompt_cache_hit_tokens;
        private int prompt_cache_miss_tokens;

        // Getters and Setters
        public int getPromptTokens() { return prompt_tokens; }
        public void setPromptTokens(int prompt_tokens) { this.prompt_tokens = prompt_tokens; }

        public int getCompletionTokens() { return completion_tokens; }
        public void setCompletionTokens(int completion_tokens) { this.completion_tokens = completion_tokens; }

        public int getTotalTokens() { return total_tokens; }
        public void setTotalTokens(int total_tokens) { this.total_tokens = total_tokens; }

        public PromptTokensDetails getPromptTokensDetails() { return prompt_tokens_details; }
        public void setPromptTokensDetails(PromptTokensDetails prompt_tokens_details) { this.prompt_tokens_details = prompt_tokens_details; }

        public int getPromptCacheHitTokens() { return prompt_cache_hit_tokens; }
        public void setPromptCacheHitTokens(int prompt_cache_hit_tokens) { this.prompt_cache_hit_tokens = prompt_cache_hit_tokens; }

        public int getPromptCacheMissTokens() { return prompt_cache_miss_tokens; }
        public void setPromptCacheMissTokens(int prompt_cache_miss_tokens) { this.prompt_cache_miss_tokens = prompt_cache_miss_tokens; }
    }

    public static class PromptTokensDetails {
        private int cached_tokens;

        // Getters and Setters
        public int getCachedTokens() { return cached_tokens; }
        public void setCachedTokens(int cached_tokens) { this.cached_tokens = cached_tokens; }
    }

    public Map<String, Object> getJsonMessage() throws JsonSyntaxException {
        String respond = getChoices().get(0).getMessage().getContent();
        return parseJsonMessage(getJsonString(respond));
    }
    public static Map<String, Object> parseJsonMessage(String string) {
        return new Gson().fromJson(string, new TypeToken<Map<String, Object>>() {}.getType());
    }
    public static String getJsonString(String string) {
        if (string.startsWith("```json\n") && string.endsWith("\n```")) {
            return string.substring("```json\n".length(), string.length() - "\n```".length());
        } else if (string.startsWith("{") && string.endsWith("}")) {
            return string;
        } else {
            return string;
        }
    }

    public static String stripCode(String string) {
        if (string == null) {
            return "";
        }
        if (string.startsWith("```java\n") && string.endsWith("```")) {
            return string.substring("```java\n".length(), string.length() - "```".length());
        } else if (string.startsWith("```\n") && string.endsWith("```")) {
            return string.substring("```\n".length(), string.length() - "```".length());
        } else {
            return string;
        }
    }
}
