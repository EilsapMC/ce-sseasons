import copy
import importlib.util
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('generate_biomes', ROOT / 'tools/generate_biomes.py')
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class BiomeGeneratorTest(unittest.TestCase):
    def template(self):
        return {'attributes': {'minecraft:visual/sky_color': '#78a7ff',
                               'minecraft:gameplay/natural_mob_spawns': {'modifier': 'overlay', 'argument': {}}},
                'carvers': ['minecraft:cave'], 'features': [[], ['minecraft:trees_plains']],
                'has_precipitation': True, 'temperature': 0.8, 'downfall': 0.4,
                'effects': {'water_color': '#3f76e4', 'grass_color_modifier': 'swamp'}}

    def test_deep_copy_preserves_unrelated_codec_fields(self):
        source = self.template()
        original = copy.deepcopy(source)
        for stage in range(1, 13):
            result = generator.transform(source, stage)
            for key in ('attributes', 'carvers', 'features', 'downfall', 'has_precipitation'):
                self.assertEqual(source[key], result[key])
            self.assertEqual(source['effects']['water_color'], result['effects']['water_color'])
            self.assertEqual('none', result['effects']['grass_color_modifier'])
            for key in ('grass_color', 'foliage_color', 'dry_foliage_color'):
                self.assertRegex(result['effects'][key], r'^#[0-9a-f]{6}$')
        self.assertEqual(original, source)

    def test_temperature_matches_snapshot_offsets(self):
        expected = [0.55, 0.8, 0.8, 0.8, 0.8, 0.8, 0.8, 0.8, 0.55, 0.0, 0.0, 0.0]
        self.assertEqual(expected, [generator.transform(self.template(), i)['temperature'] for i in range(1, 13)])
        warm = self.template()
        warm['temperature'] = 1.0
        self.assertEqual(1.0, generator.transform(warm, 11)['temperature'])

    def test_tropical_pairs_share_colors_and_mid_dry_wet_precipitation(self):
        template = self.template()
        for phase in range(6):
            left = generator.transform(template, phase * 2 + 1, 'desert')
            right = generator.transform(template, phase * 2 + 2, 'desert')
            self.assertEqual(left, right)
            self.assertEqual(template['temperature'], left['temperature'])
        self.assertFalse(generator.transform(template, 3, 'desert')['has_precipitation'])
        template['has_precipitation'] = False
        self.assertTrue(generator.transform(template, 9, 'desert')['has_precipitation'])

    def test_reject_legacy_integer_rgb_and_incomplete_template(self):
        template = self.template()
        template['effects']['water_color'] = 4159204
        with self.assertRaises(ValueError):
            generator.transform(template, 1)
        with self.assertRaises(ValueError):
            generator.transform({}, 1)
        with self.assertRaises(ValueError):
            generator.transform(self.template(), 0)

    def test_complete_pack_matches_index_and_versions(self):
        pack = ROOT / 'src/main/resources/season_datapack'
        metadata = json.loads((pack / 'pack.mcmeta').read_text(encoding='utf-8'))
        self.assertEqual([121, 0], metadata['pack']['min_format'])
        self.assertEqual([121, 0], metadata['pack']['max_format'])
        manifest = json.loads((pack / 'manifest.json').read_text(encoding='utf-8'))
        self.assertEqual([97, 1], manifest['resource_format'])
        self.assertEqual(67, len(manifest['biomes']))
        count = 0
        for biome in manifest['biomes']:
            self.assertEqual(12, len(biome['variants']))
            self.assertEqual(biome['base'].split(':')[1] not in generator.EXCLUDED, biome['seasonal'])
            for key in biome['variants']:
                path = pack / 'data/ceseasons/worldgen/biome' / (key.split(':')[1] + '.json')
                value = json.loads(path.read_text(encoding='utf-8'))
                for color in ('water_color', 'grass_color', 'foliage_color', 'dry_foliage_color'):
                    self.assertRegex(value['effects'][color], r'^#[0-9a-fA-F]{6}$')
                self.assertIn('attributes', value)
                self.assertIn('features', value)
                self.assertIn('carvers', value)
                count += 1
        self.assertEqual(804, count)


if __name__ == '__main__':
    unittest.main()
