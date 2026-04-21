package org.ExperimentExecutor;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;


/**
 * 用于管理基于index的项目仓库，包括目录的读取和匹配等处理、本地仓库的管理、仓库的克隆等
 */
public class RepoHandler {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    public static String REPOSITORY_INDEX_PATH;
    public static String REPOSITORY_DATA_PATH;
    private static String USER_NAME;
    private static String USER_TOKEN;
    private static List<Map<String, Object>> repositoryIndex = null;
    private static Map<String, String> cloneUrlMap = new HashMap<>();

    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            prop.load(fis);
            USER_NAME = prop.getProperty("GITHUB_USERNAME");
            USER_TOKEN = prop.getProperty("GITHUB_TOKEN");
            REPOSITORY_INDEX_PATH = prop.getProperty("REPOSITORY_INDEX_FILE");
            REPOSITORY_DATA_PATH = prop.getProperty("REPOSITORY_DATA_PATH");
        } catch (IOException e) {
            LOGGER.severe("config loading failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
        // 创建 Gson 实例
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(REPOSITORY_INDEX_PATH)) {
            Type type = new TypeToken<List<Map<String, Object>>>() {}.getType();
            repositoryIndex = gson.fromJson(reader, type);
            LOGGER.info("loaded repository index from: " + REPOSITORY_INDEX_PATH);
        } catch (IOException e) {
            LOGGER.severe("repository index load error: " + e.getMessage());
            throw new RuntimeException(e);
        }
        for (Map<String, Object> repo: repositoryIndex) {
            cloneUrlMap.put((String) repo.get("full_name"), (String) repo.get("clone_url"));
        }
//        System.setProperty("http.proxyHost", "127.0.0.1");
//        System.setProperty("http.prixyPort", "7890");
//        System.setProperty("https.proxyHost", "127.0.0.1");
//        System.setProperty("https.prixyPort", "7890");
    }

    /**
     * 根据full name获取对应仓库，若不在本地会进行clone，返回仓库本地绝对路径
     * @param name github上仓库全名，必须与REPOSITORY_INDEX_PATH对应索引中记载的一项匹配
     * @return 本地仓库绝对路径，若发生异常返回null
     */
    public static String getRepository(String name) {
        LOGGER.info("getting repository: " + name);
        // 确认repo中有对应记录
        if (!cloneUrlMap.containsKey(name)) {
            LOGGER.warning("index does not contain repository: " + name);
            return null;
        }
        String folderName = name.replaceAll("/", "_");
        String localPath = REPOSITORY_DATA_PATH + File.separator + folderName;
        // 确认若已存在本地仓库则直接返回路径
        if (checkFolderExists(REPOSITORY_DATA_PATH, folderName)) {
            LOGGER.info("local repository found: " + localPath);
            return localPath;
        }
        // clone仓库
        LOGGER.info("local repository for " + name + " not found, start clone from " + cloneUrlMap.get(name));
        try {
            // 克隆仓库
//            CloneProgressModitor progressModitor = new CloneProgressModitor();
            Git git = Git.cloneRepository()
                    .setURI(cloneUrlMap.get(name)) // 设置远程仓库 URL
                    .setDirectory(new File(localPath)) // 设置本地存储路径
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(USER_NAME, USER_TOKEN)) // 设置认证信息
//                    .setProgressMonitor(progressModitor)
                    .call(); // 执行克隆操作
            LOGGER.info("Repository cloned successfully to: " + localPath);
            // 关闭 Git 对象
            git.close();
            return localPath;
        } catch (GitAPIException e) {
            LOGGER.warning("Error cloning repository " + name + " : " + e.getMessage());
            File trash = new File(localPath);
            if (trash.exists()) {
                ProjectHandler.deleteDir(trash);
            }
        }
        return null;
    }

    /**
     * 检查指定路径下是否存在特定名称的文件夹
     *
     * @param directoryPath 目录路径
     * @param folderName    文件夹名称
     * @return 如果文件夹存在返回 true，否则返回 false
     */
    public static boolean checkFolderExists(String directoryPath, String folderName) {
        File directory = new File(directoryPath);
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory() && file.getName().equals(folderName)) {
                        return true;
                    }
                }
            }
        } else {
            LOGGER.warning("path '" + directoryPath + "' does not exist or not a directory");
        }
        return false;
    }

    public static String getCommitHashFromPatch(String patch) throws IllegalArgumentException {
        if (patch.contains("/commit/")) {
            return patch.split("/commit/")[1].substring(0, 40);
        }
        throw new IllegalArgumentException(patch + " parse commitHash error");
    }

}

class CloneProgressModitor implements ProgressMonitor {

    private static final Logger LOGGER = Logger.getLogger("MAIN");
    @Override
    public void start(int totalTasks) {
        LOGGER.fine("start clone, total tasks: " + totalTasks);
    }

    @Override
    public void beginTask(String title, int totalWork) {
        LOGGER.fine("start task: " + title + ", total work: " + totalWork);
    }

    @Override
    public void update(int completed) {
        System.out.println("processed: " + completed);
    }

    @Override
    public void endTask() {
        System.out.println("task complete.");
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public void showDuration(boolean b) {

    }
}

