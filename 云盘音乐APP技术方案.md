# 安卓云盘音乐 APP 技术可行性方案

> 结论先行：**可行**，且技术路径明确。OneDrive 是最容易上手的起点（官方 OAuth2 + Graph API，支持 Range 流式播放，可实现进度条拖动），Google Drive / 坚果云作为第二梯队，115 OpenAPI 是值得申请的中长期选项。

---

## 一、结论速览

| 维度 | 评估 |
|---|---|
| 技术可行性 | 高。REST + OAuth2，标准客户端/App 模式，无需服务器即可完成授权与播放 |
| OneDrive 接入难度 | 低~中。Graph API 文档完整，`Files.Read` 委托权限即可播放自己的文件 |
| 流式播放 + 拖动 | 支持。Graph 直链对 `Range` 返回 `206 Partial Content`（需把 Range 发给直链而非 `/content`） |
| 国内可用性 | OneDrive/Google 在国内网络质量一般；坚果云国内首选 |
| 主要风险 | Token 生命周期管理、预认证直链过期、后台播放合规、网盘 API 政策变动 |
| 工作量估算 | MVP（单网盘 + 播放 + 列表）约 3~5 人周；多网盘聚合 + 完整播放器约 8~12 人周 |

---

## 二、OneDrive 链路全解析（已核实官方文档）

### 2.1 认证：Android 公共客户端 + PKCE

Android 应用属于**公共客户端**（public client），没有安全的 client secret，因此必须走 **授权码 + PKCE**，且绝不能把 client secret 写进 APK。

**推荐用 MSAL Android**（微软官方维护，自动处理 PKCE、token 刷新、加密缓存）：

```gradle
dependencies {
    implementation 'com.microsoft.identity.client:msal:4.9.+'
}
maven {
    url 'https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Public/maven/v1'
}
```

**应用注册**（Azure Portal → App registrations → New registration）：
- 平台选 **Mobile and desktop applications**（不是 Web）
- 记下 Application (client) ID
- Redirect URI 配置为 `msauth://<你的包名>/<签名 SHA1 的 base64url 编码>`

`res/raw/auth_config.json`：

```json
{
  "client_id": "<APPLICATION_CLIENT_ID>",
  "redirect_uri": "msauth://com.yourpkg.music/<BASE64_URL_ENCODED_SHA1>",
  "broker_redirect_uri_registered": true
}
```

`AndroidManifest.xml`（回调 Intent Filter，scheme/host/path 必须与配置**完全一致**，否则登录后回不来）：

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<activity android:name="com.microsoft.identity.client.BrowserTabActivity">
  <intent-filter>
    <action android:name="android.intent.action.VIEW"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <category android:name="android.intent.category.BROWSABLE"/>
    <data android:scheme="msauth"
          android:host="com.yourpkg.music"
          android:path="/<BASE64_ENCODED_PACKAGE_SIGNATURE>"/>
  </intent-filter>
</activity>
```

**申请的 Scope**：
- `Files.Read` — 读取文件（委托权限足够播放自己的文件；应用权限才需要 `Files.Read.All`）
- `offline_access` — 拿 refresh token，长期免登录
- `User.Read` — 基本资料（可选，用于账号页展示）

> 签名哈希生成：
> `keytool -exportcert -keystore your.keystore -alias your_alias | openssl sha1 -binary | openssl base64 | tr '+/' '-_' | tr -d '='`
> **注意**：debug 签名和 release 签名不同，两个都要在 Azure 后台注册 redirect URI，否则正式包登录必挂。这是最常见的踩坑点。

**不用 MSAL 的替代方案**：手写 PKCE 流程也行（授权端点 `https://login.microsoftonline.com/common/oauth2/v2.0/authorize`，令牌端点 `.../token`），代码量不大，但会失去 broker（企业 SSO）、证书固定、token 缓存等能力。个人项目可手写，商业项目建议 MSAL。

### 2.2 目录树与文件列举

