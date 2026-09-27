#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime
import xml.etree.ElementTree as ET
import zipfile


# 新增的季节配色，不是 SereneSeasons 原有配色。
# 第一次执行后会生成 tools/season-atmosphere.json。
# 以后修改那个 JSON 再执行，不必改这里。
DEFAULT_COLORS = {
    "temperate": {
        "spring": {
            "sky": "#8FB9EF",
            "fog": "#D3E3ED",
            "cloud": "#CCFFF5F1"
        },
        "summer": {
            "sky": "#75A9EF",
            "fog": "#BED8EE",
            "cloud": "#CCFFFFFF"
        },
        "autumn": {
            "sky": "#9AADD0",
            "fog": "#DFCCBC",
            "cloud": "#CCEEDDD1"
        },
        "winter": {
            "sky": "#A3B5CD",
            "fog": "#DCE5EB",
            "cloud": "#CCDFE7EE"
        }
    },
    # 顺序：早旱、仲旱、晚旱、早雨、仲雨、晚雨。
    "tropical": [
        {"sky": "#8EB5DE", "fog": "#DCD7BB", "cloud": "#CCFFF2D8"},
        {"sky": "#99B3D4", "fog": "#E2D5B6", "cloud": "#CCF5E9CE"},
        {"sky": "#91B4D9", "fog": "#D6D9C0", "cloud": "#CCF0ECD8"},
        {"sky": "#83AFDA", "fog": "#C2DAD7", "cloud": "#CCDCE9E5"},
        {"sky": "#859EBF", "fog": "#BCCCD6", "cloud": "#CCC7D2DF"},
        {"sky": "#8AAED5", "fog": "#C8DCDD", "cloud": "#CCDDE8E8"}
    ]
}

