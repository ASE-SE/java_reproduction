package org.callTreeGenerator;

import org.jdkAnalyzer.SQLUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

@Deprecated
public class ExceptionTypeJsonProcessor {

    public static void main(String[] args) {
        String inputFilePath = "D:\\Workspace\\uncaught exception\\example\\output0.json"; // JSON文件的路径
        String outputFilePath = "exception_statistic.json"; // 输出文件的路径

        try {
            // 读取JSON文件
            JSONArray jsonArray = readJsonArrayFromFile(inputFilePath);
//            JSONArray jsonArray = new org.json.JSONArray(new FileReader(inputFilePath).toString());

            // 创建一个Set来存储不重复的exceptionTypes
            Set<String> types = new HashSet<>();

            // 遍历JSON数组，将exceptionTypes添加到Set中
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONArray exceptionTypes = jsonArray.getJSONObject(i).getJSONArray("exceptionTypes");
                for (int j = 0; j < exceptionTypes.length(); j++) {
                    types.add(exceptionTypes.getString(j));
                }
            }

            // 调用isRuntime()方法，并将结果存储到Map中
            Map<String, Integer> results = new HashMap<>();
            for (String name: types) {
                try{
                    if (name.contains("|")) {
                        List<String> resultList = splitAndTrim(name, '|');
                        boolean flag1 = false;
                        for (String s: resultList) {
                            if (SQLUtil.isSimpleExceptionRuntime(s) == 1) {
                                flag1 = true;
                            }
                        }
                        if (flag1) {
                            results.put(name, 2);
                        } else {
                            results.put(name, -3);
                        }
                    } else{
                        results.put(name, SQLUtil.isSimpleExceptionRuntime(name));
                    }
                } catch (IllegalStateException ignore) {
                    // ignore
                }
            }

            // 将结果写入新的JSON文件
            writeResultsToJson(results, outputFilePath);

            System.out.println("Processing complete. Results are written to " + outputFilePath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static List<String> splitAndTrim(String input, char delimiter) {
        // 使用split方法分割字符串
        String[] parts = input.split("\\\\" + delimiter);

        // 创建一个List来存储结果
        List<String> resultList = new ArrayList<>();

        // 遍历分割后的字符串数组
        for (String part : parts) {
            // 去除每个子串的前后空格，并添加到结果List中
            if (!part.isEmpty()) {
                resultList.add(part.trim());
            }
        }

        return resultList;
    }

//    private static JSONArray readJsonArrayFromFile(String filePath) throws IOException {
//        org.json.JSONObject jsonObject = new org.json.JSONObject(new FileReader(filePath));
//        return jsonObject.getJSONArray("data"); // 假设JSON数组在"data"键下
//    }

    public static JSONArray readJsonArrayFromFile(String filePath) throws IOException {
        StringBuilder jsonStringBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                jsonStringBuilder.append(line);
            }
        }

        // 将字符串构建器中的JSON字符串解析为JsonArray
        return new JSONArray(jsonStringBuilder.toString());
    }

    private static void writeResultsToJson(Map<String, Integer> results, String filePath) throws IOException {
        JSONObject jsonObject = new JSONObject();

        // 遍历Map，将键值对添加到JSONObject中
        for (Map.Entry<String, Integer> entry : results.entrySet()) {
            jsonObject.put(entry.getKey(), entry.getValue());
        }

        // 将JSONObject写入文件
        try (FileWriter file = new FileWriter(filePath)) {
            file.write(jsonObject.toString(4)); // 使用4个空格进行格式化
        }
    }
}