```
GET https://graph.microsoft.com/v1.0/me/drive/root/children
```
返回 `children[]`，每项含 `id`、`name`、`size`、`file.mimeType`、`@microsoft.graph.downloadUrl`、`eTag`、`lastModifiedDateTime`。

**增量同步（强烈建议用）**：拉全量树对大号 OneDrive 账号非常慢，用 delta 接口：
```
GET https://graph.microsoft.com/v1.0/me/drive/root/delta?token={deltaLink}
```
首次不带 token，服务器返回全量 + `deltaLink`；后续带 `deltaLink` 只返回变更。同步完把 deltaLink 存本地。

**筛选音频**：`file.mimeType` 以 `audio/` 开头，或按扩展名白名单（`.mp3 .flac .m4a .aac .ogg .opus .wav .wma .ape`）。注意 `mimeType` 可能为 `application/octet-stream`（扩展名被识别不出），所以**必须同时看扩展名**。

**按文件夹遍历**：`GET /me/drive/items/{itemId}/children`，递归时注意深度限制和 `folder` facet。

### 2.3 播放直链（最关键的一环）

**方式 A：直接取直链（推荐）**
```
GET https://graph.microsoft.com/v1.0/me/drive/items/{itemId}?select=id,@microsoft.graph.downloadUrl
```
拿到的 `@microsoft.graph.downloadUrl` 就是**预认证直链**：
- 请求时**不需要**带 `Authorization` 头
- **有效期很短（分钟级）**，要即取即用
- 支持 `Range`

**方式 B：走 `/content` 端点**
```
GET /me/drive/items/{itemId}/content   →  302 Found，Location = 同一个直链
```
最终也是拿到同一个直链。所以**两种方式等价**，方式 A 少一次 302 往返，更优。

**Range（拖动进度条）必须这样发**：
```http
GET <downloadUrl>
Range: bytes=0-1023
```
→ `206 Partial Content` + `Content-Range: bytes 0-1023/2048`

⚠️ **常见错误**：把 `Range` 发给 `/content` 请求。`/content` 只做重定向，不处理 Range。正确做法是：先拿直链 → 再把 Range 发给直链。ExoPlayer 用 `DefaultHttpDataSource` + `ResolvingDataSource` 可以实现"先解析直链再带 Range 拉取"。

**缓存策略（强烈建议）**：
```
Room 表：TrackCache(trackId, fileName, mimeType, size, bitrate, durationSec, localPath, etag, lastAccess)
```
- 用 `SimpleCache`（ExoPlayer 内置）做本地磁盘缓存，LRU 清理
- 以 `eTag` 作为缓存失效标识，文件更新后自动失效
- 预缓存：用户滚动列表时后台预取下一首的前 30~60 秒（`Range: bytes=0-5242879`）

### 2.4 媒体元数据与时长

ExoPlayer 的 `MetadataRetriever` 是针对本地文件的。云端文件需要在播放前用 Range 请求读取：
- **MP3/M4A**：读文件尾部 64KB 解析 ID3v2 / MP4 moov atom
- **FLAC**：读头部 STREAMINFO + VORBIS_COMMENT
- 若要在列表里显示时长/封面/艺术家，**服务端预解析一次并存库**是最优解（见第四节）

### 2.5 大坑：企业/学校账号的 OneDrive

`/me/drive` 指向的是**个人 OneDrive**。用工作或学校账号登录时：
- 个人 OneDrive 可能被管理员禁用 → `/me/drive` 返回 404 或空
- 此时文件在 **SharePoint 文档库**里，需要走：
```
GET /users/{userId}/drive
GET /sites/{siteId}/drive
GET /sites/{hostname}:/{sitename}:/drive
```
**产品设计上必须处理**：登录后先探测 `/me/drive`，若不可用则引导用户选择 SharePoint 站点，或在服务端做统一抽象。这是从"个人玩具"走向"能用的 App"的分水岭。

---

## 三、其他网盘横向对比

