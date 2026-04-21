package org.callTreeGenerator;

import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import org.ExperimentExecutor.ExampleHandler;
import org.ExperimentExecutor.ProjectHandler;
import org.ExperimentExecutor.RepoHandler;
import org.LLMAdvisers.Advisers;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.jdkAnalyzer.ProjectParser;
import org.jgrapht.ext.JGraphXAdapter;
import com.mxgraph.swing.mxGraphComponent;
import org.jgrapht.graph.DefaultDirectedGraph;

import javax.swing.*;
import java.io.IOException;
import java.util.*;
import java.util.logging.*;
import java.util.stream.Collectors;

import static org.ExperimentExecutor.Main.analyzeExperimentDiff;

public class Main {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    public static void main(String[] args) throws IOException{
        // 设置日志级别为 ALL，允许输出更详细的日志
        LOGGER.setLevel(Level.ALL);
        // 创建文件处理器
        Handler fileHandler = new FileHandler("callTreeGenerator_Main.log", true);
        fileHandler.setLevel(Level.ALL); // 设置 Handler 级别
        // 设置日志格式
        fileHandler.setFormatter(new SimpleFormatter());
        // 将 Handler 添加到 Logger
        LOGGER.addHandler(fileHandler);
        LOGGER.setLevel(Level.ALL); // 设置 Logger 级别
//        test();
        mainContent();
    }

    public static String usingExample = "0";
    public static Map<String, List<String>> examples = new HashMap<>();
    private static String projectAbsPath;
    private static String methodQualifiedName;
    static {
         examples.put("0", List.of(new String[]{"D:\\Workspace\\uncaught exception\\example\\projects\\AsciidocFX-86c121547ff81c1a8cc0032334bbb8e85fcb6cd4", "com.kodedu.controller.ApplicationController.processTokens()"}));
         examples.put("1", List.of(new String[]{"D:\\Workspace\\uncaught exception\\example\\projects\\sonarqube-3e326d4a9b46d2dd2fee28d3b658d34f74ab2343", "org.sonar.server.rule.RuleRegistry.findIds(Map<String,String>)"}));
         projectAbsPath = examples.get(usingExample).get(0);
         methodQualifiedName = examples.get(usingExample).get(1);
    }

    private static String file_path4t0 = "src/main/java/com/kodedu/controller/ApplicationController.java";
    private static String methodName4t0 = "processTokens()";

