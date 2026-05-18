package org.ASTAnalyzer;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.TryStatement;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

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
        // method1 的语句指纹（fallback 路径才会触发计算）
        Map<String, Integer> m1Fps = null;

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
            boolean primaryPass = !matchedValidIndexes.isEmpty();
            if (primaryPass) {
                // 2. 考察其中不在嵌套try部分的重数增加情况，若无则不是
                List<Integer> matchedLinesNotInNestedTry = new ArrayList<>(matchedValidIndexes);
                matchedLinesNotInNestedTry.retainAll(tb.getSelfNoNestingLines());
                if (matchedLinesNotInNestedTry.isEmpty()) {
                    primaryPass = false;
                }
            }

            if (!primaryPass) {
                // 3. AST 指纹 fallback：当行匹配漂移/失败时，用语句级 AST 指纹判定
                //    是否存在某 method1 outer 语句被该 try 块新覆盖
                if (m1Fps == null) {
                    m1Fps = StatementFingerprinter.collectMethodFingerprints(mv1.getMethodDeclaration());
                }
                Set<String> tbFps = StatementFingerprinter.collectTryBodyFingerprints(tb.getTryNode());
                int tbInnerDepth = tb.getNesting() + 1;
                boolean fallbackPass = false;
                for (String fp : tbFps) {
                    Integer m1d = m1Fps.get(fp);
                    if (m1d != null && tbInnerDepth > m1d + offset) {
                        fallbackPass = true;
                        break;
                    }
                }
                if (!fallbackPass) continue;
                // fallback 通过：为下游 TryPatternData.getJson 塞占位行号映射（→ method1 第 0 行）
                // 仅填充原本缺失的行，绝不覆盖编辑距离已建立的精确映射
                for (int i : validIndexes) {
                    lineMatchSec2First.putIfAbsent(i, 0);
                }
            }

            // 通过筛选，进行添加
            tryWithPatterns.add(tb);
            results.add(new TryPatternData(tb, mv1.getMethodDeclaration(), mv2.getMethodDeclaration(), lineMatchSec2First));
        }
//        return tryWithPatterns;
        return results;
    }

    public static Map<Integer, Integer> matchLines(String version1, String version2) {
        String[] rawLines1 = version1.split("\n");
        String[] rawLines2 = version2.split("\n");
        // 行合并预处理：将 "...}\n else if(..){" / "...}\n else {" 等跨行格式合并为单行，
        // 避免编辑距离把单纯的换行差异判为 insert/delete 引起后续级联错配
        MergedLines merged1 = mergeMultiLineControlFlow(rawLines1);
        MergedLines merged2 = mergeMultiLineControlFlow(rawLines2);
        String[] lines1 = merged1.lines;
        String[] lines2 = merged2.lines;

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
                // 将合并行级匹配展开为原始行级映射；合并行的每个原始行都映射到 method1 合并行的首行
                int mergedI = i - 1;
                int mergedJ = j - 1;
                int origIRep = merged1.origStart[mergedI];
                for (int o = merged2.origStart[mergedJ]; o < merged2.origEnd[mergedJ]; o++) {
                    sec2first.put(o, origIRep);
                }
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

    private static final Pattern ELSE_START = Pattern.compile("^\\s*else(\\s+if\\b|\\s*\\{|\\s+).*");

    private static class MergedLines {
        String[] lines;
        int[] origStart;
        int[] origEnd;
    }

    private static MergedLines mergeMultiLineControlFlow(String[] raw) {
        List<String> outLines = new ArrayList<>();
        List<Integer> outStart = new ArrayList<>();
        int i = 0;
        while (i < raw.length) {
            int startIdx = i;
            StringBuilder sb = new StringBuilder(raw[i]);
            i++;
            // 上一行以 } 收尾且下一行以 else 起首 → 合并为同一行
            while (i < raw.length
                    && sb.toString().replaceAll("\\s+$", "").endsWith("}")
                    && ELSE_START.matcher(raw[i]).matches()) {
                sb.append(' ').append(raw[i].replaceAll("^\\s+", ""));
                i++;
            }
            outLines.add(sb.toString());
            outStart.add(startIdx);
        }
        MergedLines r = new MergedLines();
        r.lines = outLines.toArray(new String[0]);
        r.origStart = new int[outStart.size()];
        r.origEnd = new int[outStart.size()];
        for (int k = 0; k < outStart.size(); k++) {
            r.origStart[k] = outStart.get(k);
            r.origEnd[k] = (k + 1 < outStart.size()) ? outStart.get(k + 1) : raw.length;
        }
        return r;
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
