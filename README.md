#  Music Party 
> 一个高颜值的实时在线协作听歌平台。
>
> *本项目参考自 [EveElseIf/MusicParty](https://github.com/EveElseIf/MusicParty)，通过vibi coding完成开发。*

***

![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.5-green) ![Vue](https://img.shields.io/badge/Vue.js-3-4FC08D) ![Docker](https://img.shields.io/badge/Docker-Ready-blue)

**Music Party** 是一个开源的、私有化部署的多人实时在线听歌平台。

它允许你和朋友在一个虚拟房间内，通过 **网易云音乐** 或 **Bilibili** 搜索并点播歌曲。系统实现了毫秒级的播放进度同步，无论是在 PC 端还是移动端，所有人听到的都是同一秒的旋律。

## 核心特性

*   **多平台支持**：
    *   **网易云音乐**：支持搜索、歌单导入、VIP 歌曲。
    *   **Bilibili**：支持搜索视频/音频、导入收藏夹。内置 **WBI 签名**算法与**本地防盗链缓存代理**，解决 B 站音频流过期和 Referer 限制问题。
*   **精准同步**：基于 WebSocket (STOMP) 的状态分发，结合前端追帧算法，实现播放状态、进度、歌单、歌词的实时同步。
*   **响应式设计**：完美适配 PC 宽屏与移动端（Apple手机端可能存在保活问题，后台播放受限）。
*   **房间权限**：支持设置房间密码，或管理员指令实时锁定/解锁房间。
*   **沉浸体验**：内置 Canvas 音频可视化效果、同步歌词显示、全屏沉浸模式。
*   **实时互动**：内置聊天室、点赞动效、系统日志广播。
*   **智能队列**：实现“公平随机”算法，防止单人霸榜。

## Docker 一键部署（推荐）

本项目包含完整的 `docker-compose.yml`，这是最快的启动方式。

### 1. 准备配置文件
创建一个目录（例如 `music-party`），并在其中创建 `docker-compose.yml` 文件，填入以下内容：

```yaml
services:
  # 1. 网易云音乐 API 服务 (依赖项)
  netease-api:
    image: moefurina/ncm-api:latest
    container_name: music-party-netease
    restart: always
    environment:
      - PORT=3000
      - HTTPS=true
    networks:
      - music-net
    volumes:
      - ./logs/ncm:/app/logs

  # 2. Music Party 主应用
  music-party:
    build: .
    container_name: music-party-app
    restart: always
    ports:
      - "8848:8080"  # 宿主机端口:容器端口
    environment:
      # 管理员密码 (用于执行 //RESET 等指令)
      - ADMIN_PASSWORD=admin123
      
      # 网易云 API 地址 (指向上面的容器)
      - NETEASE_API_URL=http://netease-api:3000

      # 需要对应平台的凭证才能使用曲库
      # B站 SESSDATA 
      - BILIBILI_SESSDATA="your_bilibili_sessdata_here"
      
      # 网易云 Cookie 
      - NETEASE_COOKIE="your_netease_cookie_here"
    depends_on:
      - netease-api
    networks:
      - music-net
    volumes:
      # 挂载 B 站音频缓存目录 (防止重启后重新下载)
      - ./cached_media:/app/cached_media

networks:
  music-net:
    driver: bridge
```

### 2. 启动服务
在目录下运行：

```bash
docker-compose up -d
```

启动后，访问 `http://localhost:8848` 即可进入应用。公网部署请使用IP/域名。

---

## 环境变量说明

| 变量名 | 必填 | 说明 |
| :--- | :--- | :--- |
| `ADMIN_PASSWORD` | 是 | 管理员密码，用于在搜索框输入指令控制系统。 |
| `NETEASE_API_URL` | 是 | NeteaseCloudMusicApi 的地址，Docker 部署时默认为 `http://netease-api:3000`。 |
| `BILIBILI_SESSDATA`| 否 | B站账号的 SESSDATA。不填会导致搜索结果受限、无法解析 1080P/Hi-Res 音频流，且极易触发风控。 |
| `NETEASE_COOKIE` | 否 | 网易云账号 Cookie。配置后可播放 VIP 歌曲及获取更高音质。 |

---

## 搜索框指令 (Admin Commands)

在前端**搜索框**中输入以下指令，并在回车后输入管理员密码：

*   `//RESET`: **重置系统**。切歌、清空播放列表、重置播放状态（慎用）。
*   `//CLEAR`: **清空队列**。保留当前播放的歌曲，清空等待队列。
*   `//PASS <新密码>`: **设置房间密码**。例如 `//PASS 123456`。
*   `//OPEN`: **开放房间**。取消房间密码，允许任何人进入。
*   `//COOKIE <platform> <value>`: 动态更新 Cookie。
    *   例：`//COOKIE netease MUSIC_U=xxxx...`
    *   例：`//COOKIE bilibili xxxxx...`

---

## 本地开发指南

### 前端 (music-party-web)

1.  环境要求：Node.js 18+
2.  进入目录并安装依赖：
    ```bash
    cd music-party-web
    npm install
    ```
3.  启动开发服务器：
    ```bash
    npm run dev
    ```
4.  配置代理：`vite.config.js` 默认将 `/api` 和 `/ws` 代理到 `http://localhost:8080`。

### 后端 (Java)

1.  环境要求：JDK 21, Maven 3.x
2.  配置：修改 `src/main/resources/application.yml` 或通过 IDEA 环境变量传入 `BILIBILI_SESSDATA` 等配置。
3.  运行：
    ```bash
    mvn spring-boot:run
    ```

### 完整构建

后端采用 Maven 打包，前端静态资源会被 WebFlux 托管（需自行配置资源拷贝或反向代理）。建议直接使用 Docker 构建整个镜像。

---

## 免责声明

*   本项目仅供学习交流使用，请勿用于商业用途。
*   本项目涉及的第三方 API（网易云音乐、Bilibili）均为非官方接口，项目开发者不对 API 的可用性及账号风险负责。
*   请尊重版权，支持正版音乐。

---

## 贡献

欢迎提交 Issue 和 Pull Request！无论是修复 Bug、新增功能还是优化文档。

## License

MIT License
