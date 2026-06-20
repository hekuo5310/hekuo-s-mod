# Hekuo's Mod

一个功能丰富的 Minecraft Fabric 模组，包含下界放水、AI对话、群服互联、末地烛交互等多种功能。

## 版本兼容

- **主要支持**: Minecraft 1.20.1 + Fabric Loader 0.14.22+
- **Fabric API**: 0.87.2+1.20.1
- **Java**: 17+

## 安装方式

### 服务端安装
1. 安装 Fabric Loader 和 Fabric API
2. 将模组 JAR 文件放入服务器的 `mods` 目录
3. 启动服务器，模组会自动在 `config/hekuos-mod.json` 生成默认配置
4. 根据需要修改配置文件

### 客户端安装（可选）
1. 安装 Fabric Loader 和 Fabric API
2. 将模组 JAR 文件放入客户端的 `mods` 目录
3. 客户端安装后可使用双端功能（下界放水、牧羊人小屋修复、冰变霜冰、AI对话、末地烛交互）

### 兼容性说明

| 安装方式 | 可用功能 |
|:---|:---|
| **服务端装了 + 客户端没装** | 所有功能正常工作，客户端无额外渲染 |
| **客户端装了 + 服务端没装** | 功能1/2/3/4/6在单人游戏中可用 |
| **双方都装** | 完整体验 |

---

## 功能列表

所有功能都可以在配置文件中独立开关！

### 1. 铁砧火焰保护水桶（双端）

允许在铁砧中给水桶附魔火焰保护，附魔后的水桶可以在下界放水。

| 火焰保护等级 | 蒸发检查间隔 | 蒸发概率 | 强制消失时间 |
|:---:|:---:|:---:|:---:|
| I | 每40游戏刻 | 70% | 100游戏刻 |
| II | 每50游戏刻 | 60% | 160游戏刻 |
| III | 每70游戏刻 | 55% | 190游戏刻 |
| IV | 每100游戏刻 | 50% | 240游戏刻 |

**使用方法**:
1. 在铁砧左侧放入水桶
2. 在右侧放入火焰保护附魔书
3. 消耗经验后即可获得附魔水桶
4. 在下界使用附魔水桶放水

**配置项**: `fireProtectionWaterBucketEnabled` (默认: true)

### 2. 修复牧羊人小屋（双端）

修复 Minecraft 中牧羊人小屋不会在村庄中生成的已知 Bug。模组会在村庄结构池初始化时检查并补充缺失的牧羊人小屋。

**支持的生物群系**: 平原、沙漠、热带草原、雪地、针叶林

**配置项**: `fixShepherdHouseEnabled` (默认: true)

### 3. 下界冰变霜冰（双端）

当在下界的水旁边有冰块（不包括浮冰与蓝冰）时：
- 冰块会自动变成霜冰
- 水可以多存活 2400 游戏刻（2分钟）

**配置项**: `netherIceToFrostedIceEnabled` (默认: true)

### 4. AI 对话功能（双端 - 单人游戏也可用）

在游戏中与 AI 对话，支持 OpenAI / Anthropic / Google 三种协议。

**命令**:

| 命令 | 说明 |
|:---|:---|
| `/askai <内容>` | 私信与AI对话，生成会话ID |
| `/askai <内容> --all` | AI回复发送到公屏 |
| `/searchconversion <对话ID>` | 查看对话历史 |
| `/searchconversion <对话ID> --thinking` | 查看思考过程 |
| `/searchconversion <对话ID> --all` | 公开展示对话 |
| `/searchconversion <对话ID> --thinking --all` | 公开展示含思考过程 |

**注意**: 如果未开启思考功能，使用 `--thinking` 参数会收到回复"本服未开启模型思考功能喵喵喵~"

**配置项**:

```json
{
  "aiChatEnabled": false,
  "aiProvider": {
    "provider": "openai",
    "apiKey": "your-api-key",
    "model": "gpt-3.5-turbo",
    "baseUrl": "https://api.openai.com/v1",
    "timeout": 30
  },
  "aiSystemPrompt": "你是一个有用的AI助手...",
  "aiMaxTokens": 2048,
  "aiTemperature": 0.7,
  "aiThinkingEnabled": false,
  "aiThinkingBudget": 4096
}
```

**支持的提供商**:

| 提供商 | provider值 | 默认baseUrl |
|:---:|:---:|:---|
| OpenAI兼容 | `openai` | `https://api.openai.com/v1` |
| Anthropic | `anthropic` | `https://api.anthropic.com` |
| Google Gemini | `google` | `https://generativelanguage.googleapis.com/v1beta` |

### 5. OneBot群服互联（仅服务端）

通过 OneBot 协议（WebSocket）实现 QQ 群与 Minecraft 服务器的消息互通。

