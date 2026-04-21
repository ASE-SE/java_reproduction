package org.callTreeGenerator;

import org.jdkAnalyzer.SQLUtil;
import org.jgrapht.graph.DefaultDirectedGraph;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class GraphNodeTraversal {
    private static final Logger LOGGER = Logger.getLogger("MAIN");

    private ExceptionCharacteristicManager exceptionCharacteristicManager;

    public GraphNodeTraversal(ExceptionCharacteristicManager exceptionCharacteristicManager) {
        this.exceptionCharacteristicManager = exceptionCharacteristicManager;
    }

    public List<UncaughtExceptionInfo> getSuspiciousThrows(DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> graph, MethodTreeNode root) {
        LOGGER.info("start dfs graph traversal");
        return dfs(graph, root, new ArrayList<>(), new HashMap<>(), new ArrayList<>());
    }

    // DFS递归遍历
    private List<UncaughtExceptionInfo> dfs(
            DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> graph,
            MethodTreeNode currentNode,
            List<MethodTreeNode> currentPath,
            Map<MethodTreeNode, UncaughtExceptionInfo> visitedNodes,
            List<UncaughtExceptionInfo> uncaughtExceptions) {
        // 将当前节点添加到路径中
        currentPath.add(currentNode);
        // 处理当前路径及节点。对于任意已知API和存在throw的节点，分析路径上的catch情况得到未捕获exception，将未能catch的throw记录为UncaughtExceptionInfo至exceptionThrows中
        LOGGER.finer("current path: " + currentPath);
        String trueName = SQLUtil.getMethodNameIfExist(currentNode.getQualifiedName());
        // 对是否访问过、是否是API方法进行判断分4种情况分别分析
        if (visitedNodes.containsKey(currentNode)) {
            if (trueName != null) {
                // 访问过且是API节点
                ArchivedMethodTreeNode archivedMethodTreeNode = (ArchivedMethodTreeNode) visitedNodes.get(currentNode).getMethodTreeNode();
                Set<String> nodeExceptions = archivedMethodTreeNode.getExceptions().keySet();
                Set<String> nodeUncaughtExceptions = getUncaughtExceptionsOnGraph(graph, currentPath, nodeExceptions);
                UncaughtExceptionInfoAPI uncaughtExceptionInfo = new UncaughtExceptionInfoAPI(archivedMethodTreeNode, new ArrayList<>(currentPath), nodeUncaughtExceptions);
                if (!nodeUncaughtExceptions.isEmpty()) {
                    uncaughtExceptions.add(uncaughtExceptionInfo);
                    LOGGER.fine("record archived API node:\n" + uncaughtExceptionInfo.toString());
                }
            } else if (!currentNode.getThrowExceptionInfo().isEmpty()) {
                // 访问过且非API节点且有throw
                Set<String> nodeExceptions = currentNode.getThrowExceptionInfo().stream()
                        .map(ThrowExceptionInfo::getExceptionQualifiedName)
                        .collect(Collectors.toSet());
                Set<String> nodeUncaughtExceptions = getUncaughtExceptionsOnGraph(graph, currentPath, nodeExceptions);
                UncaughtExceptionInfoThrow uncaughtExceptionInfo = new UncaughtExceptionInfoThrow(currentNode, new ArrayList<>(currentPath), nodeUncaughtExceptions);
                if (!nodeUncaughtExceptions.isEmpty()) {
                    uncaughtExceptions.add(uncaughtExceptionInfo);
                    LOGGER.fine("record archived throw node:\n" + uncaughtExceptionInfo.toString());
                }
            }
        } else {
            // 若是未访问节点
            if (trueName != null) {
                // 未访问过且是API节点
                ArchivedMethodTreeNode archivedMethodTreeNode = null;
                try{
                    archivedMethodTreeNode = new ArchivedMethodTreeNode(currentNode);
                    archivedMethodTreeNode.makeExceptionNameQualified(exceptionCharacteristicManager);
                } catch (SQLException e) {
                    // ignore
                }
                if (archivedMethodTreeNode != null) {
                    Set<String> nodeExceptions = archivedMethodTreeNode.getExceptions().keySet();
                    Set<String> nodeUncaughtExceptions = getUncaughtExceptionsOnGraph(graph, currentPath, nodeExceptions);
                    UncaughtExceptionInfoAPI uncaughtExceptionInfo = new UncaughtExceptionInfoAPI(archivedMethodTreeNode, new ArrayList<>(currentPath), nodeUncaughtExceptions);
                    visitedNodes.put(currentNode, uncaughtExceptionInfo);
                    if (!nodeUncaughtExceptions.isEmpty()) {
                        uncaughtExceptions.add(uncaughtExceptionInfo);
                        LOGGER.fine("record new API node:\n" + uncaughtExceptionInfo.toString());
                    }
                }
            } else {
                // 未访问过且非API节点
                Set<String> nodeExceptions = currentNode.getThrowExceptionInfo().stream()
                        .map(ThrowExceptionInfo::getExceptionQualifiedName)
                        .collect(Collectors.toSet());
                Set<String> nodeUncaughtExceptions = getUncaughtExceptionsOnGraph(graph, currentPath, nodeExceptions);
                UncaughtExceptionInfoThrow uncaughtExceptionInfo = new UncaughtExceptionInfoThrow(currentNode, new ArrayList<>(currentPath), nodeUncaughtExceptions);
                visitedNodes.put(currentNode, uncaughtExceptionInfo);
                if (!nodeUncaughtExceptions.isEmpty()) {
                    uncaughtExceptions.add(uncaughtExceptionInfo);
                    LOGGER.fine("record new throw node:\n" + uncaughtExceptionInfo.toString());
                }
                // 获取当前节点的所有邻居（出边）
                for (MethodCallEdge edge : graph.outgoingEdgesOf(currentNode)) {
                    MethodTreeNode targetNode = graph.getEdgeTarget(edge);
                    // 递归遍历未被访问过的节点
                    if (!currentPath.contains(targetNode)) {
                        dfs(graph, targetNode, currentPath, visitedNodes, uncaughtExceptions);
                    }
                }
            }
        }
        // 完成当前节点的所有出边遍历后，从路径中移除该节点
        currentPath.remove(currentPath.size() - 1);

        return uncaughtExceptions;
    }
    private Set<String> getUncaughtExceptionsOnGraph(
            DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> graph,
            List<MethodTreeNode> nodeRoute,
            Set<String> exceptions) {
        Set<String> uncaughtExceptions = new HashSet<>(exceptions);
        for (int i = 0; i < nodeRoute.size() - 1; i++) {
            MethodCallEdge edge = graph.getEdge(nodeRoute.get(i), nodeRoute.get(i + 1));
            Iterator<String> uncaughtExceptionIterator = uncaughtExceptions.iterator();
            while (uncaughtExceptionIterator.hasNext()) {
                String exception = uncaughtExceptionIterator.next();
                if (exception == null) break;
                if (exceptionCharacteristicManager.isCaught(edge, exception)) {
                    uncaughtExceptionIterator.remove();
                }
            }
        }
        return uncaughtExceptions;
    }
}