| 网盘 | 官方 API | 流式播放 | Range/拖动 | 国内网络 | OAuth 客户端模式 | 建议 |
|---|---|---|---|---|---|---|
| **OneDrive / Graph** | ✅ 官方，文档完善 | ✅ | ✅ 直链支持 | 一般 | ✅ PKCE / MSAL | **首选起点** |
| **Google Drive** | ✅ 官方 v3 | ✅ `alt=media` | ✅ 支持 Range | 一般（需代理） | ✅ PKCE，无 secret | **最佳第二选择** |
| **坚果云 Nutstore** | ✅ 官方 OAuth2 + REST + WebDAV | ✅ | ✅（WebDAV/Range） | **优秀** | ✅ 有 client secret | **国内首选** |
| **Dropbox** | ✅ 官方 API 成熟 | ✅ | ✅ | 差 | ✅ PKCE | 海外用户多时可加 |
| **115 网盘** | ⚠️ OpenAPI **内测中**，申请制 | ✅ 且官方提到提供**音视频在线转码与播放** | ✅ | 优秀 | 需申请 | **值得申请，中长期主力** |
| **阿里云盘** | ❌ 开放平台只对合作方；官方**明令禁止**把网盘当 CDN/对外提供公网服务 | 社区方案（aligo 等）能用但违反 ToS | — | 优秀 | — | **不建议做正式产品** |
| **百度网盘** | ⚠️ 仅企业版有完整 API；个人版开放平台基本不开放新应用 | — | — | 优秀 | — | 仅企业定制可考虑 |
| **123pan / 天翼云 / 移动云盘** | ❌ 无面向个人开发者的开放 API | — | — | 优秀 | — | 排除 |
| **自建 Nextcloud / WebDAV / S3** | ✅ | ✅ | ✅ | 取决于自建 | ✅ | 适合"私有化"附加项 |

**Google Drive 要点**（备选方案一）：
```
GET https://www.googleapis.com/drive/v3/files?q=mimeType contains 'audio/' and trashed=false&fields=files(id,name,mimeType,size,quotaBytesUsed)
GET https://www.googleapis.com/drive/v3/files/{fileId}?alt=media&supportsAllDrives=true
```
- 访问共享云端硬盘需 `supportsAllDrives=true` + `includeItemsFromAllDrives=true`
- OAuth：Google Identity Services，installed app 用 PKCE，**不需要 client secret**
- Scope：`https://www.googleapis.com/auth/drive.readonly`

**坚果云要点**（国内方案）：
- 官方 OAuth 2.0 + REST API（文件列表/下载/上传），申请后拿到 `client_id` / `client_secret`
- 也有标准 **WebDAV** 接口，可作为通用降级通道（几乎所有网盘客户端都支持挂载）
- 缺点：免费版空间小（1GB/月上传流量），但**速度和合规性是国产网盘里最好的**

---

## 四、架构选型：直连 vs 服务端代理

### 方案 A：纯客户端直连（适合个人项目 / MVP）
```
Android App ──OAuth2──> 网盘 API
        └──Range 拉流──> 网盘直链(CDN)
```
- ✅ 零服务器成本、流量不过自己服务器、延迟低
- ✅ Token 存在端上（MSAL 加密缓存），隐私更好
- ❌ 无统一元数据索引（每次都要遍历云端）
- ❌ 无法做跨网盘统一搜索、无法做服务端转码
- ❌ 预认证直链在端上过期要自己管

### 方案 B：客户端 + 自建后端（适合正式产品）⭐
```
Android App ──OAuth2(带 PKCE)──> 你的后端 ──> 网盘 API
        └──统一 REST(带 Range)──> 你的后端 ──> 网盘直链(CDN)
```
- ✅ **统一 API 抽象**，新增网盘只改后端，不发版
- ✅ 服务端预解析元数据（ID3/封面/时长）→ 列表秒开、支持全文搜索
- ✅ 服务端做缓存/CDN，重复播放不消耗用户网盘流量
- ✅ 服务端可做**统一转码**（任意格式 → AAC/AAC 320k），客户端零解码负担，兼容冷门格式
- ✅ Token 刷新集中处理，端上不存 refresh token
- ❌ 有服务器成本 + 需要处理用户 token 加密存储（是敏感数据，需明确隐私政策）

