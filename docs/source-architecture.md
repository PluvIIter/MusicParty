# 音源架构与迁移说明

本轮按 2026-11-11 群内确认实施。配置只控制音源启用和音频交付类型；能力由代码声明。Cookie 用于受限音频解析，并明确允许用于部署指定音源的私人 FM/DJ；绑定用户只是浏览公开收藏的目标，管理员仅配置应用。LIKESONG、绑定目标和房间密码由本机前端保存。

## 模块

- `source/SourceRegistry`：唯一音源注册表、能力目录、有效启停、交付类型和媒体授权epoch。现有两源保留稳定ID；新源无需改通用页面。
- `service/api/IMusicApiService`：音源边界。metadata / resolveMedia分离；收藏分页由适配器转换。旧方法暂供兼容，不在核心播放器触发下载。
- `resource/`：统一公开收藏类别、Container、ResourcePage、绑定查询上下文的签名游标、容器内容和有限跨页读取。
- `media/MediaDeliveryService`：直链或服务器下载、解析合并、准备状态、媒体租约、恢复、缓存授权与服务器输入。
- `service/LocalCacheService`：流式有界下载、临时文件原子提交、带representation的哈希文件名/索引、LRU与播放/HTTP/Radio pin。
- 房间调度：只使用Registry与MediaDelivery；保留queueId、playbackId、seekNonce、公平轮询、首播账本及保留歌单行为。
- `ContainerImportService`：通用容器批量入队，元数据与分页共用完整30秒预算；提交在用户、音源、队列的固定锁序下重验身份与音源epoch，受配额、来源校验和请求幂等约束。提交后的预取失败不把已成功入队伪报为失败。
- Vue：`stores/sources`发现目录，通用搜索/收藏类别/容器读取；`useLikedSongs`仍是独立本机数据。

## 配置

```yaml
app:
  music-api:
    sources:
      netease:
        enabled: true
        delivery-mode: DIRECT_URL
      bilibili:
        enabled: true
        delivery-mode: SERVER_DOWNLOAD
```

两种模式不可与房间的随机、轮询等选曲模式混淆。默认网易直链、B站服务器下载；两个现有适配器均声明支持两种方式，但实际资源受上游策略、格式与账号音频权限限制。服务器下载失败不隐式切换直链。

环境变量为 `NETEASE_ENABLED`、`BILIBILI_ENABLED`、`NETEASE_DELIVERY_MODE`、`BILIBILI_DELIVERY_MODE`。连接地址/媒体Cookie/音质保留在各适配器技术配置。源能力没有部署开关。交付模式需改部署重启；管理台可启停，不能借运行时设置覆盖部署模式。

旧enabled配置和存档迁移至同一有效源状态；内置配置不再用新字段默认true覆盖旧CLI/YAML中的false，显式新sources配置优先。存档只保存启停、不保存交付模式。未知已删除源不映射至另一音源。`SourceRegistry.update`改变epoch，旧媒体票据不能在禁用再启用后复活。

## 公开收藏合同

- `/api/v2/sources`：可用源目录、代码能力、当前/支持交付方式。
- `/api/v2/sources/{source}/collection-categories`：适配器实际实现的类别。
- `/api/v2/sources/{source}/collections/{category}?userId=&cursor=&limit=`：明确公开目标的分类内容。
- `/api/v2/sources/{source}/containers/{type}/{id}` 及 `/tracks`：统一容器元数据和歌曲分页。
- `/api/v2/sources/{source}/tracks/{id}/album`、`/lyrics`：能力驱动的专辑与歌词。
- `/api/v2/playbacks/{playbackId}/media`、`/media/refresh`：当前播放实例的媒体获取与恢复。

列表为 `ResourcePage {items,nextCursor,total}`；终止仅由nextCursor=null确定。过滤失效条目后仍可能返回空页+下一游标。游标绑定source、target、类别/容器、limit和目录版本；不能跨目标重用。

