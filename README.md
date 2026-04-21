# Java 异常捕获硕士论文复现工具包说明

## 1. 项目概述

复现硕士论文 **《Automated Detection and Fixing of Uncaught Runtime Exceptions in Java》** 的实验工具包。

核心实现位于 `java-scanner/` 目录，其余目录主要用于保存：

- 实验输入数据
- 实验输出结果
- 实验中间记录
- 基线方法结果
- 已构建好的 SQLite 知识库

## 2. 工作区结构

- `java-scanner/`
  - 核心 Java 工程。
  - 包含知识库构建、静态分析、LLM 调用、实验执行等主要实现。
- `data/`
  - 实验数据集、实验结果、Notebook、中间过程记录。
- `baselines/`
  - 基线方法相关结果和记录。
- `sqlite-tools-osx-x64-3500100/`
  - 工作区中附带的 SQLite 工具。
- `java异常捕获硕士论文.pdf`
  - 对应论文原文。

## 3. 核心模块说明

### 3.1 `org.jdkAnalyzer`

作用：从 OpenJDK 源码中构建异常/API 知识库。

主要职责：

- 用 Eclipse JDT 解析 JDK 源码
- 提取类、异常、方法、调用关系、throw 信息、注释
- 写入 SQLite 数据库

关键文件：

- `java-scanner/src/main/java/org/jdkAnalyzer/Main.java`
- `java-scanner/src/main/java/org/jdkAnalyzer/ThreeInOneVisitor.java`
- `java-scanner/src/main/java/org/jdkAnalyzer/SQLUtil.java`

产物：

- `java-scanner/analyzeData1.db`

### 3.2 `org.callTreeGenerator`

作用：对待分析方法进行静态分析，并构建方法调用图。

主要职责：

- 在目标项目中定位待分析方法
- 从该方法出发递归构建调用图
- 在图的节点和边上记录异常相关信息
- 判断哪些异常在传播路径上没有被 catch

关键文件：

- `java-scanner/src/main/java/org/callTreeGenerator/TreeGenerator.java`
- `java-scanner/src/main/java/org/callTreeGenerator/GraphNodeTraversal.java`
- `java-scanner/src/main/java/org/callTreeGenerator/ExceptionCharacteristicManager.java`

### 3.3 `org.LLMAdvisers`

作用：利用大语言模型做语义判断，并生成异常修复代码。

主要职责：

- 判断 API 文档中声明的异常在当前上下文中是否真的可能暴露
- 判断本地 `throw` 抛出的运行时异常是否应由调用方捕获
- 结合候选异常和目标方法生成最终修复结果

关键文件：

- `java-scanner/src/main/java/org/LLMAdvisers/Advisers.java`
- `java-scanner/src/main/java/org/LLMAdvisers/LLMApiCaller.java`
- `java-scanner/src/main/java/org/LLMAdvisers/PromptTemplate.json`

### 3.4 `org.ExperimentExecutor`

作用：串联整套实验流程。

主要职责：

- 从 JSON 中读取实验样本
- 获取目标仓库
- 回溯到目标提交的父版本代码
- 执行静态分析与 LLM 推理
- 输出实验结果

关键文件：

- `java-scanner/src/main/java/org/ExperimentExecutor/Main.java`
- `java-scanner/src/main/java/org/ExperimentExecutor/ExampleHandler.java`
- `java-scanner/src/main/java/org/ExperimentExecutor/ProjectHandler.java`
- `java-scanner/src/main/java/org/ExperimentExecutor/RepoHandler.java`
- `java-scanner/src/main/java/org/ExperimentExecutor/SingleDataViewer.java`
- `java-scanner/src/main/java/org/ExperimentExecutor/LLMExperiment.java`

### 3.5 `org.ASTAnalyzer`

作用：比较模型生成结果与原始方法之间的差异，提取 try-catch 修改信息。

主要职责：

