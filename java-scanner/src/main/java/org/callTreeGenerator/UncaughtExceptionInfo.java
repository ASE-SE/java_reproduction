package org.callTreeGenerator;

import org.ExperimentExecutor.ExampleHandler;

import java.util.List;
import java.util.Set;

/**
 * 记录调用树上一个节点中形成的一个未捕获运行时异常具体信息的详细数据
 * 数据包括：
 *   methodTreeNode 存在未捕获异常方法对应树节点
 *   nodeRoute 调用树上的路径
 *   uncaughtExceptions 传播至顶层时未捕获异常内容
 */
public class UncaughtExceptionInfo {
    protected MethodTreeNode methodTreeNode;
    protected List<MethodTreeNode> nodeRoute;
    protected Set<String> uncaughtExceptions; // 由静态分析得到的，与路径匹配的未被捕获Runtime异常
    protected Set<String> checkedUncaughtExceptions = null; // 借助LLM判断，得到的需关注的未被捕获Runtime异常，包含于uncaughtExceptions
    protected String description = null; // 借助LLM得到的对该未捕获异常的描述

    public UncaughtExceptionInfo(MethodTreeNode methodTreeNode, List<MethodTreeNode> nodeRoute, Set<String> uncaughtExceptions) {
        this.methodTreeNode = methodTreeNode;
        this.nodeRoute = nodeRoute;
        this.uncaughtExceptions = uncaughtExceptions;
    }

    public MethodTreeNode getMethodTreeNode() {
        return methodTreeNode;
    }

    public List<MethodTreeNode> getNodeRoute() {
        return nodeRoute;
    }

    public Set<String> getUncaughtExceptions() {
        return uncaughtExceptions;
    }

    public Set<String> getCheckedUncaughtExceptions() {
        return checkedUncaughtExceptions;
    }

    public void setCheckedUncaughtExceptions(Set<String> checkedUncaughtExceptions) {
        this.checkedUncaughtExceptions = checkedUncaughtExceptions;
    }

    public boolean removeCheckedUncaughtException(String checkedUncaughtException) {
        if (this.checkedUncaughtExceptions.contains(checkedUncaughtException)) {
            checkedUncaughtExceptions.remove(checkedUncaughtException);
            return true;
        } else {
            return false;
        }
    }

    public MethodTreeNode getSecondLastNode() {
        if (nodeRoute.size() < 2) {
            throw new RuntimeException("UncaughtExceptionInfo node route size shouldn't less than 2");
        }
        return nodeRoute.get(nodeRoute.size() - 2);
    }

    public String getUncaughtExceptionsString() {
        StringBuilder sb = new StringBuilder();
        for (String e: uncaughtExceptions) {
            sb.append(e).append(", ");
        }
        return sb.substring(0, sb.length() - 2);
    }

    /**
     * 优先返回经过LLM检查的异常列表，如果为空，则返回未经检查的列表
     * @return 一个以异常名称列表字符串
     */
    public String getCheckedUncaughtExceptionsString() {
        if (checkedUncaughtExceptions.isEmpty()) return getUncaughtExceptionsString();
        StringBuilder sb = new StringBuilder();
        for (String e: checkedUncaughtExceptions) {
            sb.append(e).append(", ");
        }
        return sb.substring(0, sb.length() - 2);
    }

    public boolean hasDescription() {
        return (!(description == null));
    }
    public String getDescription() {
        if (!hasDescription()) {
            return "";
        }
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 或取该方法的异常抛出情况。若未经LLM判断，则直接返回uncaughtExceptions是否有抛出；若经过LLM判断，则返回由LLM得到的checkedUncaughtExceptions中是否有抛出
     * @return 是否存在需要处理的uncaught exception
     */
    public Boolean isContainUncaughtExceptions() {
        if (checkedUncaughtExceptions == null) {
            return (!uncaughtExceptions.isEmpty());
        } else {
            return (!checkedUncaughtExceptions.isEmpty());
        }
    }

    public String getHash() {
        String representation = new StringBuilder()
                .append(methodTreeNode.toString())
                .append(nodeRoute.toString())
                .append(uncaughtExceptions.toString())
                .toString();
        return ExampleHandler.md5Hash(representation);
    }

}
