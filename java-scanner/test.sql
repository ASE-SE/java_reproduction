-- sqlite3 analyzeData.db ".read analyzeData.sql"

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
    parent TEXT
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
    FOREIGN KEY (method) REFERENCES methods(qualified_name),
    FOREIGN KEY (exception) REFERENCES exceptions(qualified_name)
);

-- 创建方法-属于-类关系表，使用组合唯一约束
CREATE TABLE belongs (
    method TEXT NOT NULL,
    class TEXT NOT NULL,
    FOREIGN KEY (method) REFERENCES methods(qualified_name),
    FOREIGN KEY (class) REFERENCES classes(qualified_name),
    UNIQUE (method, class)  -- 组合唯一约束
);

-- 创建方法-调用-方法关系表，使用组合唯一约束
CREATE TABLE calls (
    method TEXT NOT NULL,
    method_been_call TEXT NOT NULL,
    FOREIGN KEY (method) REFERENCES methods(qualified_name),
    FOREIGN KEY (method_been_call) REFERENCES methods(qualified_name),
    UNIQUE (method, method_been_call)  -- 组合唯一约束
);

-- 创建类-继承/实现-类关系表，使用组合唯一约束
CREATE TABLE inherits (
    class TEXT NOT NULL,
    parent TEXT NOT NULL,
    relation TEXT NOT NULL,
    FOREIGN KEY (class) REFERENCES classes(qualified_name),
    FOREIGN KEY (parent) REFERENCES classes(qualified_name),
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
    FOREIGN KEY (file_path) REFERENCES files(file_path),
    FOREIGN KEY (class) REFERENCES classes(qualified_name),
    UNIQUE (file_path, class)  -- 组合唯一约束
);


-- 一层
SELECT m.qualified_name, m.modifier, m.comment, m.content, ts.exception, ts.throw_way, ts.throw_condition 
FROM methods m 
JOIN throws ts 
    ON ts.method = m.qualified_name 
WHERE m.qualified_name = 'java.io.ObjectInputStream.readArray(boolean)';

SELECT m.qualified_name, m.modifier, ts.exception, ts.throw_way, ts.throw_condition 
FROM methods m 
JOIN throws ts 
    ON ts.method = m.qualified_name 
WHERE m.qualified_name = 'java.io.ObjectInputStream.readArray(boolean)';

-- 二层
SELECT ms.qualified_name, m.qualified_name, m.modifier, m.comment, m.content, ts.exception, ts.throw_way, ts.throw_condition 
FROM methods ms 
JOIN calls c1 
    ON c1.method = ms.qualified_name 
JOIN throws ts 
    ON ts.method = c1.method_been_call 
JOIN methods m 
    ON m.qualified_name = c1.method_been_call 
WHERE ms.qualified_name = 'java.io.ObjectInputStream.readArray(boolean)';

SELECT ms.qualified_name, m.qualified_name, m.modifier, ts.exception, ts.throw_way, ts.throw_condition 
FROM methods ms 
JOIN calls c1 
    ON c1.method = ms.qualified_name 
JOIN throws ts 
    ON ts.method = c1.method_been_call 
JOIN methods m 
    ON m.qualified_name = c1.method_been_call 
WHERE ms.qualified_name = 'java.io.ObjectInputStream.readArray(boolean)';

-- 三层
SELECT ms.qualified_name, c1.method, m.qualified_name, m.modifier, m.comment, m.content, ts.exception, ts.throw_way, ts.throw_condition 
FROM methods ms
JOIN calls c2
    ON c2.method = ms.qualified_name
JOIN calls c1 
    ON c1.method = c2.method_been_call 
JOIN throws ts 
    ON ts.method = c1.method_been_call 
JOIN methods m 
    ON m.qualified_name = c1.method_been_call 
WHERE ms.qualified_name = 'java.io.ObjectInputStream.readArray(boolean)';

SELECT ms.qualified_name, c1.method, m.qualified_name, m.modifier, ts.exception, ts.throw_way, ts.throw_condition 
FROM methods ms
JOIN calls c2
    ON c2.method = ms.qualified_name
JOIN calls c1 
    ON c1.method = c2.method_been_call 
JOIN throws ts 
    ON ts.method = c1.method_been_call 
JOIN methods m 
    ON m.qualified_name = c1.method_been_call 
WHERE ms.qualified_name = 'java.io.ObjectInputStream.readArray(boolean)';

-- 四层
SELECT ms.qualified_name, c2.method, c1.method, m.qualified_name, m.modifier, m.comment, m.content, ts.exception, ts.throw_way, ts.throw_condition 
FROM methods ms
JOIN calls c3
    ON c3.method = ms.qualified_name
JOIN calls c2
    ON c2.method = c3.method_been_call 
JOIN calls c1 
    ON c1.method = c2.method_been_call 
JOIN throws ts 
    ON ts.method = c1.method_been_call 
JOIN methods m 
    ON m.qualified_name = c1.method_been_call 
WHERE ms.qualified_name = 'insert_str';