类别entryKind支持TRACK（直接歌曲列表）和CONTAINER（歌单/专辑/收藏夹）。TRACK由`publicCollectionTracks`返回Music，CONTAINER由`publicCollections`返回Playlist映射Container；不会把歌曲收藏伪装为容器。容器详情和曲目分页向适配器明确传递collectionType与id，不再把不同类型都当作同一个playlist；兼容旧playlist方法时必须验证类别。新源按能力实现对应方法。

当前实现网易公开歌单、B站公开收藏夹。未实现“配置Cookie账号收藏”；网易 `album/sublist` 只面向登录账号，不满足公开绑定目标约束，旧入口返回410，界面不再下发该类别。公开专辑详情与歌曲所属专辑保留，两者不是收藏专辑类别。

公开请求不携带配置账号Cookie；B站只允许匿名设备buvid信息用于公共接口。绑定UID不证明账号所有权。不公开、上游拒绝和网络失败不得返回伪造成功空收藏。未绑定明确提示绑定，不回退Cookie账号。

兼容的旧搜索、收藏、曲目、专辑及歌词接口复用ResourceService的能力、参数与超时约束，并与v2共享房间访问检查及结构化错误；旧专辑详情不再读取已分离的legacy启用字段。只有公开页面装饰配置保留无需房间口令访问。

## 私人 FM/DJ 的明确例外

私人 FM/DJ 是应用功能，由部署配置 `app.music-api.private-dj.source`（环境变量 `PRIVATE_DJ_SOURCE`，默认 `netease`）指定唯一推荐提供源。目前只有网易云实现，使用该源配置 Cookie 请求原生 `personal_fm` / `aidj/content/rcmd`。提供者代码分别声明FM与DJ，DJ必须以FM为基础。后端在房间配置的 `privateDj` 中下发 `source`、`fmSupported`、`djSupported`；这些标志只反映部署指定源，前端仅在对应标志为true时显示入口。指定源只有FM时不显示私人DJ，后端也拒绝DJ请求；不能因另一已启用源支持DJ就显示。不会按注册顺序挑选提供者，也不会在失败时换成其他源或公开歌单。源禁用、不支持、Cookie缺失/失效或上游拒绝均明确报错。

歌曲段交给统一媒体服务，DJ语音标记VOICE并走同源配置的直链或服务器下载，Radio使用已验证的语音关联而不把voiceId当歌曲解析。语音不进普通歌曲历史、不走歌曲刷新；原生语音时长必须有效（0到600秒之间，不含0）。填空、加入队列、托管保留。内部合成FM标记是调度控制项，不是音源。

推荐批次按指定源、模式、Cookie revision与启停epoch隔离；切换模式、关闭、换Cookie后丢弃旧批次及迟到结果。语音下载采用有界独立通道，避免普通歌曲预取队列阻塞；同段重试复用准备身份而不重复下载。这项Cookie例外仅用于私人推荐，**个人收藏仍查询绑定公开用户，不读取配置账号的收藏**。

## 媒体、身份与同步

`ResourceKey(sourceId,id)`用于跨源判重、状态及缓存，内部编码防分隔符歧义。queueId与playbackId不作为内容缓存身份。URL不当成稳定内容身份。

准备/网络在房间锁外；提交检查generation、启用状态与授权scope。预取READY不算首播；成功服务端起播才记账，失败和迟到结果不消耗首播。禁用源的队列条目保持待处理，不被顺序模式弹出；失败下载在既有失败冷却之后可重新准备。

直链必须原生浏览器可用且不要求秘密header。服务器下载整文件准备后以随机短期媒体ticket交付，支持Range/后缀Range；旧公开静态媒体目录不再开放。ticket核验源、凭据revision与启用epoch。恢复同实例不改playbackId、seekNonce、房间锚点或队列；不同representation（例如整曲变试听）拒绝沿用时间轴。

