# ============================
# Stage 1: Build Frontend (Vue)
# ============================
FROM node:22-alpine AS frontend-builder
WORKDIR /app/frontend

ARG APP_AUTHOR_NAME="ThorNex"
ARG APP_BACK_WORDS="THORNEX"

ENV VITE_APP_AUTHOR_NAME=${APP_AUTHOR_NAME}
ENV VITE_APP_BACK_WORDS=${APP_BACK_WORDS}

# 复制前端项目定义文件
COPY music-party-web/package*.json ./
# 安装依赖
RUN npm install

# 复制前端源代码
COPY music-party-web/ .
# 编译生产环境代码
RUN npm run build

# ============================
# Stage 2: Build Backend (Spring Boot)
# ============================
FROM maven:3.9-eclipse-temurin-21-alpine AS backend-builder
WORKDIR /app/backend

# 复制 Maven 依赖定义
COPY pom.xml .
# 预下载依赖 (利用缓存，加速构建)
RUN mvn dependency:go-offline -B

# 复制后端源代码
COPY src ./src

# Spring Boot 默认会服务 static 目录下的 index.html
COPY --from=frontend-builder /app/frontend/dist ./src/main/resources/static/

# 编译 JAR 包，跳过测试
RUN mvn clean package -DskipTests

# ============================
# Stage 3: Runtime Image
# ============================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 复制构建好的 JAR 包
COPY --from=backend-builder /app/backend/target/*.jar app.jar

# 暴露端口
EXPOSE 8080

# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]