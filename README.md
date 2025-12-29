#  Music Party 
> 一个高颜值的实时在线协作听歌平台。
>
> *本项目灵感来源于 [EveElseIf/MusicParty](https://github.com/EveElseIf/MusicParty)，开发过程大量使用LLM。*

---

##  特性

### 核心体验
*    网易云支持：支持网易云音乐的搜索与播放。BILIBILI支持正在开发中。
*    实时同步：基于 WebSocket (STOMP)，实现播放进度、歌词滚动、播放队列、暂停/播放状态的全员毫秒级同步。
*    响应式布局：完美适配 PC 与移动端。

### 功能细节
*   **点歌系统**：
    *   支持关键词搜索 。
    *   支持导入网易云 (绑定 ID)。
    *   支持批量导入歌单所有歌曲。
*   **队列管理**：
    *   查看待播放列表。
    *   支持置顶歌曲 。
    *   支持移除歌曲 。
    *   随机播放模式 (智能算法，防止同一用户连续霸屏)。
*   **用户系统**：
    *   临时用户身份，支持自定义昵称。
    *   显示在线用户列表，当前点播人高亮显示。
    *   **房间保护**：支持设置房间密码，防止未授权用户进入操作。
    *   附带一个简易的聊天框。

---

##  快速开始 (Docker 部署)

这是最推荐的部署方式。

### 1. 准备环境
确保服务器安装了 [Docker](https://www.docker.com/) 和 [Docker Compose](https://docs.docker.com/compose/)。

### 2. 获取 Cookie
为了正常使用搜索和播放功能，你需要获取网易云和 B 站的 Cookie：

*   **网易云音乐 (`NETEASE_COOKIE`)**: 登录网易云网页版，F12 控制台 -> Application -> Cookies，复制完整的 Cookie 字符串。

### 3. 配置 docker-compose.yml
创建或修改 `docker-compose.yml`，填入你的配置：

```yaml
services:
  # 1. 网易云音乐 API 服务 (第三方)
  netease-api:
    image: moefurina/ncm-api:latest
    container_name: music-party-netease
    restart: always
    environment:
      - PORT=3000
    networks:
      - music-net

  # 2. Music Party 主应用
  music-party:
    # 如果你是拉取源码构建
    build: .
    container_name: music-party-app
    restart: always
    ports:
      - "8080:8080"
    environment:
      # 管理员密码 (用于执行 //RESET 等指令)
      - ADMIN_PASSWORD=your_admin_secret
      # 网易云 API 地址 (容器内互联)
      - NETEASE_API_URL=http://netease-api:3000
      # 必填：网易云 Cookie
      - NETEASE_COOKIE="你的完整COOKIE..."
    depends_on:
      - netease-api
    networks:
      - music-net
    volumes:
      - ./logs:/app/logs

networks:
  music-net:
    driver: bridge
```

### 4. 启动
```bash
docker-compose up -d
```
访问 `http://localhost:8080` 即可开始 Party。

---

##  本地开发

### 前端
```bash
cd music-party-web
npm install
npm run dev
```
*前端默认代理 API 到 `localhost:8080`*

### 后端
1.  启动本地 NeteaseCloudMusicApi (端口 3000)。
2.  修改 `src/main/resources/application.yml` 中的配置 (Cookie/Sessdata)。
3.  运行 Spring Boot 应用。

---

##  使用指南

### 搜索与点歌
1.  点击顶部栏的 **SEARCH** 按钮。
2.  输入关键词搜索，点击右侧 `+` 号加入队列。
3.  **导入歌单**：在左侧面板输入用户 ID 绑定账号，可以查看和加入歌单里的音乐。

### 房间控制
初次启动时，系统处于 **未初始化状态**。
1.  **设置房间密码**：在进入页面时的 AuthOverlay 中设置。
2.  **修改昵称**：点击左侧用户列表中的自己名字即可重命名。

### 管理员指令 (Admin Commands)
在搜索框中输入以下指令（不区分大小写）并回车：

| 指令 | 描述 |
| :--- | :--- |
| `//RESET` | **系统重置**。清空队列、停止播放、踢出所有代理任务、清除历史记录。需要验证管理员密码。 |
| `//PASS <new_pwd>` | **修改房间密码**。例如 `//PASS 123456`。需要验证管理员密码。 |
| `//OPEN` | **开放房间**。移除房间密码，允许任何人进入。需要验证管理员密码。 |

*(注：执行指令时会弹出红色的管理员密码输入框)*

---

##计划

以下是计划中的功能更新：

- 允许用户脱离同步，随意拖动进度条与暂停（本地播放）。
- 歌单全选、队列批量删除。
- 支持bilibili。
- 点击歌曲封面/标题快速打开对应平台源网页。
- 专辑名显示。
- 支持上传本地文件并在房间内广播。
- 通过歌手/专辑搜索（网易云限定）。
