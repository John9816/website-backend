#!/bin/bash

echo "=== 检查Maven配置 ==="
mvn --version

echo ""
echo "=== 检查pom.xml ==="
if [ -f pom.xml ]; then
    echo "✓ pom.xml 存在"
    grep -A 2 "<artifactId>website</artifactId>" pom.xml | head -3
    grep -A 2 "<finalName>" pom.xml | head -3 || echo "未找到 finalName 配置"
else
    echo "✗ pom.xml 不存在"
fi

echo ""
echo "=== 开始编译 ==="
mvn clean package -DskipTests

echo ""
echo "=== 检查编译输出 ==="
if [ -d target ]; then
    echo "target目录内容:"
    ls -lh target/*.jar 2>/dev/null || echo "未找到jar文件"
    ls -lh target/ | grep -E "\\.jar$" || echo "target目录中没有jar文件"
else
    echo "✗ target目录不存在"
fi