**功能**:
- QQ群消息转发到MC服务器
- MC聊天消息转发到QQ群
- 玩家加入/离开通知
- 玩家死亡通知
- 玩家获得进度通知

**配置项**:

```json
{
  "oneBotEnabled": false,
  "oneBotConfig": {
    "wsUrl": "ws://127.0.0.1:6700",
    "accessToken": "",
    "groupIds": [123456789],
    "qqToPlayerFormat": "[QQ:{qq}] {message}",
    "playerToQqFormat": "[{player}] {message}",
    "forwardJoinLeave": true,
    "forwardDeath": true,
    "forwardAdvancement": true
  }
}
```

**使用前提**: 需要运行一个支持 OneBot 协议的QQ机器人框架（如 NapCat、Lagrange 等）

### 6. 末地烛交互（双端）

玩家使用末地烛右键其他玩家触发一系列效果：

**基本效果**:
- 被右键的玩家获得 **失明 III 5秒** + **缓慢 V 10秒**
- 公屏金色字体广播: `【玩家A】使用末地烛c了【玩家B】！`

**冷却机制**:
- 20秒内重复右键同一玩家 → 冷却15秒，期间不施加效果

**特殊牛奶**:
- 5秒内被右键20次 → 30%概率获得特殊牛奶桶
- 牛奶桶名称: 金色字体 `你真的要喝掉它吗awa？`
- 饮用效果: 回复5点生命值 + 10秒饱和 V + 5秒抗性提升 V
- 饮用后公屏粉色字体广播: `【饮用玩家】喝掉了【玩家B】的"牛奶！"`
- 喝完牛奶桶会回收（返还空桶）

**致死机制**:
- 一个游戏日内（24000刻）被末地烛右键超过500次 → 再次被右键直接死亡
- 右键他人的次数同样计入，超过500次也会被致死

**Carpet假人保护**:
- 自动识别 Carpet mod 生成的假人
- 右键假人时返回红色字体: `你是真饿了连假人都不放过`

**配置项**: `endRodInteractionEnabled` (默认: true)

### 7. 服务器状态网页（仅服务端）

提供一个美观的HTTP网页展示服务器实时状态。**无需安装Python**——前端服务器已通过Nuitka编译为独立可执行文件。

**展示内容**:
- 服务器在线状态
- 在线玩家数量/最大玩家数
- TPS (每秒tick数)
- 服务器运行时间
- 在线玩家列表（含头像和延迟显示）

**架构**:
- Java内嵌HTTP服务器提供JSON状态API（端口=配置端口+1）
- Nuitka编译的前端二进制提供静态HTML + 反向代理API（配置端口）

**启动策略（自动选择）**:
1. 优先使用Nuitka编译的 `hekuos-mod-web` 二进制（**无需Python**）
2. Fallback到系统Python3解释器

**Nuitka二进制部署**:
1. 从GitHub Release下载对应平台的 `hekuos-mod-web` 二进制
2. 放入服务器的 `hekuos-mod-web/` 目录
3. Linux/macOS需要执行: `chmod +x hekuos-mod-web/hekuos-mod-web`

**配置项**:

```json
{
  "webStatusEnabled": false,
  "webStatusConfig": {
    "port": 8080,
    "refreshInterval": 5,
    "binaryPath": ""
  }
}
```

- `binaryPath`: 手动指定Nuitka二进制路径，为空则自动搜索
- 自动搜索顺序: `hekuos-mod-web/hekuos-mod-web` → `config/hekuos-mod/hekuos-mod-web` → 系统Python3

**访问地址**: `http://服务器IP:8080`

---

## 配置文件

配置文件位于 `config/hekuos-mod.json`，修改后使用 `/hekuomod reload` 命令热重载。

### 全部配置项一览

```json
{
  "fireProtectionWaterBucketEnabled": true,
  "fixShepherdHouseEnabled": true,
  "netherIceToFrostedIceEnabled": true,
  "aiChatEnabled": false,
  "aiProvider": {
    "provider": "openai",
    "apiKey": "",
    "model": "gpt-3.5-turbo",
    "baseUrl": "https://api.openai.com/v1",
    "timeout": 30
  },
  "aiSystemPrompt": "你是一个有用的AI助手，正在Minecraft服务器中与玩家对话。请用简洁友好的方式回答问题。",
  "aiMaxTokens": 2048,
  "aiTemperature": 0.7,
  "aiThinkingEnabled": false,
  "aiThinkingBudget": 4096,
  "oneBotEnabled": false,
  "oneBotConfig": {
    "wsUrl": "ws://127.0.0.1:6700",
    "accessToken": "",
    "groupIds": [],
    "qqToPlayerFormat": "[QQ:{qq}] {message}",
    "playerToQqFormat": "[{player}] {message}",
    "forwardJoinLeave": true,
    "forwardDeath": true,
    "forwardAdvancement": true
  },
  "endRodInteractionEnabled": true,
  "webStatusEnabled": false,
  "webStatusConfig": {
    "port": 8080,
    "refreshInterval": 5,
    "binaryPath": ""
  }
}
```