**我的建议**：先做方案 A 验证产品价值（1~2 周），验证成立后把"目录树遍历 + 元数据解析 + 直链解析"三块下沉到后端，即平滑迁移到方案 B，客户端播放层不用大改。

---

## 五、客户端技术选型

### 推荐：Kotlin 原生
| 模块 | 选型 |
|---|---|
| UI | Jetpack Compose + Material 3 |
| 播放器 | **Media3 / ExoPlayer**（含 MediaSessionService、通知栏控制、耳机按键、AudioFocus） |
| 网络 | OkHttp（自定义 `ResolvingDataSource` 解析直链）/ Retrofit |
| 本地库 | Room（曲库索引 + 缓存表） |
| DI | Hilt |
| 凭据存储 | MSAL 加密缓存；自研部分用 `EncryptedSharedPreferences` / Android Keystore |
| 后台 | `MediaSessionService` + `ForegroundService`（Android 14+ 需要 `foregroundServiceType="mediaPlayback"`） |

**为什么不用 Flutter/RN**：
- Flutter 方案（`just_audio` + `flutter_media3`）开发效率更高，但 Media3 播放控制、后台服务、通知栏这些**恰好是 Media3 最有价值的部分**，跨平台桥接容易出坑。
- RN 的 `react-native-track-player` 生态偏弱，且音频焦点/后台保活是长期痛点。
- 如果你已经有 Flutter/RN 积累，那用 Flutter + `flutter_media3` 是可接受的，但要预留调试时间。

### 播放能力边界
- **原生支持**：MP3、AAC/M4A、FLAC、WAV、Ogg/Opus
- **不原生支持**：APE（WavPack/APE 需要 FFmpeg）、WMA、DSD
- 方案：优先推 FLAC/AAC；若必须支持 APE/WMA，走服务端转码（方案 B）比在客户端塞 FFmpeg 更划算（APK 体积 + 16KB 页对齐问题）

---

## 六、风险与坑清单（按优先级）

1. **Redirect URI 签名不匹配** → 正式包登录无响应。debug/release 两个签名都要注册。
2. **Range 发错地方** → 进度条只能从头播、拖动跳转到 0。必须发给直链。
3. **预认证直链过期** → 播放中途 403。策略：播放开始前刷新直链；收到 403 时自动重新取直链并恢复播放位置（ExoPlayer `onError` 里重试）。
4. **企业账号无个人 OneDrive** → 列表空白。必须做 SharePoint 站点 fallback（见 2.5）。
5. **Android 15/16 的 16KB page size** → Google Play 自 2025-11 起对 targetSdk 35+ 要求支持 16KB 页对齐。凡是带 native `.so` 的依赖（FFmpeg、SQLite 原生库、某些图片库）都要确认，别用老版本预编译产物。
6. **后台播放被系统杀** → 必须用 `MediaSessionService` + 前台服务 + 通知栏；Android 13+ 需 `POST_NOTIFICATIONS` 运行时权限。
7. **Token 刷新竞争** → 多处同时刷新导致 `invalid_grant`。用单例 + Mutex 串行化刷新。
8. **大目录卡 UI** → 目录遍历绝不能跑在主线程；用 delta API + Room 增量落库 + Paging 3 分页展示。
9. **音乐扫描不全** → `mimeType` 不可靠（常见 `application/octet-stream`），必须扩展名 + 兜底嗅探（读文件头几个字节判断 ID3/FLAC/fLaC/M4A）。
10. **ToS 风险** → 阿里云盘/115 明确限制把网盘当 CDN 或对外提供公网服务。正式产品只用官方 API，且**只服务用户自己的账号内容**（不要做公共资源库/外链）。
11. **上架合规** → Google Play 需要隐私政策 URL、Data Safety 表单、账号删除说明（如果支持登录）；中国区上架需 ICP 备案（若设服务器）+ 隐私合规。

---

