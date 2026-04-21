package org.callTreeGenerator;

import org.jdkAnalyzer.SQLUtil;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 提供已记录Exception属性查找的类
 * 对于一个项目进行分析期间应创建并复用一个ExceptionCharacteristicManager实例
 * 功能包括：
 *   exception是否是Runtime的查找
 *   exception的继承关系查找
 *   由simpleName获取qualifiedName
 */
public class ExceptionCharacteristicManager {
    public static final String THROWABLE = "java.lang.Throwable";
    public static final String RUNTIME_EXCEPTION = "java.lang.RuntimeException";
    // archivedExceptions<异常全名,异常父类全名>
    private static final Map<String, String> archivedExceptions = new HashMap<>();
    private static final Map<String, String> archivedQualifiedNameMap = new HashMap<>();

    static {
        try (ResultSet rs = SQLUtil.executeSelect("SELECT qualified_name, parent FROM exceptions");) {
            if (rs == null) {
                throw new RuntimeException("exception data initialize failed");
            }
            while (rs.next()) {
                archivedExceptions.put(rs.getString("qualified_name"), rs.getString("parent"));
            }
        } catch (SQLException ignore) {
            throw new RuntimeException("exception data initialize failed");
        }
        for (String qualifiedName: archivedExceptions.keySet()) {
            if (qualifiedName.contains(".")) {
                archivedQualifiedNameMap.put(getSubstringAfterLastDot(qualifiedName), qualifiedName);
            }
        }
    }

    /**
     * 当接收的exception全名存在于数据库中时返回true，否则返回false
     */
    public static boolean isArchived(String qualifiedName) {
        return (archivedExceptions.containsKey(qualifiedName));
    }

    public static String getSubstringAfterLastDot(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        int lastDotIndex = input.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return input.substring(lastDotIndex + 1);
    }

    public static boolean isArchivedRuntime(String simpleName) {
        String qualifiedName = getArchivedQualifiedName(simpleName);
        return isArchivedQualifiedRuntime(qualifiedName);
    }
    public static boolean isArchivedQualifiedRuntime(String qualifiedName) {
        if (qualifiedName == null) {
            return false;
        }
        if (qualifiedName.equals(RUNTIME_EXCEPTION)) {
            return true;
        }
        if (qualifiedName.equals(THROWABLE)) {
            return false;
        }
        return isArchivedQualifiedRuntime(archivedExceptions.get(qualifiedName));
    }

    public static String getArchivedQualifiedName(String simpleName) {
        String mappedName = archivedQualifiedNameMap.get(simpleName);
        if (mappedName == null) {
            return simpleName;
        } else {
            return mappedName;
        }
    }

    private Map<String, String> localExceptions = new HashMap<>(archivedExceptions);
    private Map<String, String> qualifiedNameMap = new HashMap<>();

    public ExceptionCharacteristicManager() {
        updateQualifiedNameMap(localExceptions.keySet());
    }

    public void updateLocalExceptions(Map<String, String> localExceptions) {
        this.localExceptions.putAll(localExceptions);
        updateQualifiedNameMap(localExceptions.keySet());
    }

    private void updateQualifiedNameMap(Set<String> qualifiedNames) {
        for (String qualifiedName: qualifiedNames) {
            if (qualifiedName.contains(".")) {
                this.qualifiedNameMap.put(getSubstringAfterLastDot(qualifiedName), qualifiedName);
            }
        }
    }

    /**
     * 当接收的exception全名被程序记录或存在于数据库中时返回true，否则返回false
     */
    public boolean isKnown(String qualifiedName) {
        return (localExceptions.containsKey(qualifiedName));
    }

    /**
     * 判断1是否是2的子类
     * 若1是2的子类，返回true，否则返回false
     * 当已有记录无法进行判断时，返回false
     */
    public boolean isChildOf(String qualifiedName1, String qualifiedName2) {
        if (!isKnown(qualifiedName1)) {
            return false;
        }
        String nowName = qualifiedName1;
        if (nowName == null) return false;
        while (!Objects.equals(nowName, THROWABLE)) {
            if (nowName.equals(qualifiedName2)) {
                return true;
            }
            nowName = localExceptions.get(nowName);
        }
        return nowName.equals(qualifiedName2);
    }

    /**
     * 递归查找接收的exception全名的父类判断是否是Runtime，若是Runtime返回True，否则返回false
     * 当输入名称不在记录时返回false
     */
    public boolean isRuntime(String qualifiedName) {
        if (qualifiedName == null) {
            return false;
        }
        if (qualifiedName.equals(RUNTIME_EXCEPTION)) {
            return true;
        }
        if (qualifiedName.equals(THROWABLE)) {
            return false;
        }
        return isRuntime(localExceptions.get(qualifiedName));
    }

    /**
     * 根据输入的exception的simpleName返回查找到的qualifiedName
     * 若无法查询到结果，直接返回原simpleName
     */
    public String getQualifiedName(String simpleName) {
        String mappedName = qualifiedNameMap.get(simpleName);
        if (mappedName == null) {
            for (String qualifiedName: localExceptions.keySet()) {
                String[] splits = qualifiedName.split("\\.");
                if (splits.length > 0 && splits[splits.length - 1].equals(simpleName)) {
                    qualifiedNameMap.put(simpleName, qualifiedName);
                    return qualifiedName;
                }
            }
            return simpleName;
        } else {
            return mappedName;
        }
    }

    /**
     * 根据输入的exception的simpleName返回查找到的qualifiedName
     * 若无法查询到结果，返回null
     */
    public String getQualifiedNameElseNull(String simpleName) {
        String mappedName = qualifiedNameMap.get(simpleName);
        if (mappedName == null) {
            for (String qualifiedName: localExceptions.keySet()) {
                String[] splits = qualifiedName.split("\\.");
                if (splits.length > 0 && splits[splits.length - 1].equals(simpleName)) {
                    qualifiedNameMap.put(simpleName, qualifiedName);
                    return qualifiedName;
                }
            }
            return null;
        } else {
            return mappedName;
        }
    }

    /**
     * 判断输入MethodCallInfo中记录的catch是否能够catch所输入的exception类型
     * 若被catch返回true，否则返回false
     */
    public boolean isCaught(TryCatchNestingInfo tryCatchNestingInfo, String qualifiedName) {
        for (String exceptionType: tryCatchNestingInfo.getExceptionTypes(this)) {
            if (isChildOf(qualifiedName, exceptionType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断输入MethodCallEdge中记录的所有调用是否都能够catch所输入的exception类型
     * 若任意调用过程均被catch返回true，否则返回false
     */
    public boolean isCaught(MethodCallEdge methodCallEdge, String qualifiedName) {
        if (qualifiedName == null) return false;
        try {
            for (TryCatchNestingInfo tryCatchNestingInfo : methodCallEdge.getMethodCallInfos()) {
                if (!isCaught(tryCatchNestingInfo, qualifiedName)) {
                    return false;
                }
            }
        } catch (NullPointerException e) {
            // ignore
            return false;
        }
        return true;
    }

}