- 解析修改前后的方法代码
- 检测新增的 try-catch 结构
- 为后续评估提供结构化差异信息

关键文件：

- `java-scanner/src/main/java/org/ASTAnalyzer/MethodDiffAnalyzer.java`

## 4. 与论文方法部分的对应关系

这套实现整体上和论文第 3 章到第 4 章的方法流程是对应的，大致可以分成以下几个阶段。

### 阶段 1：构建 API / 异常知识库

论文方法：

- 分析 OpenJDK
- 收集异常继承关系、API 方法信息、throw 信息、注释文档

代码对应：

- `org.jdkAnalyzer`
- SQLite 数据库 `analyzeData1.db`

### 阶段 2：为目标方法构建调用图

论文方法：

- 对输入项目做静态解析
- 从目标方法出发构建调用图
- 记录调用点所在的 try/catch 语境

代码对应：

- `org.callTreeGenerator.TreeGenerator`
- `org.jdkAnalyzer.ProjectParser`

### 阶段 3：检测可疑的未捕获运行时异常

论文方法：

- 利用知识库识别 API 可能抛出的异常
- 分析本地 `throw` 抛出的异常
- 沿调用图传播异常
- 去掉路径上已被 catch 的异常

代码对应：

- `org.callTreeGenerator.GraphNodeTraversal`
- `org.callTreeGenerator.ExceptionCharacteristicManager`

### 阶段 4：用 LLM 做语义判断和修复生成

论文方法：

- 判断候选异常在上下文中是否真实相关
- 判断 `throw` 抛出的异常是否应由外层处理
- 生成修复后的方法代码

代码对应：

- `org.LLMAdvisers.Advisers`
- `org.LLMAdvisers.PromptTemplate.json`
- `org.LLMAdvisers.LLMApiCaller`

### 阶段 5：比较生成修复与真实修复

论文方法：

- 评估模型输出是否引入了合适的 catch 逻辑

代码对应：

- `org.ASTAnalyzer.MethodDiffAnalyzer`
- `org.ExperimentExecutor.Main.analyzeExperimentDiff(...)`

## 5. 运行前准备

在运行之前，至少需要准备以下内容：

- Java
  - `pom.xml` 目前配置的是 Java 11。
- Maven
  - 用于编译和打包项目。
- 可用的大模型 API
  - 需要配置 URL、模型名和 API Key。
- 本地仓库存储目录
  - 实验执行时依赖本地仓库，或者需要具备自动 clone 的条件。
- 仓库索引 JSON
  - 用于根据仓库名找到 clone URL。
- 可写的临时目录
  - 用来生成目标提交父版本的项目代码。

## 6. 配置说明

主配置文件：

- `java-scanner/config.properties`

关键字段：

- `TMP_PROJECT_PATH`
- `EXPERIMENT_INTERMEDIATE_PROCESS_DATA_PATH`
- `REPOSITORY_INDEX_FILE`
- `REPOSITORY_DATA_PATH`
- `EXPERIMENT_MARK`
- `EXPERIMENT_SOURCE`
- `EXPERIMENT_TARGET`
- `LLM_API_KEY`
- `LLM_API_URL`
- `LLM_MODEL_NAME`
- `GITHUB_USERNAME`
- `GITHUB_TOKEN`

辅助说明文件：

- `java-scanner/config info.txt`

该文件中对每个配置项的含义做了简单说明。

## 7. 怎么运行

### 7.1 先跑单条样本

这是最适合初次理解整个流程的方式。

入口类：

- `org.ExperimentExecutor.SingleDataViewer`

它会做的事情：

- 选取一个样本
- 生成目标提交父版本代码
- 解析项目
- 构建调用图
- 检测可疑未捕获异常
- 调用 LLM
- 计算修复差异
- 显示调用图可视化窗口

推荐步骤：

1. 修改 `java-scanner/config.properties`
2. 确认 `java-scanner/analyzeData1.db` 存在且可读
3. 编译项目
4. 在 IDE 中直接运行 `org.ExperimentExecutor.SingleDataViewer`