---

## 管理命令

| 命令 | 权限 | 说明 |
|:---|:---:|:---|
| `/hekuomod reload` | OP (权限2+) | 热重载配置文件 |
| `/hekuomod status` | 所有玩家 | 查看各功能开关状态 |

---

## 编译

### 环境要求
- JDK 17
- Python 3.14+（仅编译前端时需要）
- Nuitka（仅编译前端时需要）

### 编译模组

```bash
./gradlew build
# 产物: build/libs/hekuos-mod-1.0.0.jar
```

### 编译前端服务器（Nuitka）

```bash
# 一键编译
./scripts/build_nuitka.sh

# 清理后重新编译
./scripts/build_nuitka.sh --clean

# 产物: dist/hekuos-mod-web
```

### CI自动编译

项目已配置GitHub Actions（`.github/workflows/build.yml`），推送tag时自动构建并发布：

```bash
git tag v1.0.0
git push origin v1.0.0
```

Release中会包含：
- `hekuos-mod-1.0.0.jar` - 模组JAR
- `hekuos-mod-web` - Linux x86_64 前端二进制
- `hekuos-mod-web.exe` - Windows x64 前端二进制
- `hekuos-mod-web` - macOS ARM64 前端二进制

---

## 项目结构

```
hekuos-mod/
├── .github/workflows/build.yml    # GitHub Actions CI/CD
├── scripts/build_nuitka.sh        # Nuitka编译脚本
├── build.gradle                   # Gradle构建脚本
├── gradle.properties              # 版本和依赖配置
├── settings.gradle                # Gradle设置
├── python/status_server.py        # 前端服务器Python源码（Nuitka编译目标）
├── src/main/java/com/hekuo/mod/
│   ├── HekuosMod.java             # 主模组类
│   ├── HekuosModClient.java       # 客户端入口
│   ├── HekuosModServer.java       # 服务端入口
│   ├── config/ModConfig.java      # 配置系统（所有功能可开关）
│   ├── mixin/
│   │   ├── AnvilScreenHandlerMixin.java  # 铁砧附魔（双端）
│   │   ├── BucketItemMixin.java          # 水桶下界放水（双端）
│   │   ├── IceBlockMixin.java            # 冰块变霜冰（双端）
│   │   ├── ShepherdHouseMixin.java       # 牧羊人小屋修复（双端）
│   │   ├── PlayerEntityMixin.java        # 末地烛交互（双端）
│   │   ├── MilkBucketItemMixin.java      # 特殊牛奶桶（双端）
│   │   ├── server/
│   │   │   ├── MinecraftServerMixin.java       # 服务端tick（仅服务端）
│   │   │   ├── ServerPlayNetworkHandlerMixin.java  # 聊天转发（仅服务端）
│   │   │   └── PlayerManagerMixin.java          # 玩家事件转发（仅服务端）
│   │   └── client/
│   │       └── HandledScreenMixin.java   # 客户端铁砧提示（仅客户端）
│   ├── command/ModCommands.java    # 命令注册（双端）
│   ├── ai/
│   │   ├── AiService.java         # AI API调用（双端）
│   │   └── ConversationManager.java # 对话管理（双端）
│   ├── onebot/OneBotBridge.java   # OneBot协议桥接（仅服务端）
│   ├── web/
│   │   ├── StatusServerManager.java # 状态网页管理（仅服务端）
│   │   └── SimpleStatusServer.java  # 内嵌HTTP API（仅服务端）
│   ├── tracker/
│   │   ├── WaterEvaporationTracker.java  # 水蒸发追踪（双端）
│   │   └── EndRodTracker.java            # 末地烛追踪（双端）
│   └── util/CarpetFakePlayerDetector.java # Carpet假人检测（双端）
├── src/main/resources/
│   ├── fabric.mod.json
│   ├── hekuos-mod.mixins.json
│   └── assets/hekuos-mod/lang/
│       ├── zh_cn.json
│       └── en_us.json
└── README.md
```

---

## 依赖

| 依赖 | 版本 | 用途 |
|:---|:---:|:---|
| Fabric Loader | 0.14.22+ | 模组加载器 |
| Fabric API | 0.87.2+ | Fabric API |
| OkHttp | 4.12.0 | AI API HTTP请求 |
| Java-WebSocket | 1.5.4 | OneBot WebSocket连接 |
| Gson | 2.10.1 | JSON处理 |
| Nuitka | latest | 前端服务器编译（仅构建时） |

---

## 许可证

MIT License
