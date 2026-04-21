package org.ASTAnalyzer;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.TryStatement;

import java.util.*;

public class TryBlock {

    private final TryStatement tryNode;
    private final String fullMethodBlock;
    private final Set<TryStatement> parentTries;
    private final int nesting;
    private final int formatStartLine;
    private final int formatLength;
    private List<Integer> selfNoNestingLines = new ArrayList<>();

    public TryBlock(TryStatement tryNode, String fullMethodBlock){
        this.tryNode = tryNode;
        this.fullMethodBlock = fullMethodBlock;
        this.parentTries = getDirectTryParent(tryNode);
        this.nesting = parentTries.size();
        String[] fullMethodLines = fullMethodBlock.split("\n");
        String[] tryLines = tryNode.toString().replaceAll("\\s+$", "").split("\n");
        formatStartLine = findStartIndex(fullMethodLines, tryLines);
        formatLength = tryLines.length;
    }

    protected Set<TryStatement> getDirectTryParent(ASTNode node){
        if (node.getParent() == null){
            return new HashSet<TryStatement>();
        }
        if (node.getParent() instanceof TryStatement){
            Set<TryStatement> tryset = getDirectTryParent(node.getParent());
            tryset.add((TryStatement) node.getParent());
            return tryset;
        } else {
            return getDirectTryParent(node.getParent());
        }
    }

    public boolean childOf(TryStatement tryNode){
        return parentTries.contains(tryNode);
    }

    public List<Integer> getValidIndexesInMethod() throws IllegalStateException{
        String[] fullMethodLines = fullMethodBlock.split("\n");
        List<Integer> validCodeIndexes = new ArrayList<>();
        for (int i = formatStartLine; i < formatStartLine + formatLength; i++){
            if (fullMethodLines[i]
                    .replaceAll("^[\\s\t]+", "")
                    .replaceAll("[\\s\t]+$", "")
                    .length() > 1){
                validCodeIndexes.add(i);
            }
        }
        return validCodeIndexes;
    }

    protected static int findStartIndex(String[] a, String[] b) throws IllegalStateException{
        // 在a中寻找b的起始位置
        int n = a.length;
        int m = b.length;

        for (int i = 0; i <= n - m; i++) {
            boolean match = true;
            for (int j = 0; j < m; j++) {
                if (!Util.isLineMatchWithoutIndentation(a[i + j], b[j])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i; // 返回匹配的起始索引
            }
        }
        throw new IllegalStateException("try block match error");
    }

    public TryStatement getTryNode() {
        return tryNode;
    }

    public Set<TryStatement> getParentTries() {
        return parentTries;
    }

    public int getNesting() {
        return nesting;
    }

    public int getFormatStartLine() {
        return formatStartLine;
    }

    public int getFormatLength() {
        return formatLength;
    }

    public void addSelfLine(Integer line) {
        selfNoNestingLines.add(line);
    }

    public List<Integer> getSelfNoNestingLines() {
        return selfNoNestingLines;
    }

    public boolean hasFinally() {
        return !(tryNode.getFinally() == null);
    }

    @Override
    public boolean equals(Object obj){
        if (obj instanceof TryBlock){
            return ((TryBlock) obj).getTryNode().equals(this.tryNode);
        } else if (obj instanceof TryStatement){
            return (obj.equals(this.tryNode));
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        return tryNode.toString();
    }
}