### 7.2 跑主实验流程

入口类：

- `org.ExperimentExecutor.Main`

它会做的事情：

- 从 `EXPERIMENT_SOURCE` 读取样本
- 跳过已经写入 `EXPERIMENT_TARGET` 的样本
- 对未处理样本执行完整流程
- 持续将结果写回输出文件

### 7.3 跑 LLM-only 基线

入口类：

- `org.ExperimentExecutor.LLMExperiment`

它会做的事情：

- 不经过静态分析引导筛选
- 直接把方法交给 LLM 生成修复
- 作为基线结果输出

### 7.4 重建 JDK 知识库

入口类：

- `org.jdkAnalyzer.Main`

它会做的事情：

- 解析本地 OpenJDK 源码
- 重新构建 SQLite 知识库

如果只是复现论文实验，通常优先使用已有的 `analyzeData1.db`，不必重新生成。

## 8. 示例构建命令

进入项目目录：

```bash
cd /path/to/java-scanner
mvn -DskipTests compile
```

如果需要完整依赖布局后再运行：

```bash
cd /path/to/java-scanner
mvn -DskipTests package dependency:copy-dependencies
java -cp "target/classes:target/dependency/*" org.ExperimentExecutor.Main
```

说明：

- macOS / Linux 下 classpath 分隔符通常是 `:`
- Windows 下 classpath 分隔符通常是 `;`

## 9. 当前工具包存在的主要问题

### 9.1 存在大量 Windows 路径硬编码

这是当前复现中最大的实际问题。

问题位置包括但不限于：

- `java-scanner/config.properties`
- `org.ExperimentExecutor.ProjectHandler`
- `org.ExperimentExecutor.RepoHandler`
- `org.jdkAnalyzer.Main`
- `TreeGenerator` 中部分路径匹配逻辑

如果你在 macOS 或 Linux 上运行，需要适配路径分隔符和绝对路径。

### 9.2 没有完整的开箱即用文档

当前工程中没有原始 `README`、没有启动脚本、也没有 Maven Wrapper。

### 9.3 强依赖外部资源

这套工具依赖以下外部条件：

- 仓库索引 JSON
- 本地仓库集合
- GitHub clone 权限
- 可用的大模型服务

缺少其中任一项，都可能导致流程无法完整运行。

### 9.4 配置文件中直接保存密钥不安全

目前的配置方式把 API Key 和 GitHub Token 直接写在 `config.properties` 里，这种做法不安全。

更合理的做法：

- 把密钥改为环境变量读取
- 本地配置文件只保留路径和非敏感信息

### 9.5 部分路径没有完全配置化

不是所有路径都从 `config.properties` 中读取，部分结果文件路径仍然写死在 Java 源码中，例如 `ExampleHandler` 中的常量路径。

## 10. 推荐的复现顺序

如果你是第一次接手这套工程，建议按下面顺序进行：

1. 先阅读论文的方法部分
2. 确认 `java-scanner/analyzeData1.db` 已存在
3. 修改 `java-scanner/config.properties`
4. 先跑通 `SingleDataViewer`
5. 单样本跑通后再尝试 `ExperimentExecutor.Main`
6. 只有在确实需要时再去重建 JDK 知识库

## 11. 当前工作区中已经包含的内容

当前工作区里至少已经有以下内容：

- 一个预构建的 SQLite 知识库
- 多份实验结果 JSON
- 实验中间记录
- baseline 相关目录

这意味着你不一定需要从零开始重新生成所有实验产物。

## 12. 补充说明

- 论文 PDF 在部分环境下做文本提取时可能会出现乱码，这和字体编码有关，但不影响确认论文的英文摘要、章节结构和总体方法流程。
- 从工程状态来看，这更像作者内部实验代码，而不是经过充分整理后的公共复现版本。

