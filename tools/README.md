# 一键构建与资源处理

项目根目录的 `build.bat` / `build.py` 自动拉取 SereneSeasons 源码，复用 `tools/finish_seasons.py` 导入日历和季节传感器贴图、更新天空/雾/云配色，运行 Python 回归及完整 Gradle 测试与构建，最后生成并校验插件 JAR 和 CE content pack。不会启动或修改运行中的服务器，也不会编译原模组。

## 一键使用

前提：Python 3.10+、Git、JDK 25（建议设置 `JAVA_HOME`）；首次需要联网下载源码、Gradle 和构建依赖，无需另装 Gradle。

在 `craft-engine-seasons` 目录中执行：

```powershell
.\build.bat
```

Windows 也可以直接执行 `py -3 -X utf8 .\build.py`。Linux/macOS 使用 Shell 入口（无需先设置执行权限）：

```sh
sh ./build.sh
sh ./build.sh --dry-run
sh ./build.sh --offline
sh ./build.sh --mod "../SereneSeasons"
```

`build.sh` 优先使用 `python3`，不存在时回退到 `python`；版本检查由 `build.py` 完成。所有参数原样传递，退出码与构建结果一致。也可以直接使用 `python3 build.py`。脚本以自身目录定位工程，因此可从其他目录用绝对路径启动；`--mod` 的相对路径按调用时的工作目录解析。

可选参数：

```powershell
# 只检查并显示计划：不下载、不写入、不运行测试或构建
.\build.bat --dry-run

# 使用已有源码，不拉取、不更新，也不修改这个源码目录
.\build.bat --mod "E:\Projects\Seasons\SereneSeasons"

# 使用已下载源码缓存和 Gradle 依赖缓存
.\build.bat --offline
```

- 源码地址和完整提交 SHA 在 `tools/sereneseasons-source.json`。默认锁定已验证的版本，不追踪上游最新分支，避免材质布局变化导致同一构建不可复现。
- 首次从锁定仓库下载到 `.cache/sereneseasons/<提交SHA>/`；之后检查 origin、HEAD、工作区及贴图，直接复用，不自动 pull。缓存不在 `build/` 内，不会被 `gradle clean` 删除。
- 缓存有本地修改时停止，不执行 reset 或覆盖；保留/移走修改后重试，或者通过 `--mod` 显式使用修改后的源码。
- 下载先进入临时目录，验证提交和所需资源后才发布为缓存；下载失败不留一个可误用的半成品缓存。
- `--dry-run` 遇到尚未下载的源码只报告待下载版本，明确提示资源尚未校验；不会为了预检联网下载。
- `--offline` 不下载原模组，向 Gradle 传入 `--offline`。必须先在线成功构建一次以缓存 Gradle distribution、Java 25 工具链和依赖；Gradle wrapper 自身的引导下载不受该 Gradle 参数控制。
- 需要更新上游版本时，修改锁文件的完整 SHA，再运行脚本验证；旧提交缓存不会被强制改写。

## 兼容旧入口

原有本地源码处理入口仍然可用：

```powershell
py -3 .\tools\finish_seasons.py --dry-run
py -3 .\tools\finish_seasons.py
```

这个旧入口默认读取同级 `..\SereneSeasons`，支持 `--mod <源码目录>` 和 `--offline`，本身不负责自动下载。

正式执行会先把受影响文件备份到 `backups/ss-assets-<时间戳>/`，再写入资源；`NEW-FILES.json` 记录本次新增文件。构建失败时保留备份和已写入资源，不跳过测试，不生成新的 ready 交付目录。

独立运行 Python 回归测试：

```powershell
py -3 -X utf8 -m unittest discover -s src/test/python -v
```

## 导入和配置

- 24 张 PNG：12 张温带日历、6 张热带日历、1 张未知状态日历、4 张传感器顶面、1 张侧面。
- 保留 CE 的现有物品模型身份，日历模型改用原模组贴图，传感器使用日光探测器模板模型。
- 复制原模组的许可证和资源来源说明；这不授予额外的再分发权利。
- `tools/season-atmosphere.json` 定义温带四季和热带六阶段配色。天空、雾使用 `#RRGGBB`，云使用 `#AARRGGBB`。
- 这些天空预设是插件新增配置，不是从 SereneSeasons 导出的原版配色。
- 修改 JSON 后重新执行脚本、安装新 JAR 并完整重启服务器。仅修改此文件或插件 reload 不会热更新客户端群系注册表。
- 配色只写入 `biomes.index` 标为 seasonal 的群系变体；不改其他群系字段，也不改变服务器世界的真实群系。

## 安装输出

成功后查看 `build/distributions/seasons-ready-<时间戳>/`：

1. 停服并备份原插件和 CraftEngine 的 `ce_seasons` 内容目录。
2. 用交付目录中的插件 JAR 替换旧版本，不要保留两个版本。
3. 将 `ce-seasons-content.zip` 解压到 `plugins/CraftEngine/resources/`，结果应为 `resources/ce_seasons/pack.yml`，不要多套一层目录。
4. 完整启动服务器，重新生成和分发 CraftEngine 资源包，客户端重新登录并加载新包。
5. `SHA256SUMS.txt` 可用于校验 JAR 和内容包。

## 运行期验收

目标环境是工程锁定的 Minecraft/Folia 26.3、CraftEngine 26.9.1、Java 25。单元测试和构建通过不等同于完成真实 Folia/客户端验收。

在启用季节的世界中，确认 `visual.enabled=true`，先选平原、草地和橡树进行检查：

- 切换春、秋、冬，检查日历、传感器、草叶以及天空/雾/云；等待限速刷新队列排空。
- 步行进入新区块、离开视距后返回、远距离传送、维度往返和重连后再次检查。
- 多玩家处于不同 Folia 区域时执行插件配置重载，确认已发送区块不会长期停留在旧颜色。
- 检查日志是否出现协议失败、群系注册表不一致或异步访问错误。

已修复配置重载时先发布新状态再清队列导致的新刷新任务丢失；新区块首发沿用 CE 的 biome remapper，已发送区块仍按限速队列刷新。默认每 tick 处理 2 个刷新票据，因此短暂分批更新不等于漏刷新。真实游戏表现仍需在上述环境中确认。
