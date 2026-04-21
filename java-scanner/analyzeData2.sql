-- sqlite3 analyzeData2.db ".read analyzeData2.sql"

-- 启用外键支持
PRAGMA foreign_keys = ON;
-- 创建类表：classes
CREATE TABLE classes (
    qualified_name TEXT PRIMARY KEY,
    modifier INT NOT NULL,
    comment TEXT,
    class_type TEXT
);

-- 创建异常表：exceptions
CREATE TABLE exceptions (
    qualified_name TEXT PRIMARY KEY,
    modifier INT NOT NULL,
    comment TEXT,
    exception_type TEXT,
    parent TEXT,
    FOREIGN KEY (parent) REFERENCES exceptions(qualified_name)
);

-- 创建方法表：methods
CREATE TABLE methods (
    qualified_name TEXT PRIMARY KEY,
    modifier INT,
    comment TEXT,
    content TEXT
);

-- 创建方法-抛出-异常关系表
CREATE TABLE throws (
    throw_key TEXT PRIMARY KEY,
    method TEXT NOT NULL,
    exception TEXT NOT NULL,
    throw_way TEXT NOT NULL,
    throw_condition TEXT,
    FOREIGN KEY (method) REFERENCES methods(qualified_name) ON UPDATE CASCADE,
    FOREIGN KEY (exception) REFERENCES exceptions(qualified_name) ON UPDATE CASCADE
);

-- 创建方法-属于-类关系表，使用组合唯一约束
CREATE TABLE belongs (
    method TEXT NOT NULL,
    class TEXT NOT NULL,
    FOREIGN KEY (method) REFERENCES methods(qualified_name) ON UPDATE CASCADE,
    FOREIGN KEY (class) REFERENCES classes(qualified_name) ON UPDATE CASCADE,
    UNIQUE (method, class)  -- 组合唯一约束
);

-- 创建方法-调用-方法关系表，使用组合唯一约束
CREATE TABLE calls (
    method TEXT NOT NULL,
    method_been_call TEXT NOT NULL,
    FOREIGN KEY (method) REFERENCES methods(qualified_name) ON UPDATE CASCADE,
    FOREIGN KEY (method_been_call) REFERENCES methods(qualified_name) ON UPDATE CASCADE,
    UNIQUE (method, method_been_call)  -- 组合唯一约束
);

-- 创建类-继承/实现-类关系表，使用组合唯一约束
CREATE TABLE inherits (
    class TEXT NOT NULL,
    parent TEXT NOT NULL,
    relation TEXT NOT NULL,
    FOREIGN KEY (class) REFERENCES classes(qualified_name) ON UPDATE CASCADE,
    FOREIGN KEY (parent) REFERENCES classes(qualified_name) ON UPDATE CASCADE,
    UNIQUE (class, parent)  -- 组合唯一约束
);

-- 创建文件表：files
CREATE TABLE files (
    file_path TEXT PRIMARY KEY
);

-- 创建类-位于-文件表，使用组合唯一约束
CREATE TABLE locates (
    file_path TEXT NOT NULL,
    class TEXT NOT NULL,
    FOREIGN KEY (file_path) REFERENCES files(file_path) ON UPDATE CASCADE,
    FOREIGN KEY (class) REFERENCES classes(qualified_name) ON UPDATE CASCADE,
    UNIQUE (file_path, class)  -- 组合唯一约束
);