SEASONS = ("spring", "summer", "autumn", "winter")
ATTRIBUTE_KEYS = {
    "sky": "minecraft:visual/sky_color",
    "fog": "minecraft:visual/fog_color",
    "cloud": "minecraft:visual/cloud_color",
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def json_bytes(value):
    return (
        json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    ).encode("utf-8")


def contained(path, root):
    """拒绝通过符号链接或 ../ 写到目标目录之外。"""
    path.resolve().relative_to(root.resolve())
    return path


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(
        prefix=path.name + ".", suffix=".tmp", dir=str(path.parent)
    )
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def validate_colors(colors):
    temperate = colors.get("temperate")
    tropical = colors.get("tropical")
    require(isinstance(temperate, dict), "temperate 必须是对象")
    require(
        isinstance(tropical, list) and len(tropical) == 6,
        "tropical 必须包含六个阶段"
    )
    profiles = [temperate.get(name) for name in SEASONS] + tropical
    for profile in profiles:
        require(isinstance(profile, dict), "缺少季节配色")
        for key, digits in (("sky", 6), ("fog", 6), ("cloud", 8)):
            value = profile.get(key)
            require(
                isinstance(value, str)
                and re.fullmatch(r"#[0-9a-fA-F]{" + str(digits) + "}", value),
                f"无效颜色：{key}={value!r}，应为 {digits} 位十六进制颜色"
            )


def parse_bool(text):
    value = text.strip().lower()
    require(value in ("true", "false", "1", "0"),
            f"biomes.index 布尔值无效：{text!r}")
    return value in ("true", "1")


def failure_details(project):
    directory = project / "build/test-results/test"
    for report in sorted(directory.glob("TEST-*.xml")):
        try:
            root = ET.parse(report).getroot()
            for case in root.iter("testcase"):
                for kind in ("failure", "error"):
                    error = case.find(kind)
                    if error is not None:
                        print(
                            "\nFAILED:",
                            case.get("classname", ""),
                            case.get("name", ""),
                            file=sys.stderr
                        )
                        text = error.get("message") or error.text or ""
                        print(text[:1800], file=sys.stderr)
        except (ET.ParseError, OSError):
            pass


def main():
    parser = argparse.ArgumentParser(
        description="导入本地 SS 贴图、配置季节天空颜色并构建交付包"
    )
    parser.add_argument(
        "--project", type=Path,
        default=Path(__file__).resolve().parent.parent
    )
    parser.add_argument("--mod", type=Path)
    parser.add_argument("--offline", action="store_true",
                        help="使用 Gradle 离线模式，需要已有依赖缓存")
    parser.add_argument(
        "--dry-run", action="store_true",
        help="只检查输入和计划，不修改文件、不运行 Gradle"
    )
    args = parser.parse_args()

    project = args.project.resolve()
    mod = (args.mod or project.parent / "SereneSeasons").resolve()
    pack = project / "content-pack/ce_seasons"
    assets = pack / "resourcepack/assets/ce_seasons"
    resources = project / "src/main/resources"
    datapack = resources / "season_datapack"
    colors_path = project / "tools/season-atmosphere.json"
    textures = (
        mod / "common/src/main/resources/assets/sereneseasons/textures"
    )

    wrapper = project / ("gradlew.bat" if os.name == "nt" else "gradlew")
    for path in (
        project / "build.gradle.kts",
        wrapper,
        pack / "pack.yml",
        datapack / "pack.mcmeta",
        mod / "LICENSE",
    ):
        require(path.is_file(), f"缺少文件：{path}")

    require(textures.is_dir(), f"找不到模组贴图目录：{textures}")

    metadata = read_json(datapack / "pack.mcmeta")["pack"]
    minimum = metadata.get("min_format", metadata.get("pack_format"))
    require(
        minimum in (121, [121, 0]),
        f"Expected data pack format 121.0, got {minimum!r}"
    )

    colors = read_json(colors_path) if colors_path.exists() else DEFAULT_COLORS
    validate_colors(colors)

    # 所有修改先在内存中准备，校验完成后才写入。
    # value=None 表示删除已过期的动画元数据。
    changes = {}
    imported_pngs = []

    def put(path, data):
        contained(path, project)
        changes[path] = data

    def put_json(path, value):
        put(path, json_bytes(value))

    if not colors_path.exists():
        put_json(colors_path, colors)

    def import_texture(relative):
        source = textures / relative
        require(source.is_file(), f"缺少模组贴图：{source}")
        data = source.read_bytes()
        require(
            len(data) >= 24 and data[:8] == b"\x89PNG\r\n\x1a\n",
            f"不是有效 PNG 文件头：{source}"
        )
        destination = assets / "textures" / relative
        put(destination, data)
        imported_pngs.append(destination)

        source_meta = Path(str(source) + ".mcmeta")
        target_meta = Path(str(destination) + ".mcmeta")
        if source_meta.exists():
            read_json(source_meta)
            put(target_meta, source_meta.read_bytes())
        elif target_meta.exists():
            put(target_meta, None)

        return "ce_seasons:" + relative[:-4]

    # 十二温带阶段、六热带阶段、未知状态。
    calendars = [
        (f"stage_{i}", f"calendar_{i:02d}") for i in range(12)
    ] + [
        (f"stage_{12 + i}", f"calendar_tropical_{i:02d}")
        for i in range(6)
    ] + [("unknown", "calendar_null")]

    for model_name, texture_name in calendars:
        model = assets / f"models/item/calendar/{model_name}.json"
        item_definition = assets / f"items/calendar/{model_name}.json"
        require(model.is_file(), f"缺少现有日历模型：{model}")
        require(item_definition.is_file(),
                f"缺少现有物品模型定义：{item_definition}")
        read_json(item_definition)  # 保留现有 CE/物品模型身份。

        reference = import_texture(f"item/{texture_name}.png")
        put_json(model, {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": reference}
        })

    side = import_texture("block/season_sensor_side.png")
    for season in SEASONS:
        model = assets / f"models/block/season_sensor/{season}.json"
        require(model.is_file(), f"缺少现有传感器模型：{model}")
        top = import_texture(f"block/season_sensor_{season}_top.png")

        # 模型仍使用原 CE resource key，只更换贴图和显示模型。
        put_json(model, {
            "parent": "minecraft:block/template_daylight_detector",
            "textures": {
                "top": top,
                "side": side,
                "particle": top
            }
        })

    put(
        pack / "SS-ASSET-SOURCE.txt",
        (
            "Calendar and season sensor PNG files were copied from the "
            "operator's local SereneSeasons source tree.\n"
            "Original asset rights remain with their respective owners.\n"
            "The source LICENSE is retained as SS-SOURCE-LICENSE.txt.\n"
            "This import does not grant additional redistribution rights.\n"
            "Seasonal sky/fog/cloud presets are addon configuration, "
            "not imported SereneSeasons artwork.\n"
        ).encode("utf-8")
    )
    put(pack / "SS-SOURCE-LICENSE.txt", (mod / "LICENSE").read_bytes())
    # 以插件自身索引为准，不根据名字猜测哪些群系启用季节。
    indexes = list(resources.rglob("biomes.index"))
    require(len(indexes) == 1,
            f"需要唯一 biomes.index，实际找到 {len(indexes)} 个")

    biome_changes = {}
    seen_keys = set()
    row_count = 0

    for number, line in enumerate(
        indexes[0].read_text(encoding="utf-8-sig").splitlines(), 1
    ):
        line = line.strip()
        if not line or line.startswith("#"):
            continue

        cells = [part.strip() for part in line.split("|")]
        require(
            len(cells) == 15,
            f"biomes.index 第 {number} 行应有 15 列，实际为 {len(cells)} 列"
        )
        base, enabled_text, tropical_text, *keys = cells
        require(base.startswith("minecraft:"),
                f"索引中存在未支持的源群系：{base}")
        enabled = parse_bool(enabled_text)
        tropical = parse_bool(tropical_text)
        row_count += 1

        for stage, key in enumerate(keys):
            require(key not in seen_keys, f"重复群系变体：{key}")
            seen_keys.add(key)
            require(
                re.fullmatch(r"ceseasons:[a-z0-9_/-]+", key) is not None,
                f"非法或非本插件群系 key：{key}"
            )
            namespace, name = key.split(":", 1)
            require(
                name.endswith(f"/stage_{stage + 1:02d}"),
                f"群系阶段顺序异常：{key}"
            )
            path = datapack / f"data/{namespace}/worldgen/biome/{name}.json"
            contained(path, datapack)
            require(path.is_file(), f"缺少季节群系：{path}")
            definition = read_json(path)

            if not enabled:
                continue

            attributes = definition.setdefault("attributes", {})
            require(isinstance(attributes, dict),
                    f"群系 attributes 不是对象：{key}")

            if tropical:
                profile = colors["tropical"][stage // 2]
            else:
                profile = colors["temperate"][SEASONS[stage // 3]]

            for short_name, attribute in ATTRIBUTE_KEYS.items():
                attributes[attribute] = profile[short_name]

            put_json(path, definition)
            biome_changes[path] = definition

    require(row_count > 0 and biome_changes, "没有可修改的季节群系")
    require(len(imported_pngs) == 24, "导入贴图数量异常")

    print(f"项目：{project}")
    print(f"模组：{mod}")
    print(f"计划导入：{len(imported_pngs)} 张 PNG")
    print(f"计划更新：{len(biome_changes)} 个群系的天空/雾/云")
    print(f"配色配置：{colors_path}")

    if args.dry_run:
        print("\n检查完成。dry-run 未写文件，也未执行构建。")
        return

    # 备份仅涉及的文件，放在 build 外，避免被 Gradle clean 删除。
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    backup = project / "backups" / f"ss-assets-{stamp}"
    backup.mkdir(parents=True)
    newly_created = []

    for path in changes:
        relative = path.relative_to(project)
        if path.exists():
            require(path.is_file(), f"目标不是文件：{path}")
            destination = backup / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, destination)
        else:
            newly_created.append(relative.as_posix())

    atomic_write(
        backup / "NEW-FILES.json",
        json_bytes(newly_created)
    )
    print(f"备份：{backup}")

    for path, data in changes.items():
        if data is None:
            path.unlink(missing_ok=True)
        else:
            atomic_write(path, data)

    # 校验实际落盘数据。
    for path, data in changes.items():
        if data is None:
            require(not path.exists(), f"旧文件删除失败：{path}")
        else:
            require(path.read_bytes() == data, f"文件写入校验失败：{path}")

    print("\n运行完整 Gradle 测试和构建，不跳过任何测试……")
    if os.name == "nt":
        command = [
            "cmd.exe", "/d", "/c", ".\\gradlew.bat",
            "--no-daemon", "clean", "test", "build"
        ]
    else:
        command = [
            "sh", "./gradlew",
            "--no-daemon", "clean", "test", "build"
        ]

    if args.offline:
        command.append("--offline")
    result = subprocess.run(command, cwd=project)
    if result.returncode:
        failure_details(project)
        raise RuntimeError(
            "Gradle 失败，未生成 ready 交付目录。\n"
            "资源修改已保留，原文件在备份中；没有修改或跳过测试。\n"
            f"测试报告：{project / 'build/reports/tests/test/index.html'}"
        )

    # 从本次 clean build 产物中寻找真正的插件 JAR。
    jars = []
    for path in sorted((project / "build/libs").glob("*.jar")):
        if path.name.endswith(("-sources.jar", "-javadoc.jar")):
            continue
        with zipfile.ZipFile(path) as archive:
            if "paper-plugin.yml" in archive.namelist():
                jars.append(path)

    require(len(jars) == 1, f"需要唯一插件 JAR，实际找到：{jars}")
    plugin_jar = jars[0]

    # 防止构建任务重新生成旧数据包，悄悄覆盖刚加入的颜色。
    with zipfile.ZipFile(plugin_jar) as archive:
        require(archive.testzip() is None, "插件 JAR 完整性校验失败")
        for path, expected in biome_changes.items():
            member = path.relative_to(resources).as_posix()
            actual = json.loads(archive.read(member).decode("utf-8-sig"))
            require(
                actual == expected,
                f"JAR 内群系与修改结果不同：{member}。"
                "请检查构建期间是否重新运行了群系生成器。"
            )

    # 只打包 ce_seasons 目录，避免把旁边旧 ZIP 再套进新包。
    distributions = project / "build/distributions"
    distributions.mkdir(parents=True, exist_ok=True)
    staging = distributions / f".staging-{stamp}"
    staging.mkdir()
    content_zip = staging / "ce-seasons-content.zip"

    with zipfile.ZipFile(
        content_zip, "w", compression=zipfile.ZIP_DEFLATED
    ) as archive:
        for path in sorted(pack.rglob("*")):
            if path.is_file():
                contained(path, pack)
                archive.write(
                    path,
                    "ce_seasons/" + path.relative_to(pack).as_posix()
                )

    with zipfile.ZipFile(content_zip) as archive:
        require(archive.testzip() is None, "内容包 ZIP 完整性校验失败")
        for path in imported_pngs:
            member = "ce_seasons/" + path.relative_to(pack).as_posix()
            require(
                archive.read(member) == path.read_bytes(),
                f"内容包缺少贴图或贴图不一致：{member}"
            )

    shutil.copy2(plugin_jar, staging / plugin_jar.name)

    checksums = []
    for path in sorted(staging.iterdir()):
        if path.is_file():
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            checksums.append(f"{digest}  {path.name}")

    atomic_write(
        staging / "SHA256SUMS.txt",
        ("\n".join(checksums) + "\n").encode("utf-8")
    )
    atomic_write(
        staging / "INSTALL.txt",
        (
            "1. Stop the server before replacing the addon JAR.\n"
            "2. Back up the installed CE ce_seasons pack.\n"
            "3. Install the new addon JAR; do not keep duplicate versions.\n"
            "4. Extract ce-seasons-content.zip into CraftEngine/resources/.\n"
            "5. Start the server and rebuild/distribute the CE resource pack.\n"
            "6. Reconnect clients and inspect calendar, sensor and atmosphere.\n\n"
            "Atmosphere settings: tools/season-atmosphere.json\n"
            "After changing settings, rerun this script and reinstall the JAR.\n"
            "This script does NOT fix Java chunk/movement refresh logic.\n"
            "Source assets retain their original license.\n"
        ).encode("utf-8")
    )

    ready = distributions / f"seasons-ready-{stamp}"
    os.replace(staging, ready)

    print("\n完成：测试、构建及制品校验通过。")
    print(f"交付目录：{ready}")
    print(f"修改前备份：{backup}")
    print("真实服务端和客户端效果仍需安装后验证。")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n已中断；如已经修改文件，请查看 backups。", file=sys.stderr)
        sys.exit(130)
    except Exception as error:
        print(f"\nERROR: {error}", file=sys.stderr)
        sys.exit(1)
