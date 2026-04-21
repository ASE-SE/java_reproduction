package org.jdkAnalyzer;
import org.json.JSONArray;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static java.sql.DriverManager.getConnection;
@Deprecated
public class modifyMethodName {

    static final String databaseOldName = "analyzeData1.db";
    static final Connection conn_old;
    static final String databaseNewName = "analyzeData2.db";
    static final Connection conn_new;

    static {
        try {
            String urlOld = "jdbc:sqlite:" + databaseOldName;
            conn_old = getConnection(urlOld);
            String urlNew = "jdbc:sqlite:" + databaseNewName;
            conn_new = getConnection(urlNew);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        migrateDB();
    }

    public static void migrateDB() {

//            // 启用外键约束支持
//            try (Statement stmt = conn_new.createStatement()) {
//                stmt.execute("PRAGMA foreign_keys = ON;");
//                System.out.println("Foreign key support enabled in new database.");
//            }

        // 按依赖顺序迁移数据
//        migrateTable(conn_old, conn_new, "classes", "qualified_name, modifier, comment, class_type");  // success
//        migrateTable(conn_old, conn_new, "exceptions", "qualified_name, modifier, comment, exception_type, parent");  // success
//        migrateTable(conn_old, conn_new, "methods", "qualified_name, modifier, comment, content");  // success
//        migrateTable(conn_old, conn_new, "files", "file_path");  // success

//        migrateTable(conn_old, conn_new, "throws", "throw_key, method, exception, throw_way, throw_condition");  // success
        migrateTable(conn_old, conn_new, "belongs", "method, class");
        migrateTable(conn_old, conn_new, "calls", "method, method_been_call");
//        migrateTable(conn_old, conn_new, "inherits", "class, parent, relation");  // success
//        migrateTable(conn_old, conn_new, "locates", "file_path, class");  // success

        System.out.println("Data migration completed successfully!");
    }

    private static void migrateTable(Connection oldDbConn, Connection newDbConn, String tableName, String columns) {
        String selectQuery = "SELECT " + columns + " FROM " + tableName;
        String insertQuery = "INSERT INTO " + tableName + " (" + columns + ") VALUES (" +
                String.join(", ", columns.split(", ")) // Placeholder for prepared statement
                        .replaceAll("[^,]+", "?") +
                ")";

        List<String> missed = new ArrayList<>();
        Integer index = 0;

        try (Statement selectStmt = oldDbConn.createStatement();
             ResultSet rs = selectStmt.executeQuery(selectQuery);
             PreparedStatement insertStmt = newDbConn.prepareStatement(insertQuery)) {

            while (rs.next()) {
                String[] columnArray = columns.split(", ");
                for (int i = 0; i < columnArray.length; i++) {
                    insertStmt.setObject(i + 1, rs.getObject(columnArray[i]));
                }
                try {
                    insertStmt.executeUpdate();
                } catch (SQLException e) {
                    missed.add(index.toString());
                    System.out.println("Error migrating table " + tableName + ": " + e.getMessage() + ", " +  index);
                }
                index++;
            }
            System.out.println("Table " + tableName + " migrated successfully.");
        } catch (SQLException e) {
            System.err.println("Error migrating table " + tableName + ": " + e.getMessage());
            System.err.println("Migrating Terminated");
        } finally {
            JSONArray jsonArray = new JSONArray(missed);
            // 将JSONArray转换为字符串
            String jsonString = jsonArray.toString();
            String filePath = tableName + "TableMissedFile.json";
            // 写入文件
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
                writer.write(jsonString);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void modifyMethodNames() {
        try {
            if (conn_new != null) {
                // Enable foreign key constraints
                try (Statement stmt = conn_new.createStatement()) {
                    stmt.execute("PRAGMA foreign_keys = ON;");
                }

                // Fetch all qualified_name values
                String selectSQL = "SELECT qualified_name FROM methods";
                try (Statement stmt = conn_new.createStatement();
                     ResultSet rs = stmt.executeQuery(selectSQL)) {

                    while (rs.next()) {
                        String qualifiedName = rs.getString("qualified_name");

                        // Modify the qualified_name as needed
                        String modifiedName = removeTags(qualifiedName);
                        if (!modifiedName.equals(qualifiedName)) {
                            // Update the qualified_name in the database
                            String updateSQL = "UPDATE methods SET qualified_name = ? WHERE qualified_name = ?";
                            try (PreparedStatement pstmt = conn_new.prepareStatement(updateSQL)) {
                                pstmt.setString(1, modifiedName);
                                pstmt.setString(2, qualifiedName);
                                pstmt.executeUpdate();
                            }
                        }
                    }
                }
                System.out.println("All qualified_name values have been updated.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String removeTags(String input) {
        StringBuilder result = new StringBuilder();
        int openCount = 0;

        for (char c : input.toCharArray()) {
            if (c == '<') {
                openCount++;
            } else if (c == '>') {
                if (openCount > 0) {
                    openCount--;
                }
            } else if (openCount == 0) {
                result.append(c);
            }
        }

        return result.toString();
    }


}
