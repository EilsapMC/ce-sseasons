import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[3]
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('finish_seasons', ROOT / 'tools/finish_seasons.py')
finisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(finisher)


class FinishSeasonsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.project = Path(self.temporary.name) / 'project with spaces'
        self.mod = self.project.parent / 'SereneSeasons'
        self.pack = self.project / 'content-pack/ce_seasons'
        self.assets = self.pack / 'resourcepack/assets/ce_seasons'
        self.resources = self.project / 'src/main/resources'
        self.datapack = self.resources / 'season_datapack'
        self.textures = self.mod / 'common/src/main/resources/assets/sereneseasons/textures'
        self.png = b'\x89PNG\r\n\x1a\n' + bytes(24)
        self.write(self.project / 'build.gradle.kts', b'plugins { java }')
        self.write(self.project / 'gradlew.bat', b'@exit /b 0')
        self.write(self.project / 'gradlew', b'#!/bin/sh\nexit 0\n')
        self.write(self.mod / 'LICENSE', b'Original fixture license\n')
        self.write(self.pack / 'pack.yml', b'namespace: ce_seasons\n')
        for name, data_format in finisher.DATAPACKS.values():
            self.write_json(self.resources / name / 'pack.mcmeta',
                            {'pack': {'min_format': data_format, 'max_format': data_format}})
        calendars = [(f'stage_{i}', f'calendar_{i:02d}') for i in range(12)]
        calendars += [(f'stage_{12 + i}', f'calendar_tropical_{i:02d}') for i in range(6)]
        calendars.append(('unknown', 'calendar_null'))
        for name, texture in calendars:
            self.write_json(self.assets / f'models/item/calendar/{name}.json', {'old': True})
            self.write_json(self.assets / f'items/calendar/{name}.json', {'identity': name})
            self.write(self.textures / f'item/{texture}.png', self.png)
        self.write(self.textures / 'block/season_sensor_side.png', self.png)
        for season in finisher.SEASONS:
            self.write_json(self.assets / f'models/block/season_sensor/{season}.json', {'old': True})
            self.write(self.textures / f'block/season_sensor_{season}_top.png', self.png)
        self.original_biome = {
            'attributes': {'minecraft:visual/sky_color': '#123456', 'unrelated': {'keep': True}},
            'effects': {'water_color': '#123456'}, 'temperature': 0.8, 'features': [[]]
        }
        rows = []
        for name, enabled, tropical in [('plains', True, False), ('desert', True, True),
                                         ('the_end', False, False)]:
            keys = [f'ceseasons:{name}/stage_{stage:02d}' for stage in range(1, 13)]
            rows.append('|'.join([f'minecraft:{name}', str(enabled), str(tropical), *keys]))
            for root, _ in finisher.DATAPACKS.values():
                for stage in range(1, 13):
                    path = self.resources / root / f'data/ceseasons/worldgen/biome/{name}/stage_{stage:02d}.json'
                    self.write_json(path, self.original_biome)
        for root, _ in finisher.DATAPACKS.values():
            self.write(self.resources / root / 'biomes.index', ('\n'.join(rows) + '\n').encode())

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value)

    def write_json(self, path, value):
        self.write(path, finisher.json_bytes(value))

    def biome(self, name, stage):
        return self.datapack / f'data/ceseasons/worldgen/biome/{name}/stage_{stage:02d}.json'

    def snapshot(self):
        return {path.relative_to(self.project).as_posix(): path.read_bytes()
                for path in self.project.rglob('*') if path.is_file()}

    def run_main(self, *arguments):
        argv = ['finish_seasons.py', '--project', str(self.project), *arguments]
        with mock.patch.object(sys, 'argv', argv), contextlib.redirect_stdout(io.StringIO()):
            finisher.main()

    def build_fixture(self, command, cwd):
        self.assertEqual(self.project, cwd)
        self.assertEqual(['--no-daemon', 'clean', 'test', 'build'], command[-4:])
        if os.name == 'nt':
            self.assertEqual(['cmd.exe', '/d', '/c', '.\\gradlew.bat'], command[:4])
        jar = self.project / 'build/libs/seasons-test.jar'
        jar.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('paper-plugin.yml', 'name: Test\n')
            for path in self.resources.rglob('*'):
                if path.is_file():
                    archive.write(path, path.relative_to(self.resources).as_posix())
        return subprocess.CompletedProcess(command, 0)

    def test_dry_run_does_not_write_or_build(self):
        before = self.snapshot()
        with mock.patch.object(finisher.subprocess, 'run') as run:
            self.run_main('--dry-run')
        run.assert_not_called()
        self.assertEqual(before, self.snapshot())

    def test_complete_import_preserves_identities_and_excluded_biomes(self):
        identity = self.assets / 'items/calendar/stage_0.json'
        identity_before = identity.read_bytes()
        with mock.patch.object(finisher.subprocess, 'run', side_effect=self.build_fixture):
            self.run_main()
        self.assertEqual(identity_before, identity.read_bytes())
        self.assertEqual(24, len(list((self.assets / 'textures').rglob('*.png'))))
        self.assertEqual((self.mod / 'LICENSE').read_bytes(), (self.pack / 'SS-SOURCE-LICENSE.txt').read_bytes())
        for stage in range(1, 13):
            self.assertEqual(self.original_biome, finisher.read_json(self.biome('the_end', stage)))
            for name, profile in [
                ('plains', finisher.DEFAULT_COLORS['temperate'][finisher.SEASONS[(stage - 1) // 3]]),
                ('desert', finisher.DEFAULT_COLORS['tropical'][(stage - 1) // 2])
            ]:
                actual = finisher.read_json(self.biome(name, stage))
                for short, key in finisher.ATTRIBUTE_KEYS.items():
                    self.assertEqual(profile[short], actual['attributes'][key])
                self.assertEqual({'keep': True}, actual['attributes']['unrelated'])
                for key in ('effects', 'temperature', 'features'):
                    self.assertEqual(self.original_biome[key], actual[key])
        ready = next((self.project / 'build/distributions').glob('seasons-ready-*'))
        with zipfile.ZipFile(ready / 'ce-seasons-content.zip') as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(24, sum(name.endswith('.png') for name in archive.namelist()))
        self.assertTrue((ready / 'SHA256SUMS.txt').is_file())
        backup = next((self.project / 'backups').iterdir())
        self.assertIn('tools/season-atmosphere.json', finisher.read_json(backup / 'NEW-FILES.json'))

    def test_existing_color_config_is_used_without_overwrite(self):
        colors = copy.deepcopy(finisher.DEFAULT_COLORS)
        colors['temperate']['spring']['sky'] = '#112233'
        path = self.project / 'tools/season-atmosphere.json'
        self.write_json(path, colors)
        before = path.read_bytes()
        with mock.patch.object(finisher.subprocess, 'run', side_effect=self.build_fixture):
            self.run_main()
        self.assertEqual(before, path.read_bytes())
        for root, _ in finisher.DATAPACKS.values():
            biome = self.resources / root / 'data/ceseasons/worldgen/biome/plains/stage_01.json'
            self.assertEqual('#112233', finisher.read_json(biome)['attributes']['minecraft:visual/sky_color'])

    def test_missing_older_version_pack_fails_before_any_changes(self):
        path = self.resources / 'season_datapack_26_1_2/pack.mcmeta'
        path.unlink()
        before = self.snapshot()
        with mock.patch.object(finisher.subprocess, 'run') as run:
            with self.assertRaises(FileNotFoundError):
                self.run_main()
        run.assert_not_called()
        self.assertEqual(before, self.snapshot())

    def test_invalid_color_aborts_before_resource_changes(self):
        colors = copy.deepcopy(finisher.DEFAULT_COLORS)
        colors['tropical'][0]['cloud'] = '#FFFFFF'
        self.write_json(self.project / 'tools/season-atmosphere.json', colors)
        before = self.snapshot()
        with mock.patch.object(finisher.subprocess, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'cloud'):
                self.run_main()
        run.assert_not_called()
        self.assertEqual(before, self.snapshot())

    def test_animation_metadata_copies_and_removes_stale_files(self):
        copied = self.assets / 'textures/item/calendar_00.png.mcmeta'
        stale = self.assets / 'textures/item/calendar_01.png.mcmeta'
        self.write_json(self.textures / 'item/calendar_00.png.mcmeta', {'animation': {'frametime': 2}})
        self.write_json(stale, {'animation': {}})
        with mock.patch.object(finisher.subprocess, 'run', side_effect=self.build_fixture):
            self.run_main()
        self.assertEqual({'animation': {'frametime': 2}}, finisher.read_json(copied))
        self.assertFalse(stale.exists())

    def test_failed_build_keeps_backup_and_does_not_publish_ready(self):
        with mock.patch.object(finisher.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1)):
            with self.assertRaisesRegex(RuntimeError, 'Gradle'):
                self.run_main()
        self.assertTrue(any((self.project / 'backups').iterdir()))
        self.assertFalse(list((self.project / 'build/distributions').glob('seasons-ready-*')))

    def test_offline_mode_is_forwarded_to_gradle(self):
        def build(command, cwd):
            self.assertEqual('--offline', command[-1])
            return self.build_fixture(command[:-1], cwd)
        with mock.patch.object(finisher.subprocess, 'run', side_effect=build):
            self.run_main('--offline')
        self.assertTrue(list((self.project / 'build/distributions').glob('seasons-ready-*')))

    def test_invalid_index_boolean_is_rejected(self):
        self.assertTrue(finisher.parse_bool(' TRUE '))
        self.assertFalse(finisher.parse_bool('0'))
        with self.assertRaises(RuntimeError):
            finisher.parse_bool('yes')


if __name__ == '__main__':
    unittest.main()
