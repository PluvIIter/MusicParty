#  Music Party 
> 一个高颜值的实时在线协作听歌平台。
>
> *本项目参考自 [EveElseIf/MusicParty](https://github.com/EveElseIf/MusicParty)，85%的代码通过vibe coding完成开发。*

***

![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.5-green) ![Vue](https://img.shields.io/badge/Vue.js-3-4FC08D) ![Docker](https://img.shields.io/badge/Docker-Ready-blue)

**Music Party** 是一个开源的、私有化部署的多人实时在线WEB听歌平台。

它允许你和朋友在一个虚拟房间内，通过 **网易云音乐** 或 **Bilibili** 搜索并点播歌曲。系统实现了播放进度同步，无论是在 PC 端还是移动端，所有人听到的都是同一秒的旋律。
（本项目并非“破解版”，对于VIP歌曲/高音质等会员内容，需要具有相应资格账号的cookie来获取，下方有获取cookie的教程）
<img width="1778" height="1080" alt="image" src="https://github.com/user-attachments/assets/64d7f5d1-9837-43ab-8c1b-dad78361b348" />

## 核心特性

*   **多平台支持**：
    *   **网易云音乐**：支持搜索、歌单导入。
        * 网易云的API实现依赖[NeteaseCloudMusicApi](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced)。
    *   **Bilibili**：支持搜索、收藏夹导入。 
        * 注意：b站源由于防盗链问题，方案是先下载本地缓存，然后由服务器将音频提供给用户，可能会占用较多网络带宽，消耗大量流量。
        * 此外，b站源风控现象严重，极不稳定，建议能用网易云就用网易云。
*   **精准同步**：基于 WebSocket (STOMP) 的状态分发，结合前端追帧，实现播放状态、进度、歌单、歌词的实时同步。
*   **响应式设计**：完美适配 PC 宽屏与移动端（手机端可能存在保活问题，后台播放受限）。
*   **房间权限**：支持设置房间密码，或管理员指令实时锁定/解锁房间。
*   **实时互动**：内置聊天室、点赞动效、系统日志广播。
*   **智能队列**：实现“公平随机”算法，防止单人霸榜。
*   **直播音频流**：可以开启直播音频流，使用一个简单的链接来实时收听，用于类似于vrChat等类似场景。

## Docker 部署（推荐）

本项目支持全自动化的 Docker 部署，建议直接拉取构建好的镜像。

### 1. 使用 Docker Compose 一键启动 (最简方案)

下载项目自带的 `docker-compose.yml` 并根据需要修改其中的环境变量，compose中带有NeteaseCloudMusicApi。

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
  -v ./music_party/cached_media:/app/cached_media \
  -v ./music_party/data:/app/data \
  --restart unless-stopped \
  thornex/music-party:latest
```

### 环境变量说明

| 变量名                       | 必填 | 说明                                                                          |
|:--------------------------|:---|:----------------------------------------------------------------------------|
| `APP_AUTHOR_NAME`         | 否  | 页面显示的作者名字，地点在左上角标题后面。默认为 `ThorNex`。                                         |
| `APP_BACK_WORDS`          | 否  | 中间专辑封面后方的装饰性背景字，强制大写。默认为 `MUSIC PARTY`。                                     |
| `ADMIN_PASSWORD`          | 是  | 管理员密码，用于打开管理员面板。                                                            |
| `NETEASE_ENABLED`         | 是  | 网易云源是否开启。                                                                   |
| `NETEASE_API_URL`         | 是  | NeteaseCloudMusicApi 的地址，Docker 部署时默认为 `http://netease-api:3000`。           |
| `BASE_URL`                | 否  | 服务的域名（带协议）。用户获取直播流链接时，拼接在前面。默认为 `http://localhost:8848`。                    |
| `BILIBILI_ENABLED`        | 否  | B站源是否开启。                                                                    |
| `BILIBILI_SESSDATA`       | 否  | B站账号的 SESSDATA。配置后可支持高音质解析，减少风控。                                            |
| `NETEASE_COOKIE`          | 否  | 网易云账号 Cookie。配置后可播放 VIP 歌曲及获取更高音质。                                          |
| `NETEASE_QUALITY`         | 否  | 网易云音质等级。可选：`standard`, `higher`, `exhigh`, `lossless`, `hires`。默认 `exhigh`。 |
| `QUEUE_MAX_SIZE`          | 否  | 播放队列最大长度，默认 `1000`。                                                         |
| `QUEUE_HISTORY_SIZE`      | 否  | 播放历史记录保留数量，默认 `50`。当播放列表里没有音乐时，会从历史记录随机抽选。                                  |
| `QUEUE_MAX_USER_SONGS`    | 否  | 单个用户在队列中允许的最大点歌数量，默认 `100`。                                                 |
| `PLAYLIST_IMPORT_LIMIT`   | 否  | 导入歌单时的最大歌曲数限制，默认 `100`。                                                     |
| `CHAT_HISTORY_LIMIT`      | 否  | 聊天历史记录保留数量，默认 `1000`。                                                       |
| `CHAT_MIN_INTERVAL`       | 否  | 聊天发言最小间隔 (毫秒)，默认 `1000`。                                                    |
| `CHAT_MAX_LENGTH`         | 否  | 单条聊天消息最大长度 (字符)，默认 `200`。                                                   |
| `CACHE_MAX_SIZE`          | 否  | 本地音乐缓存上限，默认 `1GB`。支持格式如 `512MB`, `2GB`。                                     |
| `AUTH_RATE_LIMIT_ENABLED` | 否  | 是否开启密码验证频率限制，默认 `true`。                                                     |
| `AUTH_MAX_ATTEMPTS`       | 否  | 密码验证最大尝试次数，默认 `5`。                                                          |
| `AUTH_WINDOW_SECONDS`     | 否  | 密码验证统计时间窗口 (秒)，默认 `60`。                                                     |
| `AUTH_BLOCK_DURATION`     | 否  | 超过尝试次数后的封锁时长 (秒)，默认 `300`。                                                  |

---

## Windows 启动器

如果你想在 Windows 上快速运行，而不想折腾 Docker、Java 或 Node.js 环境，可以使用 **一键启动器**。
<img width="1010" height="713" alt="Snipaste_2026-05-21_10-45-11" src="https://github.com/user-attachments/assets/2402f66e-4a52-423f-a32c-c0478c84a283" />

### 获取与使用
1.  前往 [GitHub Releases](https://github.com/pluviiter/MusicParty/releases) 下载最新的 `MusicParty.exe`。
2.  将 `MusicParty.exe` 放置在你喜欢的文件夹中。
3.  **直接双击运行**：
    *   启动器会自动释放内置的 JRE 环境、网易云 API 服务以及 Java 核心程序。
    *   所有的配置、运行环境、缓存都保存在 EXE 同级目录下。
4.  在 UI 界面上修改端口、密码、网易云 Cookie 等配置，点击“启动系统”即可。
5.  系统就绪后，点击界面上的“打开网页”即可。
6. 如果你需要公网部署，请保持监听地址为0.0.0.0，并使用你的公网IP:端口的形式访问网页。

---

## 房间密码
<img width="622" height="427" alt="Image_2026-05-21_10-53-45_s10ixfb1 qoj" src="https://github.com/user-attachments/assets/1f5eeb44-4a21-4dc5-8399-753b9f16b88a" />

部署后首次启动需要设置房间密码，其他成员只有使用密码才能进入。
也可以设置为public公开，此时无需密码即可进入。
密码可以在管理员面板中更改。


---

## 聊天框命令

在前端**聊天窗口**中可以输入以下命令：
*   `//clear`: 从播放队列中清空自己点播的所有歌曲。
*   `//stream`: 获取自己的直播流链接（需要开放直播流）。
*   `//admin`: 打开管理员控制面板（需要管理员密码）。
*   `/alive`: 为解决手机端网页后台播放保活问题的实验性选项，执行后会后台循环播放无感知音频以保活，需要刷新网页，再次输入则关闭。此命令仅个人本地生效。

---

## 随机播放
随机播放是本项目的一个重点，为了保证所有成员的参与度，当开启随机播放时，将按照每个成员一首的方式轮替随机播放此成员点的歌。
如果希望完全随机，可以在管理员面板中切换，此外，也可以设置随机时是否会随机到不在线成员的歌曲。

---

## 置顶
在按成员轮替的随机播放中，播放列表会按成员分组，此时置顶逻辑有所变化。第一次点击置顶后，歌曲只会在该成员的列表中置顶，即轮替到该成员时，此歌曲优先播放。
对已经置顶的歌曲再次点击置顶，才会全局置顶，即无视轮替，下一首 必定是指定歌曲。

---

## 管理员面板
在聊天窗口输入//admin并输入管理员密码后，可以进入管理员面板修改配置。
<img width="1276" height="928" alt="Image_2026-05-21_10-56-16_5vxgwqdh kau" src="https://github.com/user-attachments/assets/593bcd6f-c039-4764-a009-d340cbdef287" />

* 修改部署时的配置参数。
* 锁定播放，切歌，随机按钮以防止用户滥用。（建议保持播放按钮锁定，防止某一个用户因为卸下耳机等行为导致的自动暂停）
* 开关直播流功能。
* 更新歌曲源的凭证，或是启停歌曲源。
* 对播放列表或者聊天记录进行清理。
* 重置系统。

---

## 历史记录
播放过的歌曲会被加入历史记录，当没有歌曲播放时，会随机从历史记录中播放歌曲。

---

## 闲置
当没有任何成员连接时，播放会自动暂停。如果该状态保持一小时，当前歌曲播放状态将被清空。
这是因为网易云的歌曲链接有时效，为了防止链接失效。

---

## 直播流链接获取
1. 确保已经管理员已经在管理员面板启用了直播流。
2. 在聊天窗口中输入`//stream`
3. 切换到系统日志窗口，即可看到自己的直播流链接。
#### 注意，直播流使用FFmpeg，会占用更多性能，且有大量流量消耗。

---

## Cookie凭证获取
* **网易云**
浏览器打开网易云音乐，登录，按F12开启控制台，选择网络，然后点击我的音乐，在控制台中寻找playlist开头的请求，然后找到Cookie，将所有内容复制。
<img width="1604" height="1084" alt="image" src="https://github.com/user-attachments/assets/afa77005-aebd-4120-90f3-ccddb2370712" />

* **bilibili**
浏览器打开bilibili，登录，进入我的收藏页面，按F12开启控制台，选择网络，然后收藏夹翻页，在控制台中寻找lsit开头的请求，然后找到Cookie，在内容中寻找SESSDATA，复制 = 后面到 ; 之前的所有内容。
<img width="1959" height="1084" alt="image" src="https://github.com/user-attachments/assets/67cebc9d-bfd5-4ec6-ae17-1922b2a175e9" />

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
