#  Music Party 
> 一个高颜值的实时在线协作听歌平台。
>
> *本项目参考自 [EveElseIf/MusicParty](https://github.com/EveElseIf/MusicParty)，通过vibi coding完成开发。*

***

![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.5-green) ![Vue](https://img.shields.io/badge/Vue.js-3-4FC08D) ![Docker](https://img.shields.io/badge/Docker-Ready-blue)

**Music Party** 是一个开源的、私有化部署的多人实时在线听歌平台。

它允许你和朋友在一个虚拟房间内，通过 **网易云音乐** 或 **Bilibili** 搜索并点播歌曲。系统实现了播放进度同步，无论是在 PC 端还是移动端，所有人听到的都是同一秒的旋律。
<img width="1778" height="1080" alt="image" src="https://github.com/user-attachments/assets/64d7f5d1-9837-43ab-8c1b-dad78361b348" />

## 核心特性

*   **多平台支持**：
    *   **网易云音乐**：支持搜索、歌单导入。
    *   **Bilibili**：支持搜索、收藏夹导入。b站源由于防盗链问题，方案是先下载本地缓存，然后由服务器将音频提供给用户，可能会消耗大量流量。
*   **精准同步**：基于 WebSocket (STOMP) 的状态分发，结合前端追帧，实现播放状态、进度、歌单、歌词的实时同步。
*   **响应式设计**：完美适配 PC 宽屏与移动端（Apple手机端可能存在保活问题，后台播放受限）。
*   **房间权限**：支持设置房间密码，或管理员指令实时锁定/解锁房间。
*   **实时互动**：内置聊天室、点赞动效、系统日志广播。
*   **智能队列**：实现“公平随机”算法，防止单人霸榜。
*   **直播音频流**：可以开启直播音频流，使用一个简单的链接来实时收听，用于类似于vrChat等类似场景。

## Docker 部署（推荐）

### 1. 一键部署

使用docker-compose一键部署网易云api+本应用：

```bash
curl -sSL https://raw.githubusercontent.com/ThorNex/MusicParty/main/docker-compose.prod.yml > docker-compose.yml
# 编辑配置 (填入你的 Cookie 等)
# 启动
docker-compose up -d
```

### 2. 使用 Docker Run 单独启动

如果你已有现成的网易云 API 服务，可以直接使用以下命令：

```bash
docker run -d \
  --name music-party \
  -p 8848:8080 \
  -e ADMIN_PASSWORD=admin123 \
  -e NETEASE_API_URL=http://your-api-ip:3000 \
  -e NETEASE_COOKIE="你的网易云Cookie" \
  -e BILIBILI_SESSDATA="你的B站SESSDATA" \
  -v ./cached_media:/app/cached_media \
  --restart unless-stopped \
  thornex/music-party:latest
```

---

## 环境变量说明

| 变量名                     | 必填 | 说明                                                                |
|:------------------------|:---|:------------------------------------------------------------------|
| `APP_AUTHOR_NAME`       | 否  | 页面显示的作者名字，地点在左上角标题后面，以by XXX的形式。                                  |
| `APP_BACK_WORDS`        | 否  | 中间专辑封面后方的装饰性背景字，强制大写。                                             |
| `ADMIN_PASSWORD`        | 是  | 管理员密码，用于在搜索框输入指令控制系统。                                             |
| `NETEASE_API_URL`       | 是  | NeteaseCloudMusicApi 的地址，Docker 部署时默认为 `http://netease-api:3000`。 |
| `BASE_URL`              | 否  | 服务的域名。用户获取直播流链接时，拼接在前面。                                           |
| `BILIBILI_SESSDATA`     | 否  | B站账号的 SESSDATA。不填会导致搜索结果受限、无法解析 1080P/Hi-Res 音频流，且极易触发风控。         |
| `NETEASE_COOKIE`        | 否  | 网易云账号 Cookie。配置后可播放 VIP 歌曲及获取更高音质。                                |
| `QUEUE_MAX_SIZE`        | 否  | 播放队列最大长度，默认 `1000`。                                               |
| `QUEUE_HISTORY_SIZE`    | 否  | 播放历史记录保留数量，默认 `50`。当播放列表里没有音乐时，会从历史记录随机抽选。                        |
| `PLAYLIST_IMPORT_LIMIT` | 否  | 导入歌单时的最大歌曲数限制，默认 `100`。                                           |
| `CHAT_HISTORY_LIMIT`    | 否  | 聊天历史记录保留数量，默认 `1000`。                                             |
| `CACHE_MAX_SIZE`        | 否  | 本地音乐缓存上限，默认 `1GB`。支持格式如 `512MB`, `2GB`。                           |

---

## 搜索框指令

在前端**搜索框**中输入以下指令，并在回车后输入管理员密码：

*   `//RESET`: **重置系统**。切歌、清空播放列表、重置播放状态（慎用）。
*   `//CLEAR`: **清空队列**。保留当前播放的歌曲，清空等待队列。
*   `//PASS <新密码>`: **设置房间密码**。例如 `//PASS 123456`。
*   `//OPEN`: **开放房间**。取消房间密码，允许任何人进入。
*   `//STREAM ON`: **开放直播流**。允许用户通过直播流链接进行收听。默认关闭。
*   `//STREAM OFF`: **关闭直播流**。禁止用户通过直播流链接进行收听。此为默认项。
*   `//COOKIE <platform> <value>`: 动态更新 Cookie。
    *   例：`//COOKIE netease MUSIC_U=xxxx...`
    *   例：`//COOKIE bilibili xxxxx...`

---

## 直播流链接获取
1. 确保已经使用`//STREAM ON`启用了直播流。
2. 在聊天窗口中输入`//stream`
3. 切换到系统日志窗口，即可看到自己的直播流链接。
### 注意，直播流使用FFmpeg，会占用更多性能，且有大量流量消耗。

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

1.  环境要求：JDK 21, Maven 3.x, 并已部署Netease Cloud Music Api。
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
