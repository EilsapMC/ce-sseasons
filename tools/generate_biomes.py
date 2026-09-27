#!/usr/bin/env python3
"""Generate 26.3 server registry biomes, using Mojang's local templates only.

Run before processResources/package: python tools/generate_biomes.py --vanilla PATH
PATH is a vanilla resource root or its data/minecraft/worldgen/biome directory.
No SS color tables, textures, downloaded data, or client-only registry append.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'src/main/resources/season_datapack'
NAMESPACE = 'ceseasons'
# Original hand-authored grass, foliage, dry foliage RGB palette, spring -> winter.
PALETTE = (
    ('#8ba971', '#80a66b', '#a7a276'),
    ('#83b568', '#74b15d', '#a4ab70'),
    ('#74b65b', '#64a94f', '#9aa564'),
    ('#6eab50', '#5b9c46', '#a3a260'),
    ('#75a24e', '#629343', '#b0a364'),
    ('#829c52', '#739047', '#b7a064'),
    ('#a29a58', '#ad914b', '#be995e'),
    ('#b19a61', '#c08246', '#c39161'),
    ('#aa9570', '#ac7656', '#b78b69'),
    ('#91a091', '#7f9486', '#a5a598'),
    ('#8f9e9a', '#829a94', '#a1a69e'),
    ('#93a28f', '#829d84', '#a4a68d'),
)
RGB = re.compile(r'^#[0-9a-fA-F]{6}$')
TROPICAL = frozenset({'jungle', 'sparse_jungle', 'bamboo_jungle', 'mangrove_swamp',
                      'savanna', 'savanna_plateau', 'windswept_savanna', 'desert',
                      'badlands', 'eroded_badlands', 'wooded_badlands', 'mushroom_fields'})
EXCLUDED = frozenset({'nether_wastes', 'soul_sand_valley', 'crimson_forest',
                      'warped_forest', 'basalt_deltas', 'the_end', 'end_highlands',
                      'end_midlands', 'small_end_islands', 'end_barrens', 'the_void',
                      'deep_dark', 'dripstone_caves', 'lush_caves', 'ocean', 'deep_ocean',
                      'frozen_ocean', 'deep_frozen_ocean', 'cold_ocean', 'deep_cold_ocean',
                      'lukewarm_ocean', 'deep_lukewarm_ocean', 'warm_ocean',
                      'river', 'beach', 'stony_shore'})


def variant_key(biome: str, stage: int) -> str:
    if not 1 <= stage <= 12:
        raise ValueError('stage must be 1..12')
    return f'{NAMESPACE}:{biome}/stage_{stage:02d}'


# Six original dry/wet colors, each shared by two of the twelve registered stages.
TROPICAL_PALETTE = (
    ('#a9a052', '#859342', '#bea365'), ('#b5a366', '#a2944b', '#c1a175'),
    ('#a3a15c', '#879c4e', '#b5a46b'), ('#83b166', '#65a44e', '#a6ab69'),
    ('#68ae58', '#4f9e46', '#97a660'), ('#78ae5e', '#62a24b', '#a1a567'),
)


def transform(template: dict, stage: int, biome: str = 'plains') -> dict:
    if not 1 <= stage <= 12:
        raise ValueError('stage must be 1..12')
    if not isinstance(template.get('attributes', {}), dict):
        raise ValueError('26.3 positional attributes must be an object')
    required = {'carvers', 'features', 'temperature', 'downfall', 'has_precipitation', 'effects'}
    if not required.issubset(template):
        raise ValueError(f'Not a complete DIRECT_CODEC template: missing {required - template.keys()}')
    if not RGB.fullmatch(str(template['effects'].get('water_color'))):
        raise ValueError('26.3 STRING_RGB_COLOR requires #rrggbb strings, not legacy integers')
    result = copy.deepcopy(template)
    tropical = biome in TROPICAL
    grass, foliage, dry = TROPICAL_PALETTE[(stage - 1) // 2] if tropical else PALETTE[stage - 1]
    result['effects'].update(grass_color=grass, foliage_color=foliage, dry_foliage_color=dry)
    if biome not in EXCLUDED:
        if tropical:
            tropical_stage = (stage - 1) // 2
            if tropical_stage == 1:
                result['has_precipitation'] = False
            elif tropical_stage == 4:
                result['has_precipitation'] = True
        elif template['temperature'] <= 0.8:
            offset = -0.25 if stage in (1, 9) else -0.8 if stage >= 10 else 0.0
            result['temperature'] = round(max(-0.5, min(2.0, template['temperature'] + offset)), 6)
    # Overrides must not be replaced by the vanilla swamp/dark-forest color modifier.
    if 'grass_color_modifier' in result['effects']:
        result['effects']['grass_color_modifier'] = 'none'
    return result


def source_directory(path: Path) -> Path:
    nested = path / 'data/minecraft/worldgen/biome'
    result = nested if nested.is_dir() else path
    if not (result / 'plains.json').is_file():
        raise ValueError(f'Vanilla biome resource directory not found: {path}')
    return result


def generate(vanilla: Path, output: Path = OUTPUT, only: set[str] | None = None) -> dict:
    source = source_directory(vanilla)
    version_file = source.parents[3] / 'version.json'
    if not version_file.is_file():
        raise ValueError(f'26.3 version.json missing next to resource root: {version_file}')
    version = json.loads(version_file.read_text(encoding='utf-8'))
    expected_pack = {'resource_major': 97, 'resource_minor': 1, 'data_major': 121, 'data_minor': 0}
    if version.get('id') != '26.3' or version.get('pack_version') != expected_pack:
        raise ValueError('Generator requires exact 26.3 / data 121.0 / resource 97.1 templates')
    files = sorted(source.glob('*.json'))
    if only is not None:
        files = [p for p in files if p.stem in only]
        if {p.stem for p in files} != only:
            raise ValueError('Requested template is not present')
    # Validate every source before writing any generated file.
    entries = []
    for file in files:
        raw = file.read_bytes()
        template = json.loads(raw)
        variants = [transform(template, stage, file.stem) for stage in range(1, 13)]
        entries.append((file.stem, raw, variants))
    if not entries:
        raise ValueError('No vanilla biome templates found')
    output.mkdir(parents=True, exist_ok=True)
    pack = {'pack': {'description': 'CESeasons original seasonal server biome variants (26.3)',
                     'min_format': [121, 0], 'max_format': [121, 0]}}
    (output / 'pack.mcmeta').write_text(json.dumps(pack, indent=2) + '\n', encoding='utf-8')
    manifest = {'schema': 1, 'minecraft': '26.3', 'data_format': [121, 0],
                'resource_format': [97, 1], 'stages': 12, 'biomes': []}
    for name, raw, variants in entries:
        directory = output / f'data/{NAMESPACE}/worldgen/biome/{name}'
        directory.mkdir(parents=True, exist_ok=True)
        for stage, variant in enumerate(variants, 1):
            (directory / f'stage_{stage:02d}.json').write_text(
                json.dumps(variant, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
        manifest['biomes'].append({'base': f'minecraft:{name}',
                                   'seasonal': name not in EXCLUDED,
                                   'tropical': name in TROPICAL,
                                   'template_sha256': hashlib.sha256(raw).hexdigest(),
                                   'variants': [variant_key(name, i) for i in range(1, 13)]})
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    # Runtime reads this dependency-free index; source hashes remain in manifest.json.
    lines = ['# base|seasonal|tropical|twelve registry keys; generated, do not edit']
    lines.extend('|'.join((b['base'], str(b['seasonal']).lower(), str(b['tropical']).lower(),
                           *b['variants'])) for b in manifest['biomes'])
    (output / 'biomes.index').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vanilla', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=OUTPUT)
    parser.add_argument('--only', nargs='+', help='Smoke fixture only; omit for release generation')
    args = parser.parse_args()
    result = generate(args.vanilla, args.output, set(args.only) if args.only else None)
    print(f"Generated {len(result['biomes']) * 12} biomes; data 121.0, resource reference 97.1")


if __name__ == '__main__':
    main()
