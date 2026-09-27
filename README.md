> **AI 编写声明：本项目由 AI 编写，包括主要代码、构建脚本和文档。请先在测试环境验证，不要将构建通过视为生产环境兼容性保证。**

# CraftEngineSeasons

基于 **CraftEngine** 的服务端季节扩展，面向 **Folia**，实现受 SereneSeasons 启发的季节外观、天气和农业玩法。

这不是 SereneSeasons 的官方移植，也不保证与原模组完全一致。客户端不需要安装 SereneSeasons，但需要加载服务器通过 CraftEngine 生成和分发的资源包。

## 功能

- **季节时钟**：春、夏、秋、冬共 12 个子季节，以及热带群系的 6 个干湿季阶段；支持保存进度、暂停和手动切换。
- **季节外观**：通过客户端群系映射改变草地、树叶、干枯树叶及天空、雾、云的颜色，不修改世界实际存储的群系。
- **气候变化**：季节天气、积雪和结冰逻辑，可单独配置或关闭。
- **作物联动**：限制已配置原版作物的适生季节；提供 CraftEngine 自定义作物和瓜茎行为。
- **日历与季节传感器**：日历显示季节阶段；传感器提供 0–15 的定向弱红石信号。
- **一键构建**：拉取固定版本的原模组源码、处理材质、生成 content pack、运行测试并打包。

## 运行要求与版本支持

| 项目 | 要求 |
| --- | --- |
| Java | JDK / Java 25 |
| Minecraft | **26.1.2、26.2、26.3**，同一个插件 JAR |
| 服务端 | 面向 Folia 开发；安装前确认服务端及依赖兼容 |
| CraftEngine | 以 **26.9.1** 为集成基准，必须具备本项目使用的接口 |
| 客户端 | 加载 CraftEngine 生成的服务器资源包 |

插件按服务端的确切版本选择内置季节数据包；不将未列出的版本视为兼容版本。插件最低 API 声明和默认编译依赖均基于 26.1.2。

### 验证范围

“支持”表示已提供对应实现和资源，不表示已经完成所有实服场景验收：

| 版本 | 已完成的兼容性验证 |
| --- | --- |
| 26.1.2 | 官方服务端原生群系 palette 编解码、相关反射接口；780 个季节群系 JSON 原生解码及颜色回读 |
| 26.2 | 上述验证，792 个季节群系 JSON 原生解码及颜色回读；另验证了 Folia 26.2 的区域骨粉字段与 CraftBiome 句柄 |
| 26.3 | 保留既有实现与资源，并接入启动时原生协议自检；本轮没有新增真实 26.3 服务端 JAR 的验证证据 |

**尚未完成三版完整服务端与客户端联机验收**。跨维度、重连、资源包渲染、多 Folia region 的作物生长和骨粉行为仍需在目标服务器上测试。普通单元测试不代替这些验证。

## 一键构建

构建环境需要：

- **JDK 25**，确保 `java` 可用。
- **Python 3.10 或更新版本**。
- **Git**。
- 首次构建可访问源码仓库和 Gradle 依赖仓库。

项目自带 Gradle Wrapper，无需另外安装 Gradle。

### Windows

```powershell
.\build.bat
```

也可以直接调用 Python：

```powershell
py -3 -X utf8 .\build.py
```

### Shell

```sh
sh ./build.sh
```

入口脚本会选择可用的 Python，并将参数原样传给 `build.py`。Shell 入口已做语法及 Git Bash 验证，尚未在原生 Linux / macOS 环境完成全流程测试。

### 常用参数

```sh
# 只显示执行计划，不下载、修改资源或构建
sh ./build.sh --dry-run

# 使用本地缓存的源码和构建依赖
sh ./build.sh --offline

# 使用已有原模组源码，不自动更新该目录
sh ./build.sh --mod ../SereneSeasons
```

Windows 入口同样支持这些参数，例如 `build.bat --offline`。`--mod` 的相对路径以调用时的工作目录为准。

离线构建要求源码、Gradle Wrapper 分发包、依赖及所需 JDK 已准备好；`--offline` 不能替代首次环境准备。

### 构建流程

1. 根据 [`tools/sereneseasons-source.json`](tools/sereneseasons-source.json) 拉取锁定提交，不自动追踪上游最新分支。
2. 将源码缓存到 `.cache/sereneseasons/<commit>/`，检查来源、提交及工作区状态；不覆盖脏缓存。
3. 运行资源预检和 Python 回归测试。
4. 从原模组导入日历、季节传感器的 24 张 PNG，应用本项目的季节配色与天空配置。
5. 更新 CraftEngine content pack 和三版内置季节数据包。
6. 执行 Gradle `clean test build`，校验交付文件并生成校验和。

