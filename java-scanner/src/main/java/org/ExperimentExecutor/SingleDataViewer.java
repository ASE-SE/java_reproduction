package org.ExperimentExecutor;

import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.swing.mxGraphComponent;
import org.LLMAdvisers.Advisers;
import org.callTreeGenerator.*;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.jdkAnalyzer.ProjectParser;
import org.jgrapht.ext.JGraphXAdapter;
import org.jgrapht.graph.DefaultDirectedGraph;

import javax.swing.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.*;
import java.util.stream.Collectors;

import static org.ExperimentExecutor.Main.analyzeExperimentDiff;

public class SingleDataViewer {
    private static final Logger LOGGER = Logger.getLogger("MAIN");

    private static String EXPERIMENT_MARK;
    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            prop.load(fis);
            EXPERIMENT_MARK = prop.getProperty("EXPERIMENT_MARK");
        } catch (IOException e) {
            LOGGER.severe("config loading failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    public static void main(String[] args) throws IOException {
        // 设置日志级别为 ALL，允许输出更详细的日志
        LOGGER.setLevel(Level.ALL);
        // 创建文件处理器
        Handler fileHandler = new FileHandler("singleDataViewer_Main.log", true);
        fileHandler.setLevel(Level.ALL); // 设置 Handler 级别
        // 设置日志格式
        fileHandler.setFormatter(new SimpleFormatter());
        // 将 Handler 添加到 Logger
        LOGGER.addHandler(fileHandler);
        LOGGER.setLevel(Level.ALL); // 设置 Logger 级别
//        test();
        mainContent();
    }

    public static void mainContent() {
        ExampleHandler.ExampleData data = ExampleHandler.getDataByKeys("SonarSource/sonarqube",
                "<Patch - https://github.com/SonarSource/sonarqube/commit/f09de6aa56a805127e9f3c169b9fab7d50cd48fc>",
                "getAuthenticationStatusPage(HttpServletRequest,HttpServletResponse)");
        LOGGER.info("got data: \n" + data.toString());
        processExample(data);
    }


    private static void processExample(ExampleHandler.ExampleData data) {
        String repoID = data.getRepo_id();
        String commitHash = RepoHandler.getCommitHashFromPatch(data.getPatch());
        // 生成待分析项目
        LOGGER.info("start project generation on example: " + data.getMethodName());
        List<String> generatedFiles = ProjectHandler.generateCommitParentHistoryFiles(
                RepoHandler.getRepository(repoID),
                commitHash);
        if (generatedFiles == null) {
            LOGGER.warning("data with repo_id: " + repoID +" and commitHash: " + commitHash + "and name: " + data.getMethodName() + " process failed");
            return;
        }

        // 解析源代码
        LOGGER.info("start parse on example: " + data.getMethodName());
        ProjectParser projectParser = new ProjectParser(ProjectHandler.TMP_PROJECT_PATH);
        ExceptionCharacteristicManager exceptionCharacteristicManager = new ExceptionCharacteristicManager();

        // 获取正负例
        TreeGenerator treeGenerator = new TreeGenerator(projectParser, exceptionCharacteristicManager); // 用例对应TreeGenerator
        IMethodBinding targetMethod = treeGenerator.getMethodBindingByFileAndName(data.getFile_path(), data.getMethodName()); // 用例对应method，首个（0）为正例，剩余为负例
        ExampleHandler.ExampleData exampleData = new ExampleHandler.ExampleData(data); // 实验数据，附标签;
        exampleData.setLabel(1);
        // 进行实验
        LOGGER.info("start experiment on example: " + data.getMethodName());
        singleExperiment(treeGenerator, targetMethod, exampleData, exceptionCharacteristicManager);
        LOGGER.info("finish example: " + data.getMethodName());
    }
    private static void singleExperiment(TreeGenerator treeGenerator, IMethodBinding targetMethod, ExampleHandler.ExampleData data, ExceptionCharacteristicManager exceptionCharacteristicManager) {
        // 生成调用树
        LOGGER.info("start analyze： " + targetMethod.getName());
        treeGenerator.methodBinding2graph(targetMethod);
        DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> graph = treeGenerator.getGraph();
        MethodTreeNode root = treeGenerator.getRoot();
        if (data.getLabel() == 0) {
            data.setMethodBefore(root.getCode());
            data.setMethodAfter(root.getCode());
        }

        // 根据异常抛出捕获遍历树得到未捕获异常
        LOGGER.info("start find exception: " + targetMethod.getName());
        GraphNodeTraversal graphNodeTraversal = new GraphNodeTraversal(exceptionCharacteristicManager);
        List<UncaughtExceptionInfo> exceptionResults = graphNodeTraversal.getSuspiciousThrows(graph, root);
        Integer layer = 10;
        while(exceptionResults.size() > 30 && layer > 3) {
            Integer finalLayer = layer;
            exceptionResults.removeIf(info -> info.getNodeRoute().size() - 1 > finalLayer);
            layer --;
        }
        LOGGER.fine("root method:\n" + root.getCode());
        StringBuilder sb = new StringBuilder();
        sb.append("found uncaught exceptions:\n");
        for (UncaughtExceptionInfo exceptionResult : exceptionResults) {
            sb.append(exceptionResult);
        }
        LOGGER.fine(sb.toString());

        // 使用大语言模型处理抛出端
        Advisers advisers = new Advisers(data);
        LOGGER.info("start LLM call on method judgment");
        List<UncaughtExceptionInfo> checkedExceptionResults = exceptionResults.parallelStream()
                .peek(uncaughtExceptionInfo -> {
                    advisers.handleUncaughtExceptionInfo(uncaughtExceptionInfo, EXPERIMENT_MARK);
                })
                .filter(UncaughtExceptionInfo::isContainUncaughtExceptions)
                .collect(Collectors.toList());
        if (checkedExceptionResults.isEmpty()) {
            checkedExceptionResults = exceptionResults;
        }

        // 使用大语言模型处理捕获端
        LOGGER.info("start LLM call on main question");
        Map<String, String> mainQuestionResult = advisers.handleMainQuestion(root, checkedExceptionResults, EXPERIMENT_MARK);
        if (mainQuestionResult == null) {
            data.setLabel(-1);
        } else {
            data.setMethodResult(mainQuestionResult.get("result"));
        }

        ExampleHandler.ExampleData processed_data = analyzeExperimentDiff(data);

        LOGGER.info("origin method: \n" + processed_data.getMethodBefore() +
                "\nafter method: \n" + processed_data.getMethodAfter() +
                "\nresult method: \n" + processed_data.getMethodResult() +
                "\ntrue exceptions: \n" + processed_data.getExceptionTypes().toString() +
                "\n result exceptions: \n" + processed_data.getResultExceptionTypes().toString());

        LOGGER.info("finish analyze： " + targetMethod.getName());

        LOGGER.info("start graph visualize");
        // 使用 JGraphT 提供的 JGraphXAdapter 适配器
        JGraphXAdapter<MethodTreeNode, MethodCallEdge> graphAdapter = new JGraphXAdapter<>(graph);

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
}
