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

## 版本与数据

本分支只支持当前网络协议和配置格式，不保留旧版接口或配置别名。请同时更新服务端、Web 客户端和启动器。房间旧存档会尝试备份后导入并改写为当前格式，读取失败时尝试备份并允许后续覆盖；内置备份失败不阻止继续写入，升级前请务必自行备份。音源启停只配置 `sources.<sourceId>.enabled`，不再读取适配器连接配置中的启用字段。

## 新手教程

进入房间后，点顶部“新手教程”查看快速入门或按主题搜索完整说明；精简模式下也有入口。教程涵盖点歌、用户收藏、本机喜欢、队列与模式、投票和权限、FM/DJ、手机后台及常见问题。

[教程全部文案（TXT）](docs/新手教程全部文案.txt)与界面共用同一份内容。修改 `music-party-web/src/constants/tutorial.js` 后，在 `music-party-web` 目录执行 `node scripts/export-tutorial.mjs` 更新文本。

## 核心特性

*   **多平台支持**：
    *   **网易云音乐**：支持搜索、歌单导入。
        * 网易云的API实现依赖[NeteaseCloudMusicApi](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced)。
    *   **Bilibili**：支持搜索、收藏夹导入。 
        * 注意：b站源由于防盗链问题，方案是先下载本地缓存，然后由服务器将音频提供给用户，可能会占用较多网络带宽，消耗大量流量。
        * 此外，b站源风控现象严重，极不稳定，建议能用网易云就用网易云。
    *   **QQ音乐**：支持歌曲搜索、歌词（含翻译）、专辑、歌单导入与个人收藏，默认关闭。
        * QQ源直接对接 [L-1124/QQMusicApi](https://github.com/L-1124/QQMusicApi) 自带的Web API；Windows启动器已内置服务与Python环境，无需另外安装。
        * 会员歌曲仍需要有效账号凭据，不支持DRM加密音频和私人DJ语音。
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

| 变量名                       | 必填 | 说明                                                                          |
|:--------------------------|:---|:----------------------------------------------------------------------------|
| `APP_AUTHOR_NAME`         | 否  | 页面显示的作者名字，地点在左上角标题后面。默认为 `ThorNex`。                                         |
| `APP_BACK_WORDS`          | 否  | 中间专辑封面后方的装饰性背景字，强制大写。默认为 `MUSIC PARTY`。                                     |
| `ADMIN_PASSWORD`          | 是  | 管理员密码，用于打开管理员面板。                                                            |
| `NETEASE_ENABLED`         | 是  | 网易云源是否开启。                                                                   |
| `NETEASE_API_URL`         | 是  | NeteaseCloudMusicApi 的地址，Docker 部署时默认为 `http://netease-api:3000`。           |
| `BASE_URL`                | 否  | 服务的域名（带协议）。用户获取直播流链接时，拼接在前面。默认为 `http://localhost:8848`。                    |
| `BILIBILI_ENABLED`        | 否  | B站源是否开启。                                                                    |
| `BILIBILI_COOKIE`         | 否  | 各类上游查询均使用此Cookie；未配置时使用匿名设备信息。是否可访问由上游权限和风控决定。 |
| `NETEASE_DELIVERY_MODE` | 否 | `DIRECT_URL`（默认）或 `SERVER_DOWNLOAD`。 |
| `BILIBILI_DELIVERY_MODE` | 否 | `SERVER_DOWNLOAD`（默认）或 `DIRECT_URL`；直链格式及可用性取决于上游。 |
| `PRIVATE_DJ_SOURCE` | 否 | 私人 FM / DJ 唯一提供源，默认 `netease`；QQ 音乐不提供推荐能力，选择 `qqmusic` 会明确提示不可用。 |
| `QQMUSIC_ENABLED` | 否 | QQ音乐源，默认 `false`。 |
| `QQMUSIC_API_URL` | 否 | L-1124原生Web API地址；随包服务为本机动态端口，Compose 为 `http://qqmusic-api:8080`，独立上游默认端口为8080。 |
| `QQMUSIC_COOKIE` | 否 | 与网易云一致的 Cookie 串（`musicid=...; musickey=...`），可补充上游支持的其它 Cookie 字段。用于QQ所有上游查询，未配置时匿名请求。 |
| `QQMUSIC_QUALITY` | 否 | `MP3_320`（默认）、`MP3_128`、普通 `FLAC`；不支持DRM加密格式。 |
| `QQMUSIC_DELIVERY_MODE` | 否 | 默认 `DIRECT_URL`（与网易云一致）；需要服务器统一转发时配置 `SERVER_DOWNLOAD`。 |
| `NETEASE_COOKIE`          | 否  | 网易云账号 Cookie。用于所有上游查询及音频、FM/DJ，未配置时匿名；不会改变所查询的用户或歌单。                                          |
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
    *   启动器会自动释放内置的 JRE、网易云 API、QQMusicApi Web 服务及其 Python 运行环境、Cloudflare Tunnel，以及 Java 核心程序；按配置启动相应服务。
    *   所有的配置、运行环境、缓存都保存在 EXE 同级目录下。
4.  在 UI 界面上修改端口、密码、网易云 Cookie 等配置，点击“启动系统”即可。
5.  系统就绪后，点击界面上的“打开网页”即可。
6.  如果需要邀请外网朋友，启动前打开 **Cloudflare Tunnel 内网穿透**。隧道连接后，启动器会显示临时公网地址，可以复制或直接打开。不需要公网IP、域名或Cloudflare账号，也不用在路由器上做端口转发。
7.  停止系统或退出启动器时，应用和启动器创建的隧道会一并关闭。更新前先停止并退出启动器，备份配置和数据，再覆盖EXE；内置运行环境会自动更新，通常不需要手动删除 `bin` 文件夹。

### Cloudflare Tunnel

*   开关默认关闭，修改后在下次启动系统时生效。只转发应用的HTTP端口，不对外开放网易云API或QQ网关端口。
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

## QQ音乐源

QQ源默认关闭，直接使用 [L-1124/QQMusicApi 自带的Web服务](https://github.com/L-1124/QQMusicApi/tree/c046788dd1f11daacc74da487cbbf1bcd8ac6f33/web)，不再维护额外的HTTP网关，也不调用第三方公用会员池。

*   支持歌曲搜索、详情、普通LRC歌词、专辑、歌单与分页导入。
*   支持直链和服务器下载两种交付方式，接入现有播放器、缓存和直播音频流。
*   不提供私人FM／DJ：本音源不声明推荐能力，私人推荐仍由部署指定的其他音源提供。
*   用户绑定决定查询哪位用户；请求使用部署配置的QQ账号凭据，读取权限由上游判断，不额外过滤隐私标记，也不会把目标换成配置账号。

Docker启用步骤（使用本PR构建的主应用镜像，旧发布镜像不包含QQ功能）：

1. 在本地 `.env` 设置 `QQMUSIC_ENABLED=true`。需要账号权限时设置单个 `QQMUSIC_COOKIE`，与网易云一样直接粘贴 Cookie 串，格式示例：`musicid=123456789; musickey=Q_H_L_...`，不要提交该文件。凭据必须来自合法账号，不能用目标用户ID代替。
2. 执行 `docker compose --profile qqmusic up -d --build`。Compose直接使用固定上游源码和其自带的 `web/Dockerfile` 构建QQ API，与网易云API同处内部网络；Java连接 `http://qqmusic-api:8080`，无需补丁入口。不映射主机端口；默认不额外挂载QQ账号或状态目录，需要持久化时按上游文档配置。
3. 管理员可在管理界面替换或清空QQ凭据，旧凭据不回显。运行期更新不写入房间存档，重启后仍以部署/启动器配置为准。
4. 收藏绑定只决定查询目标，不会改变登录账号；QQ 音乐不提供私人FM，因此不建议把 `PRIVATE_DJ_SOURCE` 指向它。

**Windows启动器**：在音源设置中启用QQ即可，默认使用随包的上游Web服务，无需安装Python或填写服务地址。需要使用独立部署的API时，切换到外部模式并填写根地址；启动器不会关闭外部服务。QQ关闭时不启动API。

**外部API**：按上游说明部署匹配版本，使用原生 `web/run.py` 或上游Docker镜像，填写API根地址即可，不需要随附启动补丁。MusicParty正常传递配置Cookie；哪些接口匿名、如何使用凭据和缓存，完全遵循上游原生行为，不修改路由认证或共享缓存。请勿把API直接公开到公网。与网易云一致，直接粘贴浏览器 Cookie 串；`musicid` 与 `musickey` 必须成对出现，可选 `openid`、`refresh_token`、`access_token`、`expired_at`、`unionid`、`str_musicid`、`refresh_key` 按真实 Cookie 填写；旧的 `loginType`、`encrypt_uin` JSON 字段不再使用。

独立部署与接口说明见 [QQ API说明](sidecars/qqmusic/README.md)，分发时请保留 [上游许可说明](sidecars/qqmusic/UPSTREAM-NOTICE.md)及随包源码。

QQ音频只支持有权取得的普通MP3/FLAC，不解密DRM；按上游Web正常响应处理，不再因模型缺少原始试听或可见性标记而一律拒绝。会员也不保证所有地区/单购歌曲可播。服务器缓存、下载和Radio不自动获得音乐再分发授权；请核对平台规则与版权。公开匿名样本的验证不能替代登录账号、CDN、多端和Radio实测。

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
*   请尊重版权，支持正版音乐。

---

## 贡献

欢迎提交 Issue 和 Pull Request！无论是修复 Bug、新增功能还是优化文档。PR请提往DEV分支。

## License

MIT License