Radio从同一媒体服务获取SERVER输入，不按平台补头或全局降级HTTPS，并执行适配器的radioInput能力门禁。媒体区分编码representation与内容timeline：浏览器和服务器可使用不同格式，但不能把整曲改成试听后继续沿用旧进度。Radio只接受本进程签发、保留原timeline的播放对象；未知重建对象拒绝输入。凭据或epoch变更使旧准备结果/运行输入失效；清空或切换时作废准备请求，迟到成功或空完成不会遗留解析标记。首次临时断流刷新后可立即重启，连续故障才进入退避，停止转码时同步释放pin并恢复静音填充。

缓存资产包含representation、timeline与授权命名空间；同源同ID的不同内容版本使用不同下载资产，逻辑资源状态通过当前版本映射读取。播放pin绑定实际签发的资产，不随尚未接受的刷新候选移动。为避免进程重启后revision归零使旧VIP音频被其他凭据复用，当前采用随机进程授权session：重启后旧文件保留索引和LRU计量，但必须重新下载授权，而不直接签发旧文件。未来可实现重新授权后复用同内容字节；本轮不作此优化承诺。

## 前端、本机数据与部署

源标签、能力、专辑入口、类别、容器以及源站externalUrl由目录/适配器结果驱动。图标/action使用本地白名单，不执行服务器脚本、组件或任意URL模板。媒体SW缓存原有CDN allowlist保留为安全优化策略，不作为源发现或能力判断。

绑定目标本机保存，请求显式传入；移除后端绑定命令。切源、换目标、换容器与关闭弹窗时用请求代次/取消保护；重新打开或从本机喜欢返回时恢复被取消的读取，保留分页游标且不重复追加。容器提供显式“加载更多”，空页带下一游标或不足以滚动时也能继续读取。

LIKESONG不按启用源过滤，既有来源/快照保留；禁用源仅在点播时提示不可用，不换源、不删除点赞。升级前缺少externalUrl的条目通过经过房间鉴权的适配器元数据端点解析源页面，允许已禁用的已知音源，不发起媒体解析，也不打开旧音频URL或改写存量点赞记录。

Windows启动器使用`sources`映射，迁移旧JSON包括显式false，只在需要时启动网易sidecar，传递相同Java源参数。配置文件0600保存，Cookie字段掩码并标为仅音频。Docker新增交付环境变量；如仅用B站可执行 `docker compose up -d --no-deps music-party`（须同时关闭网易源），不要求主应用等待可选源可达。

Android继续服务端WebView与媒体桥，未添加每平台原生播放器。

## 验证及已知边界

本轮测试覆盖：第三源stub/动态目录、TRACK与CONTAINER类别、跨源同ID、长字符串ID、公开目标隔离、无Cookie收藏、游标串用/空页、通用导入幂等/限额、两种媒体交付、Range、媒体授权更换/重启、不同内容刷新、Radio失效、调度与旧模式回归。

运行结果详见PR。不得把mock测试当成真实平台权限或长期可播放率验证。

已知边界：

1. 尚未用真实登录账号、CDN、FFmpeg长期音频和Android真机进行端到端验证。
2. 上游媒体URL经过协议/IP/域名校验，拒绝任意URL输入和重定向；DNS校验未绑定到实际传输连接，仍有DNS重绑定TOCTOU，部署需限制出站网络。不存在开放任意URL代理。
3. B站内容representation/过期元数据较粗；不能承诺上游签名或格式永久稳定。
4. 旧裸ID缓存无法可信归属的不会盲目迁移；进程重启需重新准备音频。
5. 完整Windows原生可执行打包与Android真机未在当前宿主验证；启动器前端可构建，Go配置测试与原生构建限制在PR如实列出。

上线前备份队列与本机存储；部署新前端后提示旧页面刷新。旧客户端不能表达的源/类型不要错投默认源。回滚旧二进制需恢复兼容存档备份，而非直接覆盖新状态。不开自动合并。
