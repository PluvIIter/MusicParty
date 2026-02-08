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

本项目支持全自动化的 Docker 部署，建议直接拉取构建好的镜像。

### 1. 使用 Docker Compose 一键启动 (最简方案)

下载项目自带的 `docker-compose.yml` 并根据需要修改其中的环境变量。

```bash
# 下载配置
curl -sSL https://raw.githubusercontent.com/pluviiter/MusicParty/main/docker-compose.yml > docker-compose.yml

# 修改配置（填写密码、Cookie 等）
vi docker-compose.yml

# 启动服务
docker-compose up -d
```

### 2. 使用 Docker Run 启动

如果你已有现成的网易云 API 服务，可以使用以下命令部署主应用：

```bash
docker run -d \
  --name music-party \
  -p 8848:8080 \
  -e ADMIN_PASSWORD=admin123 \
  -e NETEASE_API_URL=http://your-api-ip:3000 \
  -e BASE_URL=http://localhost:8848 \
  -e APP_AUTHOR_NAME="ThorNex" \
  -e APP_BACK_WORDS="MUSIC PARTY" \
  -e NETEASE_COOKIE="" \
  -e BILIBILI_SESSDATA="" \
  -e QUEUE_MAX_SIZE=1000 \
  -e QUEUE_HISTORY_SIZE=50 \
  -e PLAYLIST_IMPORT_LIMIT=100 \
  -e CHAT_HISTORY_LIMIT=1000 \
  -e CACHE_MAX_SIZE=1GB \
  -v ./music_party:/app \
  --restart unless-stopped \
  thornex/music-party:latest
```

---

## 环境变量说明

| 变量名                       | 必填 | 说明                                                                |
|:--------------------------|:---|:------------------------------------------------------------------|
| `APP_AUTHOR_NAME`         | 否  | 页面显示的作者名字，地点在左上角标题后面。默认为 `ThorNex`。                               |
| `APP_BACK_WORDS`          | 否  | 中间专辑封面后方的装饰性背景字，强制大写。默认为 `MUSIC PARTY`。                           |
| `ADMIN_PASSWORD`          | 是  | 管理员密码，用于在搜索框输入指令控制系统。                                             |
| `NETEASE_API_URL`         | 是  | NeteaseCloudMusicApi 的地址，Docker 部署时默认为 `http://netease-api:3000`。 |
| `BASE_URL`                | 否  | 服务的域名（带协议）。用户获取直播流链接时，拼接在前面。默认为 `http://localhost:8848`。          |
| `BILIBILI_SESSDATA`       | 否  | B站账号的 SESSDATA。配置后可支持高音质解析，减少风控。                                  |
| `NETEASE_COOKIE`          | 否  | 网易云账号 Cookie。配置后可播放 VIP 歌曲及获取更高音质。                                |
| `QUEUE_MAX_SIZE`          | 否  | 播放队列最大长度，默认 `1000`。                                               |
| `QUEUE_HISTORY_SIZE`      | 否  | 播放历史记录保留数量，默认 `50`。当播放列表里没有音乐时，会从历史记录随机抽选。                        |
| `QUEUE_MAX_USER_SONGS`    | 否  | 单个用户在队列中允许的最大点歌数量，默认 `100`。                                       |
| `PLAYLIST_IMPORT_LIMIT`   | 否  | 导入歌单时的最大歌曲数限制，默认 `100`。                                           |
| `CHAT_HISTORY_LIMIT`      | 否  | 聊天历史记录保留数量，默认 `1000`。                                             |
| `CHAT_MIN_INTERVAL`       | 否  | 聊天发言最小间隔 (毫秒)，默认 `1000`。                                          |
| `CHAT_MAX_LENGTH`         | 否  | 单条聊天消息最大长度 (字符)，默认 `200`。                                         |
| `CACHE_MAX_SIZE`          | 否  | 本地音乐缓存上限，默认 `1GB`。支持格式如 `512MB`, `2GB`。                           |
| `AUTH_RATE_LIMIT_ENABLED` | 否  | 是否开启密码验证频率限制，默认 `true`。                                           |
| `AUTH_MAX_ATTEMPTS`       | 否  | 密码验证最大尝试次数，默认 `5`。                                                |
| `AUTH_WINDOW_SECONDS`     | 否  | 密码验证统计时间窗口 (秒)，默认 `60`。                                           |
| `AUTH_BLOCK_DURATION`     | 否  | 超过尝试次数后的封锁时长 (秒)，默认 `300`。                                        |

---

## 搜索框指令

在前端**搜索框**中输入以下指令，并在回车后输入管理员密码：

*   `//LOCK <TYPE> <ON/OFF>`: **锁定控制权限**。
    *   `TYPE` 可选: `PAUSE` (暂停), `SKIP` (切歌), `SHUFFLE` (随机), `ALL` (全部)。
    *   例如: `//LOCK PAUSE ON` 锁定暂停功能。锁定后用户无法主动暂停（但允许从暂停恢复播放）。
*   `//PAUSE`: **强制暂停/播放**。管理员强制操作，无视锁定。
*   `//SKIP`: **强制切歌**。管理员强制操作，无视锁定。
*   `//SHUFFLE`: **强制切换随机**。管理员强制操作，无视锁定。
*   `//RESET`: **重置系统**。切歌、清空播放列表、清空聊天记录、重置播放状态（慎用）。
*   `//CLEAR <QUEUE/CHAT>`: **清空数据**。
    *   `QUEUE` (默认): 清空等待队列，保留当前播放。
    *   `CHAT`: 清空所有聊天历史记录。
    *   例如: `//CLEAR CHAT`。
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
#### 注意，直播流使用FFmpeg，会占用更多性能，且有大量流量消耗。

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

建议直接使用 Docker 镜像进行生产环境运行。构建镜像请参考根目录下的 `Dockerfile`。

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