脚本不会编译 SereneSeasons 本身，也不会替你安装插件或启动服务器。直接运行 `gradlew build` 不包含完整的源码拉取与材质导入流程，生成交付包请优先使用上述入口。

被替换的资源会备份到 `backups/ss-assets-<timestamp>/`。失败后请检查日志和备份，不要把中途生成的文件当作完整交付包。

### 构建产物

```text
build/distributions/seasons-ready-<timestamp>/
├── craft-engine-seasons-0.1.0.jar
├── ce-seasons-content.zip
├── INSTALL.txt
└── SHA256SUMS.txt
```

其中 **`ce-seasons-content.zip` 是 CraftEngine 内容包，不是直接发给客户端的最终资源包**。

更多构建参数、缓存规则和兼容性探测说明见 [`tools/README.md`](tools/README.md)。

## 安装与更新

1. **停止服务器**，备份世界、插件配置和已有的 `ce_seasons` 内容包。
2. 安装兼容的 CraftEngine，将本插件 JAR 放入 `plugins/`，移除重复的旧版 JAR。
3. 解压 `ce-seasons-content.zip` 到 CraftEngine 内容目录，最终应存在：

   ```text
   plugins/CraftEngine/resources/ce_seasons/pack.yml
   ```

   注意不要多套一层 `ce_seasons/` 或 ZIP 文件名目录。

4. **完整启动服务器**，让插件加载对应 Minecraft 版本的内置季节数据包。无需手动复制到世界的 `datapacks/`。
5. 按 CraftEngine 的工作流生成、分发最终资源包，让客户端重新登录并加载。
6. 检查 `plugins/CraftEngineSeasons/config.yml`，确认启用的世界和功能符合预期。

**更换 JAR、季节群系注册内容或内置天空配色后需要完整重启和客户端重连。** `/ceseasons reload` 不能替代注册表重新加载。

## 命令与权限

| 命令 | 用途 |
| --- | --- |
| `/ceseasons` | 查询当前季节 |
| `/ceseasons get [world]` | 查询指定世界的季节 |
| `/ceseasons set <subseason> [world]` | 设置子季节，例如 `early_spring`、`mid_winter` |
| `/ceseasons pause [world]` | 暂停季节时钟 |
| `/ceseasons resume [world]` | 恢复季节时钟 |
| `/ceseasons reload` | 重载插件配置 |
| `/ceseasons give <calendar\|season_sensor>` | 给执行命令的玩家发放物品 |

世界参数支持已加载且启用的世界名称或 UUID；控制台操作建议明确指定世界。子季节为四季各自的 `early`、`mid`、`late` 阶段，可使用命令补全。

- `ceseasons.query`：查询权限，默认所有玩家可用。
- `ceseasons.admin`：管理权限，默认 OP 可用。
- `give` 只能由玩家执行；需先正确安装 CE 内容包。

## 常用配置

完整默认配置见 [`src/main/resources/config.yml`](src/main/resources/config.yml)。

```yaml
clock:
  day-ticks: 24000
  days-per-sub-season: 8
  starting-sub-season: 1
  progress-while-empty: true
  source-world: ''

worlds:
  enabled: []

visual:
  enabled: true
  refresh-chunks-per-tick: 2
  refresh-interval-ticks: 5

agriculture:
  enabled: true
  mode: slow
  out-of-season-chance: 6
  underground-y: 48
  greenhouse-height: 16
```

几个容易误解的默认值：

- **`worlds.enabled: []` 不是启用所有世界**，而是仅启用时钟源世界；`source-world` 留空时选择第一个 NORMAL 世界。多世界服应明确配置。
- 每个子季节默认 8 天，完整一年为 96 个季节日。
- 外观补刷默认全服每 tick 最多处理 2 个区块任务，换季后会逐步刷新，不保证所有玩家视野瞬间改变。
- `climate.enabled` 和 `agriculture.enabled` 是不同开关，关闭气候功能不等于关闭农业联动。

## 作物联动

### 原版作物

农业功能默认开启，但只管理 `agriculture.crops` 中登记的方块 ID。默认规则包括小麦适合夏秋、胡萝卜适合春秋、土豆适合春季等，具体以默认配置为准。

- 当季：放行原生生长规则，**不额外增加生长速度或产量**。
- `slow`：反季生长尝试按概率放行；默认每次门控有 `1/6` 概率通过，不是固定每六次必成功一次。
- `stop`：阻止反季生长。
- `wither`：在相关生长尝试时触发枯萎处理，不是换季瞬间清除全部作物。
- 骨粉也走季节判定；生存模式中失败的使用尝试仍可能消耗骨粉，创造模式不扣除。

