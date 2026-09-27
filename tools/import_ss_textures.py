from pathlib import Path
from datetime import datetime
import json
import shutil

root = Path(r"E:\Projects\Seasons")
project = root / "craft-engine-seasons"
pack = project / "content-pack/ce_seasons"
assets = pack / "resourcepack/assets/ce_seasons"
source = (
    root / "SereneSeasons/common/src/main/resources/"
    "assets/sereneseasons/textures"
)

# 保留插件当前使用的 stage_0..17 和 unknown 模型路径。
calendar = {
    **{f"stage_{i}": f"calendar_{i:02d}" for i in range(12)},
    **{f"stage_{12+i}": f"calendar_tropical_{i:02d}"
       for i in range(6)},
    "unknown": "calendar_null",
}
seasons = ("spring", "summer", "autumn", "winter")

models = {}
textures = set()

for stage, texture in calendar.items():
    textures.add(f"item/{texture}.png")

root = Path(r"E:\Projects\Seasons")
project = root / "craft-engine-seasons"
pack = project / "content-pack/ce_seasons"
assets = pack / "resourcepack/assets/ce_seasons"
source = (
    root / "SereneSeasons/common/src/main/resources/"
    "assets/sereneseasons/textures"
)

# 保留插件当前使用的 stage_0..17 和 unknown 模型路径。
calendar = {
    **{f"stage_{i}": f"calendar_{i:02d}" for i in range(12)},
    **{f"stage_{12+i}": f"calendar_tropical_{i:02d}"
       for i in range(6)},
    "unknown": "calendar_null",

models = {}
textures = set()

for stage, texture in calendar.items():
    textures.add(f"item/{texture}.png")
    models[f"models/item/calendar/{stage}.json"] = {
        "parent": "minecraft:item/generated"
        "textures": {"layer0": f"ce_seasons:item/{texture}"},
    }

textures.add("block/season_sensor_side.png")
for season in seasons:
    textures.add(f"block/season_sensor_{season}_top.png")
    models[f"models/block/season_sensor/{season}.json"] = {
        "parent": "minecraft:block/template_daylight_detector",
        "textures": {
            "side": "ce_seasons:block/season_sensor_side",
            "top": f"ce_seasons:block/season_sensor_{season}_top",
        },
    }

# 全部检查通过后才修改，避免来源路径错误导致

missing = [str(source / name) for name in textures
           if not (source / name).is_file()]
missing += [str(assets / name) for name in models
            if not (assets / name).is_file()]
if missing:
    raise SystemExit("文件缺失，未修改任何内

for name in textures:
    if not (source / name).read_bytes().star
se SystemExit(f"不是有效 PNG 文件：{source / name}")

stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
backup = project / "backups" / f"ce_seasons-{stamp}"
backup.parent.mkdir(parents=True, exist_ok=True)
shutil.copytree(pack, backup)

for name in sorted(textures):
    src = source / name
    dst = assets / "textures" / name
    dst.parent.mkdir(parents=True, exist_ok=
    }

# 全部检查通过后才修改，避免来源路径错误导致半套替换。
if not (pack / "pack.yml").is_file():
    raise SystemExit(f"找不到内容包：{pack}")

missing = [str(source / name) for name in textures
           if not (source / name).is_file()]
missing += [str(assets / name) for name in models
            if not (assets / name).is_file()]
if missing:
    raise SystemExit("文件缺失，未修改任何内容：\n" + "\n".join(missing))

for name in textures:
    if not (source / name).read_bytes().star
        raise SystemExit(f"不是有效 PNG 文件：{source / name}")

stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
backup = project / "backups" / f"ce_seasons-{stamp}"
backup.parent.mkdir(parents=True, exist_ok=True)
shutil.copytree(pack, backup)

for name in sorted(textures):
    src = source / name
    dst = assets / "textures" / name
    dst.parent.mkdir(parents=True, exist_ok=
    shutil.copy2(src, dst)
    metadata = Path(str(src) + ".mcmeta")
    if metadata.is_file():
        shutil.copy2(metadata, Path(str(dst) + ".mcmeta"))

for name, model in models.items():
    (assets / name).write_text(
        json.dumps(model, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

(pack / "ASSET-SOURCES.txt").write_text(
    "Calendar and season sensor textures: Se
    "Imported from the user's local SereneSeasons source checkout.\n"
    "Original asset license and rights remain with their authors.\n",
    encoding="utf-8",
)

output = project / "build/distributions/ce-seasons-content-ss-textures"
output.parent.mkdir(parents=True, exist_ok=True)
archive = shutil.make_archive(
    str(output), "zip", root_dir=pack.parent
)

print(f"已复制 {len(textures)} 张贴图，更新
print(f"备份：{backup}")
print(f"内容包：{archive}")