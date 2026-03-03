#!/bin/bash
# MySQL 初始化脚本 — 按正确顺序执行 SQL
# 由 docker-entrypoint-initdb.d 自动调用

set -e

echo ">>> [CAgent] 开始初始化数据库..."

echo ">>> [1/3] 创建表结构..."
mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" < /sql-scripts/schema_table.sql

echo ">>> [2/3] 插入默认管理员用户..."
mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" < /sql-scripts/init_data.sql

echo ">>> [3/3] 插入学习中心与面试题库数据..."
if [ -f /sql-scripts/init_study_data.sql ]; then
  mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" < /sql-scripts/init_study_data.sql
else
  echo ">>> [3/3] init_study_data.sql 不存在，跳过"
fi

echo ">>> [CAgent] 数据库初始化完成!"
