package org.callTreeGenerator;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.jdkAnalyzer.ProjectParser;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

public class TreeGenerator {
    private static final Logger LOGGER = Logger.getLogger(TreeGenerator.class.getName());
    private DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> graph = new DefaultDirectedGraph<>(MethodCallEdge.class);
    private ProjectParser projectParser;
    private ExceptionCharacteristicManager exceptionCharacteristicManager;
    private MethodTreeNode root;
    private Map<String, MethodTreeNode> methodNameNodeMap = new HashMap<>();
    private List<IMethodBinding> negativeMethods = null; // 由visitor得到的负例

    public TreeGenerator(ProjectParser projectParser, ExceptionCharacteristicManager exceptionCharacteristicManager) {
        this.projectParser = projectParser;
        this.exceptionCharacteristicManager = exceptionCharacteristicManager;
    }

    public Graph<MethodTreeNode, MethodCallEdge> methodBinding2graph(IMethodBinding methodBinding) {
        graph = new DefaultDirectedGraph<>(MethodCallEdge.class);
        root = traverseMethods(methodBinding, null, new ArrayList<>(), 0);
        return graph;
    }

    private MethodTreeNode traverseMethods(IMethodBinding binding, MethodTreeNode parent, List<TryCatchNestingInfo> callInfos, int depth) {
        MethodTreeNode methodTreeNode = new MethodTreeNode(binding);
        if (methodNameNodeMap.containsKey(methodTreeNode.getQualifiedName())) {
            // 若待访问方法已经被记录到树中
            // 图构建，边的添加（添加当前方法与父方法之间的边）
            MethodCallEdge nowEdge = new MethodCallEdge();
            for (TryCatchNestingInfo callInfo: callInfos) {
                nowEdge.addMethodCallInfo(callInfo);
            }
            try {
                graph.addEdge(parent, methodNameNodeMap.get(methodTreeNode.getQualifiedName()), nowEdge);
            } catch (NullPointerException ignore) {
                // ignore
                LOGGER.warning("unexpected exception in traverse methods: " + binding.getName() + ", " + ignore.getMessage());
            }

            return methodNameNodeMap.get(methodTreeNode.getQualifiedName());
        } else {
            // 若待访问方法未被记录到树中
            String javaFilePath = projectParser.IMethodBinding2fileName(binding);
            if (javaFilePath == null) {
                // 若无法获取定义java文件
                // 图构建，节点的记录和边的添加（添加当前方法与父方法之间的边）
                methodNameNodeMap.put(methodTreeNode.getQualifiedName(), methodTreeNode);
                graph.addVertex(methodTreeNode);
                MethodCallEdge nowEdge = new MethodCallEdge();
                for (TryCatchNestingInfo callInfo: callInfos) {
                    nowEdge.addMethodCallInfo(callInfo);
                }
                try {
                    graph.addEdge(parent, methodTreeNode, nowEdge);
                } catch (NullPointerException ignore) {
                    // ignore
                    LOGGER.warning("unexpected exception in traverse methods: " + binding.getName() + ", " + ignore.getMessage());
                }
            } else {
                // 若获取到定义java文件
                // 使用visitor对其进行AST遍历，得到并记录结果
                CompilationUnit cu = projectParser.getCompilationUnitWithBindings(javaFilePath);
                MethodDeclarationVisitor mdv = new MethodDeclarationVisitor(binding);
                cu.accept(mdv);
                methodTreeNode.setNode(mdv.getMethodDeclarationNode());
                methodTreeNode.setThrowExceptionInfo(mdv.getThrowExceptionInfos());
                exceptionCharacteristicManager.updateLocalExceptions(mdv.getLocalExceptions());
                // 图构建，节点的记录和边的添加（添加当前方法与父方法之间的边）
                methodNameNodeMap.put(methodTreeNode.getQualifiedName(), methodTreeNode);
                graph.addVertex(methodTreeNode);
                if (parent != null) {
                    MethodCallEdge nowEdge = new MethodCallEdge();
                    for (TryCatchNestingInfo callInfo: callInfos) {
                        nowEdge.addMethodCallInfo(callInfo);
                    }
                    try {
                        graph.addEdge(parent, methodTreeNode, nowEdge);
                    } catch (NullPointerException ignore) {
                        // ignore
                        LOGGER.warning("unexpected exception in traverse methods: " + binding.getName() + ", " + ignore.getMessage());
                    }
                }
                // 递归深度优先遍历方法调用
                if (depth <= 12) {
                    for (IMethodBinding methodBinding: mdv.getBindingLeafs().keySet()) {
                        traverseMethods(methodBinding, methodTreeNode, mdv.getBindingLeafs().get(methodBinding), depth + 1);
                    }
                }
            }
            return methodTreeNode;
        }
    }

    /**
     * 参考完整方法全名，在软件系统中定位对应方法Binding
     * @param qualifiedName
     * @return 对应方法的Binding
     */
    public IMethodBinding getMethodBindingByQualifiedName(String qualifiedName) {
        int lastIndex = qualifiedName.lastIndexOf(".");
        if (lastIndex == -1) {
            return null;
        }
        String fileName = qualifiedName.substring(0, lastIndex).replace('.', File.separatorChar) + ".java";
        for (String filePath: projectParser.getJavaFiles()) {
            if (filePath.endsWith(fileName)) {
                CompilationUnit cu = projectParser.getCompilationUnitWithBindings(filePath);
                MethodFinderVisitor mfv = new MethodFinderVisitor(qualifiedName);
                cu.accept(mfv);
                negativeMethods = mfv.getAllMethodBinding();
                return mfv.getTargetBinding();
            }
        }
        return null;
    }

    /**
     * 参考文件名+方法简单名，在软件系统中定位对应方法Binding
     * @param filePath 文件完整相对路径
     * @param name 方法简单名，需附带参数名
     * @return 对应方法的Binding
     */
    public IMethodBinding getMethodBindingByFileAndName(String filePath, String name) {
        String fileRelativePath = filePath.substring(filePath.indexOf("/")).replace('/', File.separatorChar);
        for (String fileAbsPath: projectParser.getJavaFiles()) {
            if (fileAbsPath.endsWith(fileRelativePath)) {
                CompilationUnit cu = projectParser.getCompilationUnitWithBindings(fileAbsPath);
                MethodFinderVisitor mfv = new MethodFinderVisitor(name);
                cu.accept(mfv);
                negativeMethods = mfv.getAllMethodBinding();
                return mfv.getTargetBinding();
            }
        }
        return null;
    }

    public DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> getGraph() {
        return graph;
    }

    public MethodTreeNode getRoot() {
        return root;
    }

    public List<IMethodBinding> getNegativeMethods() {
        return negativeMethods;
    }
}
