# Simple Voice Chat Music —— Paper 语音聊天音乐插件 · 3.2.1

> 本插件是 [Simple Voice Chat Music](https://modrinth.com/mod/simple-voice-chat-music)（ItzDerock 的 Fabric 模组）的 **Paper 移植版**。
> 在语音聊天组里和好友一起听歌：B 站、网易云、YouTube、SoundCloud、直链音频都能点，支持图形菜单、滚动歌词和循环播放。

底层使用 [lavaplayer](https://github.com/lavalink-devs/lavaplayer) + [youtube-source](https://github.com/lavalink-devs/youtube-source)。

---

## 环境要求

- 服务端：**Paper / Purpur / Leaves 1.21.x**（编译依赖 `paper-api 26.1.2`）
- **Java 25**（插件以 `--release 25` 编译）
- 服务端安装 [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat)
- 可选：Spotify Client ID/Secret（想贴 Spotify 歌曲链接时用）

> 图形菜单需要 **Paper 1.21.7+** 的 Dialog API；更低的 1.21.x 只能用命令。

---

## 安装

1. 把 jar 放进 `plugins/`；
2. 重启服务器（本插件**不支持** `/reload`）；
3. 需要时修改 `plugins/SimpleVoiceChatMusic/config.yml`，再次重启。

首次启动会生成：

```
plugins/SimpleVoiceChatMusic/
├── config.yml              # 插件配置
├── player-preferences.json # 每个玩家的音源 / 隐藏开关
├── neteaselogin.json       # 网易云凭证（登录后生成）
└── bilibililogin.json      # B 站凭证（登录后生成）
```

语言文件在 jar 内部，见下方「语言」。

---

## 命令

参数**不需要引号**，直接空格分隔即可。

| 命令 | 说明 |
|---|---|
| `/music play <链接 \| 歌名>` | 链接直接播放；普通歌名按你的默认音源搜索后播放 |
| `/music play netease:歌名` | 强制用网易云搜索播放 |
| `/music play ytsearch:歌名` | 强制用 YouTube 搜索 |
| `/music play scsearch:歌名` | 强制用 SoundCloud 搜索 |
| `/music play BV1xx411c7mD` | 直接播放 B 站视频（不搜索） |
| `/music search <关键词>` | 搜索并列出结果，每条带「点击加入队列」 |
| `/music now-playing`（别名 `/music playing`） | 查看当前播放、进度、音量、低音 |
| `/music queue` | 查看当前歌曲与队列 |
| `/music skip` | 下一首 |
| `/music pause` / `/music resume` | 暂停 / 继续 |
| `/music stop` | 停止播放**并清空队列** |
| `/music volume <0-100>` | 设置本组音量 |
| `/music bassboost <0-200>` | 设置本组低音增强 |
| `/music loop <off\|one\|all>` | 播放模式：顺序 / 单曲循环 / 列表循环 |
| `/music collect <标识> [标题]` / `/music like` | 收藏歌曲到「我的喜欢」 |
| `/music catalogue <list\|create\|play\|add\|remove\|delete> ...` | 管理收藏目录（歌单） |
| `/music source [bilibili\|netease\|youtube\|soundcloud\|all\|reset]` | 查看 / 切换自己的默认音源 |
| `/music menu` | 打开图形播放器菜单（1.21.7+） |
| `/music login [netease\|bilibili]` | 管理员：扫码登录（二维码地图） |
| `/music login <平台> status` / `logout` | 管理员：查看状态 / 退出登录 |
| `/music login <平台> cookie <值>` | 管理员：用浏览器 Cookie 登录 |
| `/music lang [auto\|en_us\|zh_cn]` | 切换提示语言 |
| `/music kill` | 管理员：强制重启本组播放器（连续执行两次确认） |
| `/music_register_service` | 管理员：手动注册语音服务 |

`/svcmusic` 是 `/music` 的别名。

---

## 图形菜单（1.21.7+）

`/music menu` 打开原生 **Dialog** 界面，所有功能集中在这里：

- **播放器主界面**：正在播放标题、队列/音量/低音/循环状态、接下来几首、搜索框、传输控制按钮；
- 暂停/继续是同一个按钮，按当前状态自动切换；
- **搜索**：按你的默认音源搜索，B 站/网易云结果是可点击按钮（点击直接播放）；YouTube/SoundCloud 回退到聊天栏列表；
- 二级页面：
  - **播放队列**：当前歌曲 + 等待队列 + 一键清空；
  - **音量**：单独的音量滑块页；
  - **设置**：低音、语言、循环模式、每玩家默认音源、隐藏歌词、隐藏进度条；
  - **我的音乐**：收藏目录列表、新建目录、查看目录内容、播放整个目录、删除自定义目录；
  - **高级**：正在播放详情、重置播放器、登录网易云 / 登录 B 站 / 退出登录、注册语音服务；
- 每个二级页面都有「返回」，主界面有「关闭」。

菜单里的按钮最终都会执行对应的 `/music ...` 命令，行为与手打命令完全一致。
在低于 Paper 1.21.7 的服务端上，命令会返回清晰的「不支持」提示，而不是报错。

---

## Tab 补全

`/music <TAB>` 会补全所有子命令，Paper 上每个候选都带**悬浮说明**。参数级补全：

- `/music play <TAB>` → `https://`、`bilibili://`、`netease:`、`ytsearch:`、`scsearch:`
- `/music login <TAB>` → `netease`、`bilibili`、`status`、`logout`、`cookie`
- `/music source <TAB>` → `bilibili`、`netease`、`youtube`、`soundcloud`、`all`、`reset`
- `/music loop <TAB>` → `off`、`one`、`all`
- `/music volume <TAB>` → `0`、`10`、`25`、`50`、`75`、`100`
- `/music bassboost <TAB>` → `0`、`50`、`100`、`150`、`200`
- `/music lang <TAB>` → `auto` + 已加载的语言

悬浮说明在语言文件的 `tabcomplete:` 段里。

---

## 默认音源与全部音源

每个玩家可以有自己的默认搜索音源，默认取 `music.default-source`：

```bash
/music source              # 查看
/music source netease      # 网易云
/music source bilibili     # B 站
/music source youtube      # YouTube
/music source soundcloud   # SoundCloud
/music source all          # 全部音源：一次并发搜索所有音源
/music source reset        # 恢复服务器默认
```

- **链接 / BV 号 / `bilibili://` / `netease://` / 直链**：不参与搜索，直接播放；
- **普通关键词**：按默认音源搜索。选择 `all` 时会并发查询所有音源，
  结果合并展示（`[B站]`、`[网易云]`、`[YouTube]`、`[SoundCloud]`），`/music play 关键词` 播放排最前的一条；
- 结果条数统一由 `music.max-search-results` 控制，聊天栏与菜单共用。

```yaml
music:
  max-search-results: 5        # 所有音源通用（1-50）
  default-source: "bilibili"   # bilibili / netease / youtube / soundcloud / all
  all-sources:
    youtube: true              # 连不上 YouTube 时设为 false
    soundcloud: true           # 连不上 SoundCloud 时设为 false
```

> 国内网络通常连不上 YouTube / SoundCloud，建议设为 `false`，全部音源模式只搜 B 站 + 网易云。
> 插件也会在搜索前做连通性预检，连不上直接跳过，不会刷超时日志。

---

## B 站

搜索、关键词播放和链接播放都走 B 站接口：

```
/music play 晴天                                        → 搜索并播放第一条
/music search 周杰伦                                     → 列出视频，点击入队
/music play https://www.bilibili.com/video/BV1xx411c7mD  → 播放该视频音频
/music play bilibili://BV1xx411c7mD
/music play BV1xx411c7mD
```

插件通过 view 接口取 `cid`，再取码率最高的 DASH 音频流（`m4s`）交给 lavaplayer 直接播放，不下载到磁盘。
首次使用会自动访问 bilibili.com 获取 `buvid3`；登录后使用账号 Cookie，音质更高。

```yaml
bilibili:
  enabled: true
  fallback-to-youtube: true     # B 站空结果时回退到 YouTube
  # 各接口地址可在 config.yml 中替换
```

`ytsearch:` / `scsearch:` 前缀仍然绕过 B 站，直接使用 lavaplayer。

---

## 网易云音乐

管理员登录后可播放会员歌曲，全服共享：

```
/music play https://music.163.com/song?id=186016
/music play https://163cn.tv/xxxx            # App 分享短链
/music play https://music.163.com/#/playlist?id=123456
/music play netease:晴天                      # 搜索并播放第一条
/music search netease:晴天                    # 列出结果（会员歌曲带 [VIP] 标记）
```

**登录（管理员，无需改配置文件）：**

```
/music login                    -> 查看所有平台登录状态
/music login netease            -> 生成二维码地图，用网易云 App 扫码
/music login bilibili           -> 生成二维码地图，用 B 站 App 扫码
/music login <平台> status      -> 查看某个平台
/music login <平台> logout      -> 删除该平台凭证
/music login logout             -> 删除全部凭证
/music login <平台> cookie <值> -> 风控时的 Cookie 兜底
```

凭证保存在 `plugins/SimpleVoiceChatMusic/neteaselogin.json` 与 `bilibililogin.json`，所有玩家共用。
这两个文件等同于账号密码，**不要分享**。登录流程结束（成功/过期/超时/风控）后二维码地图会自动回收。

```yaml
netease:
  enabled: true
  quality: "exhigh"      # standard / higher / exhigh / lossless / hires
```

> 搜索条数统一在 `music.max-search-results`，不再有单独的 `netease.search-limit`。
> 旧的 `bilibili.cookie` 配置已移除，改用 `/music login bilibili`。

---

## ActionBar 歌词

歌曲播放时在**物品栏上方**滚动显示当前歌词，严格跟随播放进度（按时间戳二分查找，拖动/切歌也能对齐）。

- 网易云歌曲：使用官方 LRC 歌词；
- B 站 / YouTube / SoundCloud / 直链：按「歌名 + 作者」去网易云匹配歌词；
- 找不到歌词就不显示，切歌会清空残留；
- 每个玩家可以在菜单设置里单独隐藏。

```yaml
lyrics:
  enabled: true
  update-interval-ticks: 10
  offset-ms: 0           # 负数=提前显示，正数=延后显示
  color: "blue"          # 颜色名或 #rrggbb（默认蓝色）
  bold: true             # 默认加粗
  font: ""               # 留空=默认字体；也可填 minecraft:default 或资源包字体 ID
```

---

## 循环播放

```bash
/music loop          # 查看当前模式
/music loop off      # 关闭
/music loop one      # 单曲循环
/music loop all      # 列表循环：队列播完后重播当前歌曲
```

- `off` = **顺序播放**：队列播完就停；
- `one` = **单曲循环**：当前歌曲从头重播；
- `all` = **列表循环**：队列播完后按顺序重播本次播放过的歌曲；
- 按**语音组**生效，循环重播不会重复播报「开始播放」；
- 菜单 → 设置 → 循环模式 也可切换。

---

## 我的音乐（收藏目录）

每个玩家默认有一个「我的喜欢」，还可以自由创建其他目录；除「我的喜欢」外都可以删除。
数据保存在 `plugins/PlayerMusicCatalogue/<玩家UUID>.json`。

```bash
/music like                          # 收藏当前播放的歌曲到「我的喜欢」
/music collect <标识> [标题]          # 收藏指定歌曲（搜索结果里的「收藏歌曲」按钮就是它）
/music catalogue                     # 查看所有目录
/music catalogue create 我的歌单       # 新建目录
/music catalogue delete 我的歌单       # 删除目录（「我的喜欢」不可删）
/music catalogue play 我的歌单         # 按顺序播放整个目录
/music catalogue add 我的歌单          # 把当前歌曲加入该目录
/music catalogue list 我的歌单         # 查看目录内容
/music catalogue remove 我的歌单 2     # 移除第 2 首
```

- 聊天栏搜索结果的「[点击加入队列]」旁边新增「[收藏歌曲]」；
- 菜单里的搜索结果点进去有「播放 / 收藏」两个按钮；
- 菜单 →「我的音乐」可以浏览目录、创建目录、播放整个目录、删除自定义目录。

## 每玩家偏好

保存在 `plugins/SimpleVoiceChatMusic/player-preferences.json`：

- 默认搜索音源；
- 是否隐藏 ActionBar 歌词；
- 是否隐藏 Boss 栏进度。

菜单 → 设置 里修改，「应用设置」保存。

---

## 启动时自动创建音乐组

Simple Voice Chat 启动完成后，插件会创建一个名为 `音乐` 的语音组（可配置），玩家可直接加入：

```yaml
music:
  auto-group:
    enabled: true
    name: "音乐"
    password: ""      # 留空 = 无密码
    persistent: true  # SVC 保存，重启后仍存在
```

只创建一次：之后启动会按名字找到已有组并输出 `already exists`，不会重复创建。

---

## 链接识别与直链预检

交给 lavaplayer 之前，输入会先被规范化和分类：

- 去掉首尾引号/`<>`、还原 `&amp;`、对空格和中文做百分号编码，`/music play http://host/我的歌.mp3` 也能用；
- 已知平台直接路由（YouTube、SoundCloud、Vimeo、Twitch、Bandcamp、Spotify、B 站、网易云）；
- 不支持的平台给出明确提示（QQ 音乐、酷我、咪咕等）；
- **直链会先做一次预检**（`music.http.preflight`）：只读取 **1 KiB**，不下载、不占磁盘，
  可发现 401/403、404、HTML 页面、5xx、以及"声明有文件但返回 0 字节"的 CDN；
- **失败结果会缓存**（`music.http.failure-cache-seconds`，默认 60 秒），重复点同一个坏链接会立即返回，不重复请求、不刷日志。

```yaml
music:
  command-cooldown-ms: 1000   # 每人执行 /music play、/music search 的冷却
  http:
    user-agent: "Mozilla/5.0 ... Chrome/122.0 Safari/537.36"
    referer: ""
    connect-timeout-ms: 5000
    read-timeout-ms: 15000
    preflight: true
    preflight-timeout-ms: 4000
    failure-cache-seconds: 60
```

### 减少 lavaplayer 日志噪音

坏直链会让 lavaplayer 自己打印 `Suspicious exception ...` 堆栈。这个 logger 属于服务端，不属于本插件。
要静音，可在服务端的 `log4j2.xml` 的 `<Loggers>` 中加入：

```xml
<Logger name="com.sedmelluq.discord.lavaplayer" level="ERROR"/>
<Logger name="dev.lavalink.youtube" level="ERROR"/>
```

插件自身每个失败链接只会输出一行 `Failed to load track: ...`；命中缓存的失败不会重复输出。

---

## Boss 栏进度

组内播放时，每位成员都会看到 Boss 栏：

```
Never Gonna Give You Up [1:23/3:45]
██████████████░░░░░░░░░░░░░░
```

- 标题优先取文件名（URL 以音频后缀结尾时），YouTube/SoundCloud 用真实标题；
- `[当前/总时长]` 来自播放进度，条本身是原生 Boss 栏进度；
- 按组、按玩家显示，各自使用自己的语言；
- 时长未知时显示 `--:--`；
- 默认**蓝色**，每位玩家可在菜单里单独隐藏。

```yaml
bossbar:
  enabled: true
  update-interval-ticks: 20   # 20 tick = 1 秒
  color: BLUE                 # PINK/BLUE/RED/GREEN/YELLOW/PURPLE/WHITE
  overlay: PROGRESS           # PROGRESS/NOTCHED_10/NOTCHED_12/NOTCHED_20
```

版式由语言文件里的 `bossbar.title` 决定（`<title>`、`<position>`、`<duration>`）。

---

## 配置

`plugins/SimpleVoiceChatMusic/config.yml`：

```yaml
# 1) 语言与消息
language:
  default: zh_cn            # 控制台 / 未匹配客户端语言时使用
  use-client-locale: true   # 尽量按玩家客户端语言选择

messages:
  use-prefix: true          # 每条消息加前缀（语言文件的 general.prefix）

# 2) 播放与队列
music:
  max-queue-size: 100       # 每个语音组
  max-search-results: 5     # 所有音源通用（聊天栏与菜单共用，1-50）
  max-title-length: 120
  max-author-length: 60
  default-volume: 100
  default-bassboost: 0
  command-cooldown-ms: 1000
  default-source: "bilibili" # bilibili / netease / youtube / soundcloud / all

  all-sources:
    youtube: true           # 全部音源模式是否查 YouTube
    soundcloud: true        # 全部音源模式是否查 SoundCloud

  search:
    click-color: "green"    # [点击加入队列] 的颜色（名称或 #rrggbb）
    click-bold: true

  auto-group:
    enabled: true
    name: "音乐"
    password: ""
    persistent: true

  http:
    user-agent: "Mozilla/5.0 ..."
    referer: ""
    connect-timeout-ms: 5000
    read-timeout-ms: 15000
    preflight: true
    preflight-timeout-ms: 4000
    failure-cache-seconds: 60
    retry-count: 2
    retry-delay-ms: 1500

# 3) B 站
bilibili:
  enabled: true
  fallback-to-youtube: true
  # 接口地址可在 config.yml 中替换

# 4) Boss 栏
bossbar:
  enabled: true
  update-interval-ticks: 20
  color: BLUE
  overlay: PROGRESS

# 5) 图形菜单
menu:
  enabled: true
  queue-page-size: 20
  preview-count: 3

# 6) 网易云
netease:
  enabled: true
  quality: "exhigh"

# 7) 歌词
lyrics:
  enabled: true
  update-interval-ticks: 10
  offset-ms: 0
  color: "blue"
  bold: true
  font: ""

# 8) Spotify（可选）
spotify:
  client-id: ""
  client-secret: ""
```

所有配置只在启动时读取，修改后请重启服务器。

---

## 语言

所有玩家可见文本都在 jar 内的 `lang/<code>.yml`，使用 [MiniMessage](https://docs.advntr.dev/minimessage/format.html) 格式：

```yaml
general:
  prefix: "<dark_gray>[</dark_gray><aqua>音乐</aqua><dark_gray>]</dark_gray> "
```

自带语言：**zh_cn** 与 **en_us**。

### 选择顺序

1. `/music lang <code>`（按玩家，当前会话有效）；
2. 玩家客户端语言（`language.use-client-locale: true` 时）；
3. `config.yml` 的 `language.default`；
4. `en_us`；
5. 原始 key（方便发现缺失翻译）。

### 添加语言

把 `src/main/resources/lang/en_us.yml` 复制为 `lang/<code>.yml`，翻译值后重新构建即可。
插件启动时会扫描 jar 内的 `lang/*.yml`，不需要改代码；请保留所有 key 与 `<占位符>`。

消息里要显示字面量 `<` 时写成 `\<`（否则会被 MiniMessage 当作标签）。

### 文本用在哪里

- 所有命令回复与错误提示；
- 语音组内的广播（开始播放、跳过、音量变化等）；
- 搜索结果的可点击按钮与悬浮文本；
- Simple Voice Chat 客户端设置里「音乐」音量分类的名称/描述。

---

## 权限

| 权限节点 | 默认 | 说明 |
|---|---|---|
| `simplevoicechatmusic.music` | `true` | 使用 `/music` 所有子命令 |
| `simplevoicechatmusic.admin` | `op` | 登录、重置播放器、注册语音服务 |

---

## Spotify

直接贴歌曲链接：`/music play https://open.spotify.com/track/...`

插件**不**串流 Spotify 音频：它通过 Spotify Web API 取歌名与歌手，再去 YouTube 搜索该歌曲。
只支持单曲（`/track/`），不支持专辑与歌单。

在 <https://developer.spotify.com/dashboard> 创建应用，把凭据填进 `config.yml`。
不填也能用，Spotify 链接会给出提示并回退到普通搜索。

> **不要提交真实凭据。** 旧版本仓库历史里曾提交过含真实凭据的 `.env` 文件；
> 如果你 fork 过，请到 Spotify 后台轮换密钥。

---

## 常见问题

- **提示 "VoiceChat API connection has not been established yet"**：Simple Voice Chat 还没启动完成，
  等几秒；仍不行就执行 `/music_register_service` 或重启服务器。
- **完全没有声音**：确认在同一语音组、客户端「音乐」音量分类未静音；管理员可执行 `/music_register_service` 后看控制台。
- **YouTube 报 "Sign in to confirm you're not a bot"**：YouTube 封了服务器 IP，换音源（`scsearch:`）或换机房。
- **网易云登录提示 8821 风控**：稍后重试，或改用 `/music login netease cookie <MUSIC_U=...>`。
- **全部音源搜索时刷 YouTube/SoundCloud 超时**：把 `music.all-sources.youtube/soundcloud` 设为 `false`。
- **语言不对**：检查 `language.use-client-locale`，或 `/music lang zh_cn` 强制指定。

---

## 构建

```bash
mvn clean package
# -> target/SimpleVoiceChatMusic-3.2.1.jar
```

需要 JDK 25；产物为 shade 后的胖 jar（已打包 lavaplayer、youtube-source、OkHttp、Gson 等）。

---

## 致谢与许可

- 原模组：[ItzDerock/simplevoicechat-music](https://github.com/ItzDerock/simplevoicechat-music)（Apache-2.0）
- Paper 移植：GavinGoGaming、MrTron
- Spotify 支持：MrTron
- 二维码渲染：[ZXing](https://github.com/zxing/zxing)（Apache-2.0）

本项目以 **GNU General Public License v3.0（GPL-3.0）** 授权，详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。\n\n> 本项目的 Paper 移植基于 Gavin Fox / MrTron 的 GPL-3.0 项目；原始 Fabric 模组与第三方库仍保留其 Apache-2.0 / MIT 许可声明（见 NOTICE）。
