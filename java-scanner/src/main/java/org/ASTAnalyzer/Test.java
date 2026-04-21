package org.ASTAnalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Test {


//    public static void main(String[] args) {
//        String code = "      File f=File.createTempFile(\"imageio\", \".tmp\");";
//        String regex = "^[\\s\t]*(\\w+)\\s+(\\w+)\\s*=\\s*.*;\\s*$";
//
//        Pattern pattern = Pattern.compile(regex);
//        Matcher matcher = pattern.matcher(code);
//
//        if (matcher.matches()) {
//            System.out.println("Matched!");
//            String withoutType = code.replaceAll((matcher.group(1) + " "), "");
//            System.out.println(code);
//            System.out.println(withoutType);
//        } else {
//            System.out.println("No match found.");
//        }
//    }

//    public static void main(String[] args) {
//        String code = "try (Socket sa=ss.accept(new Socket(port, host))) {";
//        String regex = "^try\\s*\\((.*)\\)\\s*\\{";
//
//        Pattern pattern = Pattern.compile(regex);
//        Matcher matcher = pattern.matcher(code);
//
//        if (matcher.find()) {
//            System.out.println("Matched!");
//            String insideParentheses = matcher.group(1);
//            System.out.println(insideParentheses);
//            String extractedContent = extractContentWithNestedParentheses(insideParentheses);
//            System.out.println("Content inside parentheses: " + extractedContent);
//        } else {
//            System.out.println("No match found.");
//        }
//    }
//
//    // 解析带嵌套括号的内容
//    public static String extractContentWithNestedParentheses(String input) {
//        StringBuilder result = new StringBuilder();
//        int depth = 0;
//
//        for (int i = 0; i < input.length(); i++) {
//            char ch = input.charAt(i);
//
//            if (ch == '(') {
//                depth++;
//            } else if (ch == ')') {
//                depth--;
//            }
//
//            result.append(ch);
//
//            // 如果括号深度回到0，说明完成了整个括号的匹配
//            if (depth == 0 && ch == ')') {
//                break;
//            }
//        }
//
//        return result.toString();
//    }
//    public static void main(String[] args) {
//        boolean b = Util.isLineMatch("  File f=File.createTempFile(\"imageio\",\".tmp\");", "    f=File.createTempFile(\"imageio\",\".tmp\");");
//        System.out.println(b);
//    }
	
	public static void test() {
		try {
			String a = "";
			System.out.println(a);
		} catch (RuntimeException e1) {
			System.out.print("e1");
		} catch (Exception e2) {
			System.out.print("e2");
		} finally {
			String b = "fi";
			System.out.println(b);
		}
		String c = "c";
	}
}
//