## 七、落地路线图

**Phase 0 — 可行性验证（1~2 天）**
- Azure 注册应用，手写 PKCE 脚本跑通：授权 → 列目录 → 拿直链 → Range 拉 1MB
- 用 VLC/ExoPlayer 命令行验证直链可播

**Phase 1 — MVP（3~5 人周）**
- 登录（MSAL + PKCE）、目录浏览、音频筛选
- 播放器（Media3 + 通知栏 + 锁屏控制 + 后台播放）
- 直链解析 + Range 拖动 + 403 自动重取
- Room 本地库 + 缓存（SimpleCache）
- 最小权限清单 + 隐私政策

**Phase 2 — 体验完善（3~4 人周）**
- 元数据解析（ID3/封面/时长）、封面墙、搜索
- 智能队列、相似推荐、歌词
- 断点续传、预缓存策略、网络质量自适应码率
- 播放历史、同步

**Phase 3 — 多网盘聚合（4~6 人周）**
- 网盘抽象层（`CloudProvider` 接口）+ OneDrive / Google Drive / 坚果云 三个实现
- 后端统一 API + 元数据预解析服务 + 缓存 CDN
- 统一账号中心、多云合并曲库

---

## 八、给你的具体建议

1. **先做 OneDrive 单网盘 MVP**，理由：文档最全、OAuth 最标准、无需服务器、没有 ToS 风险。用它验证"云盘直链 + Range 拖动 + 后台播放"这三件最容易翻车的事。
2. **网盘选择跟着用户走**：海外用户 → OneDrive + Google Drive；国内用户 → 坚果云 + 申请 115 OpenAPI。**别碰阿里云盘和百度网盘个人版**（无官方 API 或明确禁止）。
3. **架构一开始就把网盘做成可插拔接口**，哪怕 MVP 只接一个。这样第二、三个网盘是加实现类，不是重写。
4. **别在客户端塞 FFmpeg**。冷门格式（APE/WMA）走服务端转码，客户端只负责播 AAC/FLAC/MP3。
5. **提前想清楚商业模式**：免费 + 增值（多账号/无限缓存/跨设备同步）比一次性买断更可持续；但一旦有服务器，就必须认真对待用户 refresh token 的加密存储和隐私披露。

---

## 九、需要你确认的 5 个决策点

1. **目标用户在哪？**（国内 / 海外 / 两者）→ 直接决定主推哪个网盘
2. **是否要上架应用商店？**（Google Play / 国内商店 / 仅 APK 分发）→ 决定合规投入量级
3. **有没有后端服务器预算？** → 决定走方案 A 还是 B
4. **你自己的技术栈？**（Kotlin / Flutter / RN / 只会 Web）→ 决定客户端选型
5. **是否只放自己的音乐，还是要做公共曲库？** → 后者涉及版权与 ToS 红线，必须走官方合作

---

## 十、DLNA 投屏：实现要点与踩过的坑

投屏要同时做**两个方向**，缺一个都算不上完整：

| 方向 | 手机的角色 | 协议面 |
|---|---|---|
| 投到设备 | UPnP **控制点**（Control Point） | SSDP 搜设备 → 取设备描述 → SOAP 控制 |
| 让电视来播 | UPnP **渲染器**（DMR） | SSDP 应答 + 设备描述 + SOAP 服务端 + 媒体代理 |

### 坑 1：明文 HTTP 会被系统拦死

DLNA 设备的控制地址天生是 `http://192.168.x.x:8200/...`，
而 Android 的网络安全策略（`networkSecurityConfig`）默认拦明文，OkHttp 会直接抛
`CLEARTEXT communication to 192.168.x.x not permitted by network security policy`。

> **不能**为了投屏把整个 App 的明文策略放开（那等于给所有网络请求开后门）。
> 办法是投屏这一段**绕开 OkHttp**，改用裸 `Socket` 手写 HTTP/1.1 —— 裸 Socket 不走那套策略。

### 坑 2：DIDL-Lite 必须带命名空间声明

