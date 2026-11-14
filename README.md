#  Music Party 
> 一个高颜值的实时在线听歌Web应用。
>
> *本项目参考自 [EveElseIf/MusicParty](https://github.com/EveElseIf/MusicParty)，85%的代码通过vibe coding完成开发。*
> 
> [演示地址](https://music.thornex.uk/)  [视频演示](https://www.bilibili.com/video/BV1LCVJ6QEmk)

***

![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.5-green) ![Vue](https://img.shields.io/badge/Vue.js-3-4FC08D) ![Docker](https://img.shields.io/badge/Docker-Ready-blue)

**Music Party** 是一个开源的、私有化部署的多人实时在线WEB听歌平台。

它允许你和朋友在一个虚拟房间内，通过 **网易云音乐**、**Bilibili** 或 **QQ音乐** 搜索并点播歌曲。系统实现了播放进度同步，无论是在 PC 端还是移动端，所有人听到的都是同一秒的旋律。

（本项目并非“破解版”，对于VIP歌曲/高音质等会员内容，需要具有相应资格账号的cookie来获取，下方有获取cookie的教程）

<img width="1778" height="1080" alt="image" src="https://github.com/user-attachments/assets/64d7f5d1-9837-43ab-8c1b-dad78361b348" />

## 核心特性

*   **多平台支持**：
    *   **网易云音乐**：支持搜索、歌单导入。
        * 网易云的API实现依赖[NeteaseCloudMusicApi](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced)。
    *   **Bilibili**：支持搜索、收藏夹导入。 
        * 注意：b站源由于防盗链问题，方案是先下载本地缓存，然后由服务器将音频提供给用户，可能会占用较多网络带宽，消耗大量流量。
        * 此外，b站源风控现象严重，极不稳定，建议能用网易云就用网易云。
    *   **QQ音乐**：支持搜索、歌单导入。
        * QQ音乐的API实现依赖 [L-1124/QQMusicApi](https://github.com/L-1124/QQMusicApi)；Windows启动器已内置服务，不支持DRM加密音频与私人DJ语音。
*   **一键内网穿透**：Windows启动器内置 Cloudflare Tunnel，打开开关即可生成临时公网地址，方便邀请朋友加入。
*   **精准同步**：基于 WebSocket (STOMP) 的状态分发，结合前端追帧，实现播放状态、进度、歌单、歌词的实时同步。
*   **响应式设计**：完美适配 PC 宽屏与移动端；支持媒体会话锁屏控制，移动端推荐『添加到主屏幕』以启用 PWA 后台播放。
*   **房间权限**：支持设置房间密码，或管理员指令实时锁定/解锁房间。
*   **实时互动**：内置聊天室、点赞动效、系统日志广播。
*   **智能队列**：支持顺序播放、轮询播放、随机播放和单曲循环；轮询防止单人霸榜，个人歌单支持拖动排序。
*   **用户收藏**：通过搜索并选择用户完成本机绑定，绑定决定查询目标，不是登录该用户；不提供直接输入 UID。查询使用该音源配置的 Cookie，未配置时匿名；能读取的内容由上游账号权限决定，不会把目标替换成配置账号。当前提供歌单/收藏夹类别，收藏专辑暂不提供。
*   **私人 FM / DJ**：由部署配置 `PRIVATE_DJ_SOURCE` 指定唯一推荐音源，默认 `netease`，仅网易云提供 FM/DJ，QQ 音乐不提供推荐，均使用所选源配置凭据。保留填空、入队和托管；不支持或失效时明确提示，不自动换源。推荐目标是配置账号本人，不改变成员已绑定的收藏查询目标。
*   **动态音源能力**：搜索、专辑和收藏分类由后端音源目录声明；交付可选择直链或服务器下载，不需要在前端添加平台名单。本机 LIKESONG 始终独立保存、展示。
*   **直播音频流**：可以开启直播音频流，使用一个简单的链接来实时收听，用于类似于vrChat等类似场景。

## Docker 部署（推荐）

本项目支持全自动化的 Docker 部署，建议直接拉取构建好的镜像。

### 1. 使用 Docker Compose 一键启动 (最简方案)

下载项目自带的 `docker-compose.yml` 并根据需要修改其中的环境变量。compose 会一并启动网易云和 QQ 音乐的上游 API，主应用按 `*_ENABLED` 选择启用哪些音源。

```bash
# 下载配置
curl -sSL https://raw.githubusercontent.com/pluviiter/MusicParty/main/docker-compose.yml > docker-compose.yml

# 修改配置（填写密码、Cookie 等）
vi docker-compose.yml

# 启动服务
docker compose up -d --build
```

### 2. 使用 Docker Run 启动

#### 单独部署上游 API

网易云音乐 API：

```bash
docker run -d --name netease-api --restart always -p 3000:3000 \
  -e PORT=3000 -e HTTPS=true \
  moefurina/ncm-api:latest
```

QQ 音乐 API（固定上游版本，先构建再运行）：

```bash
docker build -f web/Dockerfile -t music-party-qqmusic-api:c046788 \
  'https://github.com/L-1124/QQMusicApi.git#c046788dd1f11daacc74da487cbbf1bcd8ac6f33'

docker run -d --name qqmusic-api --restart always -p 8080:8080 \
  -e QQMUSIC_SERVER_HOST=0.0.0.0 \
  music-party-qqmusic-api:c046788
```

#### 启动主应用

把上面两个地址填进 `NETEASE_API_URL`、`QQMUSIC_API_URL`，再按需打开 `*_ENABLED`：

```bash
docker run -d \
  --name music-party \
  -p 8848:8080 \
  -e ADMIN_PASSWORD=admin123 \
  -e BASE_URL=http://localhost:8848 \
  -e APP_AUTHOR_NAME="ThorNex" \
  -e APP_BACK_WORDS="MUSIC PARTY" \
  -e NETEASE_ENABLED=true \
  -e NETEASE_API_URL=http://your-netease-api:3000 \
  -e NETEASE_COOKIE="" \
  -e QQMUSIC_ENABLED=true \
  -e QQMUSIC_API_URL=http://your-qqmusic-api:8080 \
  -e QQMUSIC_COOKIE="" \
  -e BILIBILI_ENABLED=false \
  -e BILIBILI_COOKIE="" \
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

**基础**

| 变量名 | 说明 |
|:--|:--|
| `ADMIN_PASSWORD` | 管理员密码（必填），用于打开管理员面板。 |
| `BASE_URL` | 服务的域名（带协议），用于拼接直播流链接。 |
| `APP_AUTHOR_NAME` | 页面左上角显示的作者名。 |
| `APP_BACK_WORDS` | 中间专辑封面后方的装饰性背景字，强制大写。 |

**音源**

音源按需启用，需要哪个源就把对应的 `*_ENABLED` 设为 `true`。

| 变量名 | 说明 |
|:--|:--|
| `NETEASE_ENABLED` | 是否启用网易云音乐源。 |
| `NETEASE_API_URL` | NeteaseCloudMusicApi 的地址，Compose 中为 `http://netease-api:3000`。 |
| `NETEASE_COOKIE` | 网易云账号 Cookie；不填时匿名请求，可读内容由上游权限决定。 |
| `NETEASE_QUALITY` | 音质等级：`standard`、`higher`、`exhigh`、`lossless`、`hires`。 |
| `NETEASE_DELIVERY_MODE` | `DIRECT_URL` 或 `SERVER_DOWNLOAD`。 |
| `BILIBILI_ENABLED` | 是否启用 Bilibili 源。 |
| `BILIBILI_COOKIE` | B 站完整 Cookie；不填时使用匿名设备信息。 |
| `BILIBILI_DELIVERY_MODE` | `DIRECT_URL` 或 `SERVER_DOWNLOAD`；直链格式及可用性取决于上游。 |
| `BILIBILI_MAX_DURATION_MINUTES` | 单曲最长时长（分钟），超出的视频标记为不可播放。 |
| `QQMUSIC_ENABLED` | 是否启用 QQ 音乐源。 |
| `QQMUSIC_API_URL` | L-1124 Web API 的地址，Compose 中为 `http://qqmusic-api:8080`。 |
| `QQMUSIC_COOKIE` | QQ 音乐账号 Cookie；不填时匿名请求。 |
| `QQMUSIC_QUALITY` | 音质等级：`MP3_128`、`MP3_320`、`FLAC`。 |
| `QQMUSIC_DELIVERY_MODE` | `DIRECT_URL` 或 `SERVER_DOWNLOAD`。 |
| `PRIVATE_DJ_SOURCE` | 私人 FM / DJ 的唯一提供源：`netease` 或 `qqmusic`。QQ 音乐只提供 FM，选择它时私人 DJ 会提示不可用。 |

**队列、播放与缓存**

| 变量名 | 说明 |
|:--|:--|
| `QUEUE_MAX_SIZE` | 播放队列最大长度。 |
| `QUEUE_HISTORY_SIZE` | 播放历史保留数量；队列为空时从历史随机选曲。 |
| `QUEUE_MAX_USER_SONGS` | 单个用户在队列中允许的最大点歌数量。 |
| `PLAYLIST_IMPORT_LIMIT` | 导入歌单时的最大歌曲数限制。 |
| `SYNC_BROADCAST_INTERVAL_MS` | 播放状态广播间隔（毫秒）。 |
| `CACHE_MAX_SIZE` | 本地音乐缓存上限，支持 `512MB`、`1GB`、`2GB` 等写法。 |

**聊天与安全**

| 变量名 | 说明 |
|:--|:--|
| `CHAT_HISTORY_LIMIT` | 聊天历史记录保留数量。 |
| `CHAT_MIN_INTERVAL` | 聊天发言最小间隔（毫秒）。 |
| `CHAT_MAX_LENGTH` | 单条聊天消息最大长度（字符）。 |
| `AUTH_RATE_LIMIT_ENABLED` | 是否开启密码验证频率限制。 |
| `AUTH_MAX_ATTEMPTS` | 密码验证最大尝试次数。 |
| `AUTH_WINDOW_SECONDS` | 密码验证统计时间窗口（秒）。 |
| `AUTH_BLOCK_DURATION` | 超过尝试次数后的封锁时长（秒）。 |

---

## Windows 启动器

如果你想在 Windows 上快速运行，而不想折腾 Docker、Java 或 Node.js 环境，可以使用 **一键启动器**。
<img width="1010" height="713" alt="Snipaste_2026-05-21_10-45-11" src="https://github.com/user-attachments/assets/2402f66e-4a52-423f-a32c-c0478c84a283" />

### 获取与使用
1.  前往 [GitHub Releases](https://github.com/pluviiter/MusicParty/releases) 下载最新的 `MusicParty.exe`。
2.  将 `MusicParty.exe` 放置在你喜欢的文件夹中。
3.  **直接双击运行**：
    *   启动器会自动释放内置的 JRE、网易云 API、QQ音乐 API、Cloudflare Tunnel，以及 Java 核心程序；按配置启动相应服务。
    *   所有的配置、运行环境、缓存都保存在 EXE 同级目录下。
4.  在 UI 界面上修改端口、密码、网易云 Cookie 等配置，点击“启动系统”即可。
5.  系统就绪后，点击界面上的“打开网页”即可。
6.  如果需要邀请外网朋友，启动前打开 **Cloudflare Tunnel 内网穿透**。隧道连接后，启动器会显示临时公网地址，可以复制或直接打开。不需要公网IP、域名或Cloudflare账号，也不用在路由器上做端口转发。
7.  停止系统或退出启动器时，应用和启动器创建的隧道会一并关闭。更新前先停止并退出启动器，备份配置和数据，再覆盖EXE；内置运行环境会自动更新，通常不需要手动删除 `bin` 文件夹。

### Cloudflare Tunnel

*   开关位于启动器界面，修改后在下次启动系统时生效。只转发应用的HTTP端口，不对外开放网易云API或QQ网关端口。
*   使用免登录的 **Quick Tunnel**，生成 `https://….trycloudflare.com` 临时地址。地址每次启动可能变化，停止隧道后失效；启动器不会把临时地址写入配置。
*   **开启即向公网开放服务**。管理员密码为空或使用默认的 `admin123` 时，启动器只显示风险提示，不阻止启动。建议设置强管理员密码与房间密码，不要把成员凭据或管理员密码发给其他人。知道链接不等于有访问权限，成员仍需遵守房间的密码设置。
*   隧道启动失败、超时或运行中退出会停止本次应用服务，并清除已显示地址，避免把失效链接当成可用服务。请查看启动器提示；不需要穿透时关闭开关再启动。
*   Quick Tunnel主要用于临时分享，**没有在线率保证**，最多支持200个并发处理中请求，不支持SSE。实际访问、WebSocket同步和音频传输仍受网络及Cloudflare政策限制；长期公开部署建议使用自己的正式隧道或服务器。详见 [Cloudflare Quick Tunnel说明](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)。

---

### 移动端
在移动端环境中，由于对浏览器的限制，在后台播放或锁屏播放时，经常会出现断联，失去同步等情况
为了解决这个方法，安卓端可以下载Release中的APK（本质套壳浏览器），苹果则可以尝试在浏览器中将网页添加到桌面。

---

## 房间密码
<img width="622" height="427" alt="Image_2026-05-21_10-53-45_s10ixfb1 qoj" src="https://github.com/user-attachments/assets/1f5eeb44-4a21-4dc5-8399-753b9f16b88a" />

部署后首次启动需要设置房间密码，其他成员只有使用密码才能进入。
也可以设置为public公开，此时无需密码即可进入。
密码可以在管理员面板中更改。

---

## 用户歌单
可以搜索并选择网易云、Bilibili或QQ音乐用户，查看其歌单/收藏夹，再按房间限额导入。绑定决定查询目标，不是登录该用户，不支持直接输入UID；查询请求携带对应音源配置的Cookie，未配置时不附带账号Cookie；上游自行决定是否采用该身份及如何缓存。能否访问由上游决定，不额外判断资源是否私密；失败不会回退成配置账号的收藏。

---

## 聊天框命令

在前端**聊天窗口**中可以输入以下命令：
*   `//clear`: 从播放队列中清空自己点播的所有歌曲。
*   `//stream`: 获取自己的直播流链接（需要开放直播流）。
*   `//admin`: 打开管理员控制面板（需要管理员密码）。

---

## 播放模式与排序
点击底部播放模式按钮，向上展开全部候选模式并选择：顺序播放、轮询播放、随机播放、单曲循环。

* **随机播放**：从可用歌曲池随机选曲，不再按成员轮流；保留歌单时仍保留原有的首播优先与轮次内避免重复机制。
* **轮询播放**：原来的公平随机，按成员每人一首轮流播放。队列按成员分组，清空歌单按钮左侧的「个人随机 / 个人顺序」只决定轮到自己时如何选歌，不改变房间模式或其他成员的偏好。
* 随机和轮询均沿用管理员的离线成员过滤设置；只有离线歌曲时仍回退到可用歌曲。
* **顺序模式排序**：已设置昵称的成员可拖动任意成员的普通歌曲，跨用户调整全局播放顺序；也可以聚焦手柄后按上下方向键。置顶歌曲、私人 FM 和系统条目仍保持原槽位。
* **轮询模式排序**：只能调整自己的普通歌曲，其他成员所在槽位不变；个人顺序选曲时按调整后的顺序播放。随机播放与单曲循环也只允许调整自己的普通歌曲，随机选曲不保证下一首。
* 全局队列仍是一份共享顺序，轮询歌单是其中该用户的子序列，不单独保存另一份模式专属顺序。排序请求携带开始操作时的播放模式，切模式后迟到的请求会被拒绝。

---

## 投票切歌
管理员面板可以开启投票切歌，开启后，只有当选择切歌的人数超过设置的阈值比例后才会切歌，此外，也可以设置在歌曲播放的前多少秒内无法投票。

---

## 置顶
在轮询播放中，播放列表会按成员分组，此时置顶逻辑有所变化。第一次点击置顶后，歌曲只会在该成员的列表中置顶，即轮替到该成员时，此歌曲优先播放。
对已经置顶的歌曲再次点击置顶，才会全局置顶，即无视轮替，下一首 必定是指定歌曲。

---

## 点赞
播放时，点击中间的封面可以对当前歌曲点赞，会有对应的视觉效果。
在搜索页面的LIKESONG选项卡下可以看到所有点赞的歌曲，可以重新添加到播放列表或者前往源页面。
注意记录的点赞歌曲是本地保存的，清理浏览器缓存丢失记录。

---

## 私人电台 / 私人DJ
在管理员面板的"私人电台"模块开启后，可以接入所选音源的推荐内容。部署配置 `PRIVATE_DJ_SOURCE` 默认使用网易云；QQ 音乐不提供私人推荐，因此该模块只在声明推荐能力的音源下可用。推荐使用该音源配置账号的凭据，与成员绑定的歌单无关。

**两种模式**（可随时切换）：
*   **私人FM**：根据账号的收听习惯持续推荐歌曲。
*   **私人DJ**：由 AI 主播语音点评/介绍歌曲，**先播放语音、再播放歌曲**。

**三个功能开关**：
*   **填充空白**：当播放队列没有有效歌曲时，不再从历史记录随机抽选，而是播放私人FM/DJ。
*   **加入队列**：在**随机播放或轮询播放**模式下生效，让私人FM 参与选曲——随机播放模式下它是一首"播完立即重新加入"的歌，轮询下它被视为一个用户参与轮替。开启「播完保留歌单」时，FM 在随机播放中每轮最多出现一次、在轮询播放中占一个成员轮次；关闭保留时按普通候选参与选曲。此功能**固定使用私人FM**，不应用私人DJ。
*   **播放托管**：完全无视播放队列，只持续播放私人FM/DJ（期间点歌照常入队，关闭托管后接续播放）。

---

## 管理员面板
在聊天窗口输入//admin并输入管理员密码后，可以进入管理员面板修改配置。
<img width="1254" height="823" alt="Image_2026-05-21_16-14-47_uvmklady ree" src="https://github.com/user-attachments/assets/9ea3ffac-0db0-434d-a972-642a8cec89bd" />

* 修改部署时的配置参数。
* 锁定播放，切歌，随机按钮以防止用户滥用。（建议保持播放按钮锁定，防止某一个用户因为卸下耳机等行为导致的自动暂停）
* 随机播放与投票切歌相关配置。
* 配置私人电台/私人DJ。
* 开关直播流功能。
* 更新歌曲源的凭证，或是启停歌曲源。
* 对播放列表或者聊天记录进行清理。
* 是否允许进度条拖动。
* 是否允许认领离线成员歌单。

---

## 历史记录
播放过的歌曲会被加入历史记录，当没有歌曲播放时，会随机从历史记录中播放歌曲。

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
浏览器打开bilibili，登录，进入我的收藏页面，按F12开启控制台，选择网络，然后收藏夹翻页，在控制台中寻找list开头的请求，然后找到请求头中的 Cookie，将所有内容复制。
<img width="1604" height="921" alt="Snipaste_2026-08-01_19-48-25" src="https://github.com/user-attachments/assets/4d9e9a29-e4d9-4e2f-877e-e22fde7b1f56" />

* **QQ音乐**
浏览器打开QQ音乐网页版并登录，按F12开启控制台，选择网络，随便点开一个 `musicu.fcg` 请求，复制请求头中完整的 Cookie（含 `musicid`、`musickey`）。

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
5.  修改 `src/constants/tutorial.js` 后，在同一目录执行 `node scripts/export-tutorial.mjs`，同步更新 `docs/新手教程全部文案.txt`。

### 后端 (Java)

1.  环境要求：JDK 21, Maven 3.x, 并已部署Netease Cloud Music Api。
2.  配置：修改 `src/main/resources/application.yml` 或通过 IDEA 环境变量传入 `BILIBILI_COOKIE` 等配置。
3.  运行：
    ```bash
    mvn spring-boot:run
    ```

### 完整构建

建议直接使用 Docker 镜像进行生产环境运行。构建镜像请参考根目录下的 `Dockerfile`。

---

## 免责声明

*   本项目仅供学习交流使用，请勿用于商业用途。
*   本项目涉及的音乐平台接口（网易云音乐、Bilibili、QQ音乐）均为非官方接口，项目开发者不对接口可用性及账号风险负责。Cloudflare Tunnel同样受上游服务政策与网络可用性限制。
*   QQ音乐源的上游许可、对应源码与打包说明见 [第三方许可说明](launcher/THIRDPARTY-LICENSE.md) 和 [QQ API 契约](docs/qqmusic-gateway-contract.md)。
*   请尊重版权，支持正版音乐。

---

## 贡献

欢迎提交 Issue 和 Pull Request！无论是修复 Bug、新增功能还是优化文档。PR请提往DEV分支。

## License

MIT License
