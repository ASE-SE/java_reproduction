package org.callTreeGenerator;

import java.util.*;

public class UncaughtExceptionInfoThrow extends UncaughtExceptionInfo {

    private List<ThrowExceptionInfo> uncaughtThrowExceptionInfo; // 异常类型与uncaughtExceptions表中匹配的throw语句
    private List<ThrowExceptionInfo> checkedUncaughtThrowExceptionInfo = null; // 经LLM筛选后得到的需关注throw

    public UncaughtExceptionInfoThrow(MethodTreeNode methodTreeNode, List<MethodTreeNode> nodeRoute, Set<String> uncaughtExceptions) {
        super(methodTreeNode, nodeRoute, uncaughtExceptions);
        initializeUncaughtThrowExceptionInfo();
    }
    private void initializeUncaughtThrowExceptionInfo() {
        uncaughtThrowExceptionInfo = new ArrayList<>();
        for (ThrowExceptionInfo throwExceptionInfo: methodTreeNode.getThrowExceptionInfo()) {
            if (uncaughtExceptions.contains(throwExceptionInfo.getExceptionQualifiedName())) {
                uncaughtThrowExceptionInfo.add(throwExceptionInfo);
            }
        }
    }

    public List<ThrowExceptionInfo> getUncaughtThrowExceptionInfo() {
        return uncaughtThrowExceptionInfo;
    }

    public List<ThrowExceptionInfo> getCheckedUncaughtThrowExceptionInfo() {
        return checkedUncaughtThrowExceptionInfo;
    }

    public void setCheckedUncaughtThrowExceptionInfo(List<ThrowExceptionInfo> checkedUncaughtThrowExceptionInfo) {
        this.checkedUncaughtThrowExceptionInfo = checkedUncaughtThrowExceptionInfo;
    }

    public void removeCheckedUncaughtThrowExceptionInfo(ThrowExceptionInfo checkedUncaughtThrowExceptionInfo) {
        if (this.checkedUncaughtThrowExceptionInfo == null) {
            this.checkedUncaughtThrowExceptionInfo = new ArrayList<>();
            return;
        }
        this.checkedUncaughtThrowExceptionInfo.remove(checkedUncaughtThrowExceptionInfo);
    }

    public String toString() {
        return "method: " + methodTreeNode.getQualifiedName() + "\nroute:\n    " + nodeRoute.toString() + "\nthrows:\n" + throwDisplayString(uncaughtThrowExceptionInfo, 4);
//        return "route: " + nodeRoute.toString() + ", exception: " + uncaughtExceptions.toString();
    }
    public String throwDisplayString(List<ThrowExceptionInfo> throwExceptionInfoes, int offset) {
        StringBuilder sb = new StringBuilder();
        for (ThrowExceptionInfo throwExceptionInfo: throwExceptionInfoes) {
            sb.append(" ".repeat(offset));
            sb.append(throwExceptionInfo.getExceptionQualifiedName());
            sb.append(":");
            sb.append(throwExceptionInfo.toString().replaceAll("\n", "\t"));
            sb.append("\n");
        }
        return sb.toString();
    }

    @Override
    public Boolean isContainUncaughtExceptions() {
        if (checkedUncaughtThrowExceptionInfo == null) {
            return (!uncaughtThrowExceptionInfo.isEmpty());
        } else {
            return (!checkedUncaughtThrowExceptionInfo.isEmpty());
        }
    }
}
