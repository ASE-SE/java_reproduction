package org.ExperimentExecutor;

import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;


/**
 * 用于从已有的本地git仓库中提取特定版本项目至临时路径
 */
public class ProjectHandler {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    public static String TMP_PROJECT_PATH;
    private static ASTParser astParser;
    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            prop.load(fis);
            TMP_PROJECT_PATH = prop.getProperty("TMP_PROJECT_PATH");
        } catch (IOException e) {
            LOGGER.severe("config loading failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static CompilationUnit getMethodCompilationUnit(String methodContent) throws IllegalStateException{
        astParser.setSource(methodContent.toCharArray());
        return (CompilationUnit) (astParser.createAST(null));
    }

    /**
     * generate the whole project at the commit at specified location and return the interested class path
     * @param repositoryPath 仓库绝对路径，其中需要有对应.git文件
     * @param commitHash 子commit对应Hash，本方法不获取该hash对应的文件而是其父
     * @return 生成的所有文件列表
     */
    static public List<String> generateCommitParentHistoryFiles(String repositoryPath, String commitHash) {
        LOGGER.info("Start generate parent history files of commit : " + commitHash);
        try{
            // Open your local repository using JGit
            // Get a repository
            Repository repository = new FileRepositoryBuilder()
                    .setGitDir(new File(repositoryPath+"\\.git"))
                    .build();
            // Create a RevWalk object
            RevWalk walk = new RevWalk(repository);
            // Get the commit
            ObjectId childCommitId = ObjectId.fromString(commitHash);
            RevCommit childCommit = walk.parseCommit(childCommitId);
            // get parent
            String parentHash;
            if (childCommit.getParentCount() > 0) {
                parentHash = childCommit.getParent(0).getName();
            } else {
                LOGGER.warning("Failed to generate parent history files of commit : " + commitHash + " due to no parent found.");
                return null;
            }
            LOGGER.fine("from commit " + commitHash + " get parent commit " + parentHash);
            ObjectId commitId = ObjectId.fromString(parentHash);
            RevCommit commit = walk.parseCommit(commitId);
            // Get the file tree of the commit
            TreeWalk treeWalk = new TreeWalk(repository);
            treeWalk.addTree(commit.getTree());
            treeWalk.setRecursive(true);
            // start generate
            List<String> projectFiles = new ArrayList<>();
//            File folder = new File(TMP_PROJECT_PATH);
//            deleteDirContent(folder);
            cleanFolder(TMP_PROJECT_PATH);
            while (treeWalk.next()){
                // traverse the java files in the commit history
                if (treeWalk.getPathString().endsWith(".java") || treeWalk.getPathString().endsWith(".jar")){
                    // generate file in corresponding path
                    String fileLocation = TMP_PROJECT_PATH + "/" + treeWalk.getPathString();
                    projectFiles.add(fileLocation);
                    File file = new File(fileLocation);
                    file.getParentFile().mkdirs();
                    file.createNewFile();
                    // write file
                    ObjectId objectId = treeWalk.getObjectId(0);
                    ObjectLoader loader = repository.open(objectId);
                    InputStream in = loader.openStream();
                    OutputStream out = new FileOutputStream(file);
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
            }
            LOGGER.info("file generation complete for parent of commit : " + commitHash);
            return projectFiles;
        }
        catch (Exception e){
            LOGGER.warning("Failed to generate parent history files of commit : " + commitHash + " due to " + e.getMessage());
//            e.printStackTrace();
            return null;
        }
    }

    /**
     * generate the whole project at the commit at specified location and return the interested class path
     * @param repositoryPath 仓库绝对路径，其中需要有对应.git文件
     * @param commitHash 子commit对应Hash，本方法不获取该hash对应的文件而是其父
     * @return 生成的所有文件列表
     */
    static public List<String> generateCommitParentHistoryFiles(String repositoryPath, String commitHash, String fileName) {
        LOGGER.info("Start generate parent history files of commit : " + commitHash);
        try{
            // Open your local repository using JGit
            // Get a repository
            Repository repository = new FileRepositoryBuilder()
                    .setGitDir(new File(repositoryPath+"\\.git"))
                    .build();
            // Create a RevWalk object
            RevWalk walk = new RevWalk(repository);
            // Get the commit
            ObjectId childCommitId = ObjectId.fromString(commitHash);
            RevCommit childCommit = walk.parseCommit(childCommitId);
            // get parent
            String parentHash;
            if (childCommit.getParentCount() > 0) {
                parentHash = childCommit.getParent(0).getName();
            } else {
                LOGGER.warning("Failed to generate parent history files of commit : " + commitHash + " due to no parent found.");
                return null;
            }
            LOGGER.fine("from commit " + commitHash + " get parent commit " + parentHash);
            ObjectId commitId = ObjectId.fromString(parentHash);
            RevCommit commit = walk.parseCommit(commitId);
            // Get the file tree of the commit
            TreeWalk treeWalk = new TreeWalk(repository);
            treeWalk.addTree(commit.getTree());
            treeWalk.setRecursive(true);
            // start generate
            List<String> projectFiles = new ArrayList<>();
//            File folder = new File(TMP_PROJECT_PATH);
//            deleteDirContent(folder);
            recreateFolder(fileName);
            while (treeWalk.next()){
                // traverse the java files in the commit history
                if (treeWalk.getPathString().endsWith(".java") || treeWalk.getPathString().endsWith(".jar")){
                    // generate file in corresponding path
                    String fileLocation = TMP_PROJECT_PATH + "\\" + fileName + "/" + treeWalk.getPathString();
                    projectFiles.add(fileLocation);
                    File file = new File(fileLocation);
                    file.getParentFile().mkdirs();
                    file.createNewFile();
                    // write file
                    ObjectId objectId = treeWalk.getObjectId(0);
                    ObjectLoader loader = repository.open(objectId);
                    InputStream in = loader.openStream();
                    OutputStream out = new FileOutputStream(file);
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
            }
            LOGGER.info("file generation complete for parent of commit : " + commitHash);
            return projectFiles;
        }
        catch (Exception e){
            LOGGER.warning("Failed to generate parent history files of commit : " + commitHash + " due to " + e.getMessage());
//            e.printStackTrace();
            return null;
        }
    }

    /**
     * 清空并重新创建指定名称的文件夹
     * @param folderName 文件夹的名称
     * @throws IOException 如果操作失败
     */
    public static void recreateFolder(String folderName) throws IOException {
        deleteFolder(folderName);
        // 创建新的空文件夹
        String project_Path = getFullPathFromName(folderName);
        Path path = Paths.get(project_Path);
        Files.createDirectories(path);
    }

    /**
     * 清空指定名称的文件夹
     * @param folderName 文件夹的绝对路径
     * @throws IOException 如果操作失败
     */
    public static void deleteFolder(String folderName) throws IOException {
        String project_Path = getFullPathFromName(folderName);
        Path path = Paths.get(project_Path);

        // 检查路径是否存在
        if (Files.exists(path)) {
            // 确认是文件夹
            if (!Files.isDirectory(path)) {
                throw new IOException("指定的路径不是一个文件夹: " + project_Path);
            }

            // 递归删除文件夹及其内容
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    public static String getFullPathFromName(String name) {
        return TMP_PROJECT_PATH + "\\" + name;
    }

    public static void sleepSecond(int s) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        // 延迟 5 秒后执行任务（非阻塞）
        executor.schedule(() -> {
            LOGGER.info(String.format("wait %d s", s));
            // 在这里执行资源释放逻辑
        }, s, TimeUnit.SECONDS);

        // 关闭 Executor（如果不需再调度任务）
        executor.shutdown();
    }

    /**
     * 删除目录及其所有内容（包括目录本身）
     * 支持 Windows 长路径（使用 \\?\ 前缀）
     */
    public static void deleteDir(File dir) {
        if (!dir.exists()) return;

        try {
            Path path = toLongPath(dir); // 转换为长路径格式（Windows）
            Files.walk(path)
                    .sorted(Comparator.reverseOrder()) // 先删除子文件，再删父目录
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to delete: " + p, e);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete directory: " + dir, e);
        }
    }

    /**
     * 仅删除目录内容，保留目录本身
     */
    public static void deleteDirContent(File dir) {
        if (!dir.isDirectory()) return;

        File[] children = dir.listFiles();
        if (children == null) return;

        for (File child : children) {
            try {
                Path childPath = toLongPath(child);
                if (Files.isSymbolicLink(childPath)) {
                    Files.delete(childPath); // 仅删除符号链接本身
                } else {
                    deleteDir(child); // 递归删除子目录或文件
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete: " + child, e);
            }
        }
    }

    /**
     * 在 Windows 上转换为长路径格式（\\?\ 前缀）
     * 非 Windows 系统直接返回原始路径
     */
    private static Path toLongPath(File file) {
        String path = file.getAbsolutePath();

        // 仅 Windows 需要处理长路径
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            if (path.startsWith("\\\\")) {
                // UNC 路径（\\server\share\...）转换为 \\?\UNC\server\share\...
                return Paths.get("\\\\?\\UNC\\" + path.substring(2));
            } else {
                // 普通路径（C:\dir\...）转换为 \\?\C:\dir\...
                return Paths.get("\\\\?\\" + path);
            }
        } else {
            // 非 Windows 系统直接返回
            return file.toPath();
        }
    }

    /**
     * 清空文件夹内容但保留文件夹本身
     * @param folderPath 要清空的文件夹路径
     * @throws IOException 如果删除过程中出现错误
     */
    public static void cleanFolder(String folderPath) throws IOException {
        Path path = Paths.get(folderPath);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("文件夹不存在: " + folderPath);
        }

        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("路径不是文件夹: " + folderPath);
        }

        // 使用Files.walkFileTree递归删除文件夹内容
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.equals(path)) {  // 确保不删除根文件夹
                    Files.delete(file);    // 删除文件
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc; // 如果访问目录时出错，抛出异常
                }
                if (!dir.equals(path)) {  // 确保不删除根文件夹
                    Files.delete(dir);   // 删除子目录
                }
                return FileVisitResult.CONTINUE;
            }
        });
        LOGGER.info("clean folder " + folderPath + " success");
    }
}
