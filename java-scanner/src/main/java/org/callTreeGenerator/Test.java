package org.callTreeGenerator;

import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.core.runtime.IPath;
import org.jdkAnalyzer.ProjectParser;
import org.jdkAnalyzer.SQLUtil;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.jgrapht.Graph;
import org.jgrapht.ext.JGraphXAdapter;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;
import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.view.mxGraph;

import javax.swing.*;
import java.io.StringWriter;
@Deprecated
public class Test {
    public static void main(String[] args) {
//        test();
//        graphTest2();
        test3();
    }

    private static void test() {
//        System.out.println("start parse at " + new Date());
//        ProjectParser pjpsr = new ProjectParser("D:\\Workspace\\uncaught exception\\example\\AsciidocFX-86c121547ff81c1a8cc0032334bbb8e85fcb6cd4");
//        List<String> javaFiles = pjpsr.getJavaFiles();
//        String targetFile = "D:\\Workspace\\uncaught exception\\example\\AsciidocFX-86c121547ff81c1a8cc0032334bbb8e85fcb6cd4\\src\\main\\java\\com\\kodedu\\controller\\ApplicationController.java";
//        CompilationUnit cu = pjpsr.getCompilationUnitWithBindings(targetFile);
//        TestVisitor tv = new TestVisitor();
//        cu.accept(tv);
//        String name = pjpsr.IMethodBinding2fileName(tv.mb);
//        System.out.println(tv.mb.toString());
//        System.out.println(name);
        String className = "javax.management.JMRuntimeException";
        List<String> inheritances = new ArrayList<>();
        while (className != null) {
            inheritances.add(className);
            className = SQLUtil.getParentClassName(className);
        }
        System.out.println(inheritances.toString());

        System.out.println("finish");
    }
    private static void graphTest2() {
        // 创建 JGraphT 图对象
        Graph<String, DefaultEdge> graph = new SimpleGraph<>(DefaultEdge.class);

        // 添加节点
        graph.addVertex("A");
        graph.addVertex("B");
        graph.addVertex("C");
        graph.addVertex("D");

        // 添加边
        graph.addEdge("A", "B");
        graph.addEdge("A", "C");
        graph.addEdge("B", "D");
        graph.addEdge("C", "D");

        // 使用 JGraphT 提供的 JGraphXAdapter 将图适配到 JGraphX
        JGraphXAdapter<String, DefaultEdge> graphAdapter = new JGraphXAdapter<>(graph);

        // 创建布局并应用
        mxCircleLayout layout = new mxCircleLayout(graphAdapter);
        layout.execute(graphAdapter.getDefaultParent());

        // 创建图形组件并将其添加到 JFrame 中
        mxGraphComponent graphComponent = new mxGraphComponent(graphAdapter);
        JFrame frame = new JFrame();
        frame.getContentPane().add(graphComponent);
        frame.setSize(800, 600);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);
    }

    private static class ClassA {
        protected int i = 0;
        public String mA1() {
            System.out.println(i);
            return String.valueOf(i);
        }
    }

    private static class ClassB extends ClassA {
        int j = 1;
        public String mA1() {
            System.out.println(j);
            return String.valueOf(j);
        }
    }

    public static void test3() {
        List<ClassA> as = new ArrayList<>();
        ClassB b = new ClassB();
        as.add(b);
        ClassA a1 = as.get(0);
        a1.mA1();
    }

}