    private static void mainContent(){
        // 解析源代码
        LOGGER.info("start parse");
        ProjectParser projectParser = new ProjectParser(projectAbsPath);
        ExceptionCharacteristicManager exceptionCharacteristicManager = new ExceptionCharacteristicManager();
        TreeGenerator treeGenerator = new TreeGenerator(projectParser, exceptionCharacteristicManager);
//        IMethodBinding targetMethod = treeGenerator.getMethodBindingByQualifiedName(methodQualifiedName);
        IMethodBinding targetMethod = treeGenerator.getMethodBindingByFileAndName(file_path4t0, methodName4t0);

        // 生成调用树
        LOGGER.info("start analyze");
        treeGenerator.methodBinding2graph(targetMethod);
        DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> graph = treeGenerator.getGraph();
        MethodTreeNode root = treeGenerator.getRoot();

        // 根据异常抛出捕获遍历树得到未捕获异常
        LOGGER.info("start find exception");
        GraphNodeTraversal graphNodeTraversal = new GraphNodeTraversal(exceptionCharacteristicManager);
        List<UncaughtExceptionInfo> exceptionResults = graphNodeTraversal.getSuspiciousThrows(graph, root);
        LOGGER.fine("root method:\n" + root.getCode());
        StringBuilder sb = new StringBuilder();
        sb.append("found uncaught exceptions:\n");
        for (UncaughtExceptionInfo exceptionResult: exceptionResults) {
            sb.append(exceptionResult);
        }
        LOGGER.fine(sb.toString());

        // 使用大语言模型处理抛出端
        LOGGER.info("start LLM call on method judgment");
        List<UncaughtExceptionInfo> checkedExceptionResults = exceptionResults.parallelStream()
                .peek(uncaughtExceptionInfo -> {
                    if (uncaughtExceptionInfo instanceof UncaughtExceptionInfoAPI) {
                        Advisers.handleAPI((UncaughtExceptionInfoAPI) uncaughtExceptionInfo);
                    } else if (uncaughtExceptionInfo instanceof UncaughtExceptionInfoThrow) {
                        Advisers.handleThrow((UncaughtExceptionInfoThrow) uncaughtExceptionInfo);
                    }
                })
                .filter(UncaughtExceptionInfo::isContainUncaughtExceptions)
                .collect(Collectors.toList());

        // 使用大语言模型处理捕获端
        LOGGER.info("start LLM call on main question");
        Map<String, String> mainQuestionResult = Advisers.handleMainQuestion(root, checkedExceptionResults);
        System.out.println(mainQuestionResult);

        LOGGER.info("start graph visualize");
        // 使用 JGraphT 提供的 JGraphXAdapter 适配器
        JGraphXAdapter<MethodTreeNode, MethodCallEdge> graphAdapter = new JGraphXAdapter<>(graph);

//        // 自定义节点和边标签
//        Map<MethodTreeNode, String> vertexLabels = new HashMap<>();
//        for (MethodTreeNode node : graph.vertexSet()) {
//            vertexLabels.put(node, node.getQualifiedName());
//        }
//
//        Map<MethodCallEdge, String> edgeLabels = new HashMap<>();
//        for (MethodCallEdge edge : graph.edgeSet()) {
//            edgeLabels.put(edge, edge.toString());
//        }

        graphAdapter.getModel().beginUpdate();
        try {
            // 设置节点的显示标签
            for (MethodTreeNode node : graph.vertexSet()) {
                Object cell = graphAdapter.getVertexToCellMap().get(node); // 获取 mxCell 对象
                if (cell instanceof com.mxgraph.model.mxCell) {
                    ((com.mxgraph.model.mxCell) cell).setValue(node.toString()); // 设置显示的名称
                }
            }

            // 设置边的显示标签
            for (MethodCallEdge edge : graph.edgeSet()) {
                Object cell = graphAdapter.getEdgeToCellMap().get(edge); // 获取 mxCell 对象
                if (cell instanceof com.mxgraph.model.mxCell) {
                    ((com.mxgraph.model.mxCell) cell).setValue(edge.toString()); // 设置显示的边信息
                }
            }
        } finally {
            graphAdapter.getModel().endUpdate();
        }

//        // 布局：使用树形布局
//        mxHierarchicalLayout layout = new mxHierarchicalLayout(graphAdapter);
//        layout.execute(graphAdapter.getDefaultParent());

        // 布局：使用树形布局
        mxHierarchicalLayout layout = new mxHierarchicalLayout(graphAdapter);

// 设置布局参数
        layout.setIntraCellSpacing(20);  // 调整同层节点之间的间距
        layout.setInterRankCellSpacing(100); // 调整层间节点之间的间距

// 执行布局
        layout.execute(graphAdapter.getDefaultParent());

// 调整标签位置以避免重叠
        for (Object cell : graphAdapter.getChildCells(graphAdapter.getDefaultParent())) {
            if (cell instanceof com.mxgraph.model.mxCell) {
                com.mxgraph.model.mxCell mxCell = (com.mxgraph.model.mxCell) cell;

                // 让标签错开，避免重叠
                if (mxCell.isVertex()) {
                    String value = (String) mxCell.getValue();
                    String style = mxCell.getStyle();

                    // 修改样式以调整标签位置
                    String newStyle = style + ";verticalAlign=middle;align=center;labelPosition=center;verticalLabelPosition=bottom;spacing=10;";
                    mxCell.setStyle(newStyle); // 更新样式

                    // 如果需要调整标签偏移位置，可以添加更多的样式配置
                }
            }
        }

        // 创建图形组件
        mxGraphComponent graphComponent = new mxGraphComponent(graphAdapter);
        graphComponent.setConnectable(false); // 禁用用户交互连接
        graphComponent.getViewport().setOpaque(true); // 使背景不透明
        graphComponent.getGraph().setCellsEditable(false); // 禁止编辑节点/边的文本

//        // 设置缩放支持
//        graphComponent.setToolTips(true); // 显示提示信息
//        graphComponent.setZoomPolicy(mxGraphComponent.ZOOM_POLICY_CENTER); // 缩放以窗口中心为基准

        // 创建控制面板用于缩放操作
        JPanel controlPanel = new JPanel();

        JButton zoomInButton = new JButton("Zoom In");
        zoomInButton.addActionListener(e -> graphComponent.zoomIn());
        controlPanel.add(zoomInButton);

        JButton zoomOutButton = new JButton("Zoom Out");
        zoomOutButton.addActionListener(e -> graphComponent.zoomOut());
        controlPanel.add(zoomOutButton);

        JButton resetZoomButton = new JButton("Reset Zoom");
        resetZoomButton.addActionListener(e -> graphComponent.zoomActual());
        controlPanel.add(resetZoomButton);

        // 创建主窗口
        JFrame frame = new JFrame("Zoomable Graph Visualization");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 600);
        frame.setLayout(new BoxLayout(frame.getContentPane(), BoxLayout.Y_AXIS));
        frame.add(graphComponent); // 添加图形组件
        frame.add(controlPanel); // 添加控制面板
        frame.setVisible(true);
    }

    public static void test() {
//        System.out.println(ExceptionCharacteristicManager.isArchived("java.lang.Exception"));
        List<String> list = new ArrayList<>();
        list.add("apple");
        list.add("banana");
        list.add("cherry");
        for (String fruit : list) {
            if (fruit.equals("banana")) {
                list.remove(fruit);  // 在迭代过程中修改集合
            }
        }

    }
}