温室是简化判定：向上检查一定高度内的玻璃，中途遮挡会阻断；**不检查房屋是否封闭**。默认认普通和染色玻璃整块，不认玻璃板、遮光玻璃，可通过 `agriculture.greenhouse-blocks` 自定义。低于配置高度且看不到天空的地下种植也有豁免。

### CraftEngine 自定义作物

**不会自动接管所有 CE 作物。** 接入已有作物需要同时配置季节表和行为：

1. 在本插件配置中登记作物的 **方块 ID**，不是仅登记种子物品 ID：

   ```yaml
   agriculture:
     crops:
       'your_pack:tomato': [SPRING, SUMMER]
   ```

2. 在对应 CE 方块的 `behaviors` 中，将原来的 `crop_block` **替换**为 `ce_seasons:season_crop`：

   ```yaml
   behaviors:
     # 保留原有其他必要行为；这里仅展示作物生长行为
     - type: ce_seasons:season_crop
       light_requirement: 9
       grow_speed: 0.125
       bone_meal_age_bonus: 2
       is_bone_meal_target: true
   ```

瓜茎则将 `stem_block` 替换为 `ce_seasons:season_stem`，并保留 `fruit`、`attached_stem` 等必要设置。附着后的 `attached_stem_block` 不需要改成季节瓜茎行为。

**不要并列保留原生长行为与季节行为**；只登记 ID 而不替换行为，也不能保证覆盖 CE 瓜茎等直接写入路径。以上片段不是完整 CE 方块定义，还需保留原作物的状态、模型及其他行为。

可运行的完整示例见 [`content-pack/ce_seasons/configuration/agriculture_examples.yml`](content-pack/ce_seasons/configuration/agriculture_examples.yml)。示例作物和瓜茎已在默认季节表中登记。

## 自定义季节外观

天空、雾和云配置位于 [`tools/season-atmosphere.json`](tools/season-atmosphere.json)：

- 天空、雾：`#RRGGBB`。
- 云：`#AARRGGBB`，包含透明度。
- 包含四季及热带干湿季阶段的配置。

修改后重新执行一键构建，再更新插件并完整重启。这些颜色写入内置数据包，不是仅执行 `/ceseasons reload` 就能更新的运行时选项。

本项目的植被与天空配色由扩展自身配置生成，**并非原模组季节配色的逐项复刻**；自动导入的原模组素材是日历和传感器贴图。

## 已知限制与排查

- **刷新延迟**：先等待限速补刷队列完成，再判断是否漏刷。测试时可先选择已启用世界的平原草地和橡树，避开黑名单群系、第三方群系及固定染色树叶。
- **注册表与协议**：启动时会用隔离的合成注册表执行原生 palette 编解码自检；不支持的格式会报错，不以猜测格式继续发送。检查日志中的协议和注册表错误。
- **红石传感器**：当前支持定向弱信号，不代表所有原版红石接口都已覆盖；CE 未暴露的 `ownSignal` 路径无法由此补齐。
- **农业边界**：没有全 CE 作物自动改装、完整封闭温室检测，也没有当季产量倍率。保护插件、骨粉和跨 region 行为需要在目标服务器验证。
- **客户端效果**：构建与原生 codec 测试通过，不等于材质、天空、移动换区块和换维度效果均已实测。报告问题时请附确切服务端版本、CraftEngine 版本、相关配置、日志和复现步骤。

## 项目结构

```text
src/main/java/                 插件实现
src/main/resources/            默认配置、插件描述及三版本季节数据包
src/test/                      Java 测试及原生兼容性探测入口
content-pack/ce_seasons/        CraftEngine 内容包
tools/                         材质导入、资源生成和构建说明
build.py                       一键构建主入口
build.bat / build.sh           Windows / Shell 启动脚本
```

## 来源与素材许可

- 原模组： [Glitchfiend / SereneSeasons](https://github.com/Glitchfiend/SereneSeasons)。
- CraftEngine 使用说明： [CraftEngine Wiki](https://xiao-momi.github.io/craft-engine-wiki/)。
- 内容包中的 `SS-ASSET-SOURCE.txt` 和 `SS-SOURCE-LICENSE.txt` 记录导入素材的来源与上游许可。

第三方素材及代码仍受各自许可约束。AI 编写声明不改变这些权利，也不代表项目获得上游官方背书；分发或修改导入素材前请阅读随包的来源与许可文件。