`dc:title`、`upnp:class` 这些前缀都依赖根元素上的 `xmlns:dc` / `xmlns:upnp`。
只吐一个 `<item>` 而不包 `<DIDL-Lite>` 根元素，渲染器会直接报「前缀未绑定」然后拒绝播放。
这类问题肉眼看不出来，**必须靠协议自测兜住**。

### 坑 3：SOAPACTION 头不能省

UPnP 规范要求每个 SOAP 请求都带 `SOAPACTION: "服务类型#动作名"`。
缺了小爱音箱这类设备会直接拒绝（返回 500 或干脆不回包）。
更稳的做法是**从请求体里自动推导出这个头**，这样它永远和 body 一致，不会写漏。

### 坑 4：设备回应完 SOAP 常常不关连接

如果客户端一股脑读到 EOF 才返回，每次控制操作都要白等到 socket 超时。
要按 `Content-Length` / `Transfer-Encoding: chunked` **精确读** body。

### 坑 5：音频必须经手机中转

渲染器自己拉不动网盘的地址（坚果云要 Basic、OneDrive 要 Bearer，而且 OneDrive 还要跳预签名直链）。
所以由手机代理：加对鉴权头 → 转发 → **透传 Range**（否则电视上拖不动进度条）。

中转时还要照顾渲染器的两个习惯：

- 它可能**先发 HEAD** 探一下文件大小，所以媒体代理要支持 HEAD（只回头不回 body）；
- 上游网盘如果是分块传输，转给渲染器时**不能既不给 Content-Length 也不给分块头**，
  不然它会干等到超时 —— 自己重新分块发过去。

### 坑 6：SSDP 需要组播锁，而且客户端必须自己入组

Android 上收组播要 `WifiManager.MulticastLock`（权限 `CHANGE_WIFI_MULTICAST_STATE`）；
同时**控制点自己也要 `joinGroup`** —— 不少设备只做组播回复，
不入组就永远搜不到它们（小米某些型号就是只组播）。

1900 端口还可能被别的投屏 App 占掉，绑不上要如实告诉用户「电视可能搜不到本机」，别装作一切正常。

### 坑 7：设备描述里的 controlURL 基准各家不同

有的写绝对路径，有的写相对路径；而相对路径该以哪一段为基准，各家不一样。
稳妥做法是把两种可能的拼法都存下来，推歌时挨个试。

### 自测怎么做的

不靠真电视也能验：`scripts/dlna-test/run-dlna-test.ps1` 起一个**假电视**（Node 写的 SSDP + SOAP 服务）
和一个**假网盘**（带 Range 的音频），把 App 里的 DLNA 代码原样编译到 JVM 上跑，
逐条断言设备描述、SOAP 报文、请求头、DIDL 合法性、206 透传、HEAD 等。

假电视是**故意写得严格**的：校验 SOAPACTION、校验 Content-Type、
带元数据就拒（模拟小爱对 DIDL 挑剔）、没设 URI 不许 Play。
这几条都是真机上踩出来的。当前 **60 项断言全绿**。

---

### 参考文档
- UPnP AVTransport 规范（DLNA DMR 必备）：https://upnp.org/specs/av/UPnP-av-ARCHITECTURE-v1-Service.pdf
- DIDL-Lite 元数据规范：https://upnp.org/specs/av/UPnP-av-ContentDirectory-v1-Service.pdf
- SSDP 设备发现：https://upnp.org/specs/arch/UPnP-arch-DeviceArchitecture-v1.1.pdf
- Microsoft Graph 下载文件：https://learn.microsoft.com/en-us/graph/api/driveitem-get-content
- MSAL Android：https://learn.microsoft.com/en-us/entra/msal/android/
- Graph 权限总览：https://learn.microsoft.com/en-us/graph/permissions-overview
- Google Drive 下载与导出：https://developers.google.com/drive/api/v3/manage-downloads
- 坚果云 API 文档：https://api.jianguocloud.com/docs
- Media3 / ExoPlayer：https://developer.android.com/media/media3/exoplayer
