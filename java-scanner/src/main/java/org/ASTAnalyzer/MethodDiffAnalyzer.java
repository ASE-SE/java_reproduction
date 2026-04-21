package org.ASTAnalyzer;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.TryStatement;

import java.util.*;
import java.util.logging.Logger;

public class MethodDiffAnalyzer {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    public static List<TryPatternData> analyze(String method1, String method2){
        // 第一步筛选，排除不包含try-catch结构的方法对
        if (!method2.contains("try") || !method2.contains("catch")){
            return new ArrayList<>();
        }
        // 获取CompilationUnit和MethodVisitor
        CompilationUnit methodCU1 = Util.getMethodCompilationUnit(method1);
        CompilationUnit methodCU2 = Util.getMethodCompilationUnit(method2);
        MethodVisitor mv1 = new MethodVisitor();
        MethodVisitor mv2 = new MethodVisitor();
        // 扫描两个method
        try {
            methodCU1.accept(mv1);
            methodCU2.accept(mv2);
        } catch (IllegalStateException | NullPointerException e) {
            LOGGER.warning("method visit failed, method1: \n" +method1 + "\nmethod2: \n" + method2);
            return null;
        }
        // 获取两版本规范后代码的行映射以及每行try重数
        Map<Integer, Integer> lineMatchSec2First = matchLines(mv1.getMethodBlock(), mv2.getMethodBlock());
        int[] method1lineTryNestings = mv1.getMethodLineTryNestings();
        int[] method2lineTryNestings = mv2.getMethodLineTryNestings();
        // 主筛选，从新版本method中的每个TryStatement中筛选得到符合的
        List<TryBlock> tryWithPatterns = new ArrayList<>();
        List<TryPatternData> results = new ArrayList<>();

        for (TryBlock tb: mv2.getTryBlocks()){
            // 1. 获取并遍历新版本try中的有效语句，与修改前进行匹配判断是否增加
            int offset = 0;
            if (tb.getNesting() > 0) {
                for (TryStatement parentTS : tb.getParentTries()) {
                    if (tryBlockListContainsTryStatement(tryWithPatterns, parentTS)) {
                        offset += 1;
                    }
                }
            }
            List<Integer> validIndexes = tb.getValidIndexesInMethod();
            List<Integer> matchedValidIndexes = new ArrayList<>();
            for (int i : validIndexes){
                if (lineMatchSec2First.containsKey(i)){
                    if (method2lineTryNestings[i] > method1lineTryNestings[lineMatchSec2First.get(i)] + offset){
                        matchedValidIndexes.add(i);
                    }
                }
            }
            if (matchedValidIndexes.isEmpty()) continue;
            // 2. 考察其中不在嵌套try部分的重数增加情况，若无则不是
            List<Integer> matchedLinesNotInNestedTry = new ArrayList<>(matchedValidIndexes);
            matchedLinesNotInNestedTry.retainAll(tb.getSelfNoNestingLines());
            if (matchedLinesNotInNestedTry.isEmpty()) {
                continue;
            }

            // 3. 可能的其他规则
            // todo

            // 通过筛选，进行添加
            tryWithPatterns.add(tb);
            results.add(new TryPatternData(tb, mv1.getMethodDeclaration(), mv2.getMethodDeclaration(), lineMatchSec2First));
        }
//        return tryWithPatterns;
        return results;
    }

    public static Map<Integer, Integer> matchLines(String version1, String version2) {
        String[] lines1 = version1.split("\n");
        String[] lines2 = version2.split("\n");

        int[][] d = new int[lines1.length + 1][lines2.length + 1];

        for (int i = 0; i <= lines1.length; i++) {
            d[i][0] = i; // 删除操作
        }
        for (int j = 0; j <= lines2.length; j++) {
            d[0][j] = j; // 插入操作
        }

        for (int i = 1; i <= lines1.length; i++) {
            for (int j = 1; j <= lines2.length; j++) {
                if (Util.isLineMatch(lines1[i - 1], lines2[j - 1])) {
                    d[i][j] = d[i - 1][j - 1]; // 不做任何操作
                } else {
                    d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + 1); // 删除、插入、替换
                }
            }
        }

        int i = lines1.length;
        int j = lines2.length;

        Map<Integer, Integer> sec2first = new HashMap<>();
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && Util.isLineMatch(lines1[i - 1], lines2[j - 1])) {
                sec2first.put(j - 1, i - 1); // 存储匹配的行索引
                i--;
                j--;
            } else if (i > 0 && (j == 0 || d[i][j] == d[i - 1][j] + 1)) {
                i--;
            } else if (j > 0 && (i == 0 || d[i][j] == d[i][j - 1] + 1)) {
                j--;
            } else {
                i--;
                j--;
            }
        }

        return sec2first;
    }

    private static boolean tryBlockListContainsTryStatement(List<TryBlock> listTryBlock, TryStatement tryStatement) {
        boolean containFlag = false;
        for (TryBlock tb : listTryBlock) {
            if (tb.equals(tryStatement)) {
                containFlag = true;
                break;
            }
        }
        return containFlag;
    }

    public static int[] getIntersection(int[] arr1, int[] arr2) {
        Set<Integer> set = new HashSet<>();
        Set<Integer> intersect = new HashSet<>();

        for (int num : arr1) {
            set.add(num);
        }

        for (int num : arr2) {
            if (set.contains(num)) {
                intersect.add(num);
            }
        }

        int[] intersection = new int[intersect.size()];
        int index = 0;
        for (int num : intersect) {
            intersection[index++] = num;
        }

        return intersection;
    }
}
