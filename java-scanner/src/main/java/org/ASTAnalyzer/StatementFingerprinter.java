package org.ASTAnalyzer;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.Type;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 从 AST 中抽取语句级"指纹"，用于在文本行匹配失效时（编辑距离漂移、大幅重构）
 * 判定 try 块是否为新增。指纹粒度选择上较粗，以容忍变量重命名、表达式重组，
 * 同时保留"有副作用动作"的核心标识：
 *   - 方法调用      → call:{name}:{argc}
 *   - 构造调用      → new:{type}:{argc}
 *   - throw 异常类  → throw:{exceptionType}
 */
public final class StatementFingerprinter {

    private StatementFingerprinter() {}

    // 通用方法名黑名单：这些名称在不同上下文重名概率高，作为指纹会产生大量假阳
    private static final Set<String> COMMON_NAMES = new HashSet<>(Arrays.asList(
            "add", "remove", "get", "set", "put", "contains", "containsKey", "containsValue",
            "size", "length", "isEmpty", "clear",
            "equals", "hashCode", "toString", "clone", "getClass", "wait", "notify", "notifyAll",
            "valueOf", "of", "asList", "stream",
            "iterator", "hasNext", "next", "remove",
            "start", "stop", "close", "run", "cancel", "init",
            "append", "substring", "trim", "split", "concat", "format",
            "toUpperCase", "toLowerCase", "charAt", "indexOf", "lastIndexOf",
            "intValue", "longValue", "doubleValue", "floatValue", "booleanValue",
            "compareTo", "name", "ordinal"
    ));

    /**
     * 收集整个方法体内每个指纹的【最深】 try 嵌套深度。catch/finally 中的语句不计入。
     * 用最深深度的语义：仅当 m1 中某指纹的所有出现都比 m2 try 内更浅时，才认为 m2 try
     * 真正"新覆盖"了一条 m1 outer 语句；避免 m1 中该指纹本就在 try 里的情况被误判。
     */
    public static Map<String, Integer> collectMethodFingerprints(MethodDeclaration md) {
        Map<String, Integer> result = new HashMap<>();
        if (md == null || md.getBody() == null) return result;
        FullCollector collector = new FullCollector(result);
        md.getBody().accept(collector);
        return result;
    }

    /**
     * 收集 try 块 body 中（不进入更内层 try / 不进入 catch / 不进入 finally）的指纹集合。
     */
    public static Set<String> collectTryBodyFingerprints(TryStatement ts) {
        Set<String> result = new HashSet<>();
        if (ts == null || ts.getBody() == null) return result;
        TryBodyCollector collector = new TryBodyCollector(result);
        ts.getBody().accept(collector);
        return result;
    }

    // ---- 指纹生成 ----

    static String fpOfMethodInvocation(MethodInvocation mi) {
        if (mi.getName() == null) return null;
        String name = mi.getName().getIdentifier();
        if (name == null || name.isEmpty()) return null;
        if (COMMON_NAMES.contains(name)) return null;
        if (name.length() < 4) return null;  // 极短名（of/get/add 等）通用性高，过滤
        return "call:" + name + ":" + mi.arguments().size();
    }

    static String fpOfClassInstanceCreation(ClassInstanceCreation cic) {
        Type t = cic.getType();
        String typeStr = (t == null) ? "?" : t.toString();
        return "new:" + typeStr + ":" + cic.arguments().size();
    }

    static String fpOfThrow(ThrowStatement ts) {
        Expression e = ts.getExpression();
        if (e instanceof ClassInstanceCreation) {
            Type t = ((ClassInstanceCreation) e).getType();
            if (t != null) return "throw:" + t.toString();
        }
        return null;
    }

    static String fpOfReturn(ReturnStatement rs) {
        if (rs.getExpression() instanceof MethodInvocation) {
            return "ret:" + fpOfMethodInvocation((MethodInvocation) rs.getExpression());
        }
        return null;
    }

    // ---- 嵌套深度（与 MethodVisitor 的按行 +1 语义对齐：祖先含 N 个 TryStatement 即深度 N） ----

    static int tryNestingOf(ASTNode node) {
        int depth = 0;
        ASTNode p = node.getParent();
        while (p != null) {
            if (p instanceof TryStatement) depth++;
            p = p.getParent();
        }
        return depth;
    }

    // ---- 全方法收集器：跳过 catch/finally，按节点 try 深度记录每个指纹的最浅深度 ----

    private static class FullCollector extends ASTVisitor {
        private final Map<String, Integer> map;
        FullCollector(Map<String, Integer> map) { this.map = map; }

        @Override
        public boolean visit(CatchClause node) { return false; }

        @Override
        public boolean visit(TryStatement node) {
            // 不进入 finally；body 与 catch 由默认遍历控制，但我们重写 CatchClause 跳过 catch
            // 这里访问 body 走默认；进入 try 前显式访问 body 而非整个 TryStatement 以排除 finally
            if (node.getBody() != null) node.getBody().accept(this);
            // 跳过 catchClauses 和 finally
            return false;
        }

        @Override
        public boolean visit(MethodInvocation node) {
            record(fpOfMethodInvocation(node), node);
            return true;
        }

        @Override
        public boolean visit(ClassInstanceCreation node) {
            record(fpOfClassInstanceCreation(node), node);
            return true;
        }

        @Override
        public boolean visit(ThrowStatement node) {
            record(fpOfThrow(node), node);
            return true;
        }

        @Override
        public boolean visit(ReturnStatement node) {
            record(fpOfReturn(node), node);
            return true;
        }

        private void record(String fp, ASTNode node) {
            if (fp == null) return;
            int d = tryNestingOf(node);
            Integer existing = map.get(fp);
            // 取最深深度：仅当 m1 中所有出现都比 m2 try 浅，才视为"新覆盖"
            if (existing == null || d > existing) {
                map.put(fp, d);
            }
        }
    }

    // ---- try body 收集器：只收集 body 内、不进入更深 try ----

    private static class TryBodyCollector extends ASTVisitor {
        private final Set<String> fps;
        TryBodyCollector(Set<String> fps) { this.fps = fps; }

        @Override
        public boolean visit(TryStatement node) { return false; }

        @Override
        public boolean visit(MethodInvocation node) {
            add(fpOfMethodInvocation(node));
            return true;
        }

        @Override
        public boolean visit(ClassInstanceCreation node) {
            add(fpOfClassInstanceCreation(node));
            return true;
        }

        @Override
        public boolean visit(ThrowStatement node) {
            add(fpOfThrow(node));
            return true;
        }

        @Override
        public boolean visit(ReturnStatement node) {
            add(fpOfReturn(node));
            return true;
        }

        private void add(String fp) {
            if (fp != null) fps.add(fp);
        }
    }
}
