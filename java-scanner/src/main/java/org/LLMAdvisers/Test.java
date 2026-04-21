package org.LLMAdvisers;

import org.callTreeGenerator.MethodTreeNode;
import org.callTreeGenerator.UncaughtExceptionInfo;

import java.io.IOException;
import java.util.List;

/**
 * 用于快速测试LLM接口是否运作正常
 */
public class Test {
    public static void main(String[] args) throws Exception{
        test();
    }

    public static void test() throws IOException {
        LLMApiCaller LLMApiCaller1 = new LLMApiCaller()
                .addMessage("You are a helpful assistant", "system")
                .addMessage("Hi", "user");
        System.out.println(LLMApiCaller1
                .call()
                .getLastAssistantMessage());
        String sentence2 = "What's the highest mountain in the world?";
        System.out.println(sentence2);
        LLMApiCaller1.addMessage(sentence2, "user");
        System.out.println(LLMApiCaller1.getConversation());
        System.out.println(LLMApiCaller1.call().getLastAssistantMessage());
        System.out.println(LLMApiCaller1.getConversation());
    }

    public static void mainContent(MethodTreeNode root, List<UncaughtExceptionInfo> exceptionResults) {

    }
}
