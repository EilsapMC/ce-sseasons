import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('seasons_build', ROOT / 'build.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
REPOSITORY = 'https://github.com/Glitchfiend/SereneSeasons'
REVISION = 'a' * 40


class BuildPipelineTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.project = Path(temporary.name) / 'project with spaces'
        self.project.mkdir()
        self.cache = self.project / '.cache/sereneseasons'
        self.mod = self.project / 'local mod'
        for name in ('build.gradle.kts', 'tools/finish_seasons.py',
                     'content-pack/ce_seasons/pack.yml', 'gradle/wrapper/gradle-wrapper.jar'):
            path = self.project / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture', encoding='utf-8')

    def source_assets(self, path):
        path.mkdir(parents=True, exist_ok=True)
        (path / 'LICENSE').write_text('Fixture license', encoding='utf-8')
        textures = path / 'common/src/main/resources/assets/sereneseasons/textures'
        names = [f'item/calendar_{i:02d}.png' for i in range(12)]
        names += [f'item/calendar_tropical_{i:02d}.png' for i in range(6)]
        names += ['item/calendar_null.png', 'block/season_sensor_side.png']
        names += [f'block/season_sensor_{season}_top.png'
                  for season in ('spring', 'summer', 'autumn', 'winter')]
        for name in names:
            file = textures / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(b'\x89PNG\r\n\x1a\n' + bytes(24))

    def main(self, arguments):
        with mock.patch.object(builder, 'ROOT', self.project), \
                mock.patch.object(builder, 'check_tools') as tools, \
                mock.patch.object(builder, 'source_lock', return_value=(REPOSITORY, REVISION)), \
                mock.patch.object(builder, 'run') as run, \
                contextlib.redirect_stdout(io.StringIO()):
            builder.main(arguments)
        return tools, run

    def test_lock_requires_https_and_full_commit(self):
        path = self.project / 'source.json'
        for repository, revision in [(REPOSITORY, 'main'), ('http://example.com/repo', REVISION),
                                     ('https://user:secret@example.com/repo', REVISION)]:
            with self.subTest(repository=repository):
                path.write_text(json.dumps({'repository': repository, 'revision': revision}))
                with self.assertRaises(RuntimeError):
                    builder.source_lock(path)
        path.write_text(json.dumps({'repository': REPOSITORY, 'revision': REVISION}))
        self.assertEqual((REPOSITORY, REVISION), builder.source_lock(path))

    def test_fresh_dry_run_never_fetches_or_creates_cache(self):
        with mock.patch.object(builder, 'ensure_source') as fetch:
            _, run = self.main(['--dry-run'])
        fetch.assert_not_called()
        run.assert_not_called()
        self.assertFalse(self.cache.exists())

    def test_missing_cache_offline_fails_without_writes(self):
        with mock.patch.object(builder, 'git') as git:
            with self.assertRaisesRegex(RuntimeError, 'Offline source cache'):
                builder.ensure_source(REPOSITORY, REVISION, self.cache, offline=True)
        git.assert_not_called()
        self.assertFalse(self.cache.exists())

    def test_local_source_runs_preflight_tests_then_build_without_git(self):
        self.source_assets(self.mod)
        with mock.patch.object(builder, 'ensure_source') as fetch:
            tools, run = self.main(['--mod', str(self.mod), '--offline'])
        fetch.assert_not_called()
        tools.assert_called_once_with(needs_git=False)
        calls = [call.args[0] for call in run.call_args_list]
        self.assertEqual(3, len(calls))
        self.assertEqual('--dry-run', calls[0][-1])
        self.assertIn('unittest', calls[1])
        self.assertEqual('--offline', calls[2][-1])
        self.assertIn(str(self.mod), calls[2])

    def test_local_dry_run_only_delegates_validation(self):
        self.source_assets(self.mod)
        _, run = self.main(['--mod', str(self.mod), '--dry-run'])
        self.assertEqual(1, run.call_count)
        self.assertEqual('--dry-run', run.call_args.args[0][-1])
        self.assertFalse(self.cache.exists())

    def test_valid_cache_is_reused_without_fetch(self):
        target = self.cache / REVISION
        target.mkdir(parents=True)
        with mock.patch.object(builder, 'validate_checkout') as validate, \
                mock.patch.object(builder, 'git') as git, contextlib.redirect_stdout(io.StringIO()):
            result = builder.ensure_source(REPOSITORY, REVISION, self.cache)
        self.assertEqual(target, result)
        validate.assert_called_once_with(target, REPOSITORY, REVISION)
        git.assert_not_called()

    def test_dirty_cache_is_refused_not_reset(self):
        target = self.cache / REVISION
        (target / '.git').mkdir(parents=True)
        with mock.patch.object(builder, 'git', side_effect=[str(target), REPOSITORY, REVISION, ' M LICENSE']) as git:
            with self.assertRaisesRegex(RuntimeError, 'local changes'):
                builder.validate_checkout(target, REPOSITORY, REVISION)
        self.assertEqual(['rev-parse', 'remote', 'rev-parse', 'status'],
                         [call.args[0] for call in git.call_args_list])

    def test_fetch_failure_does_not_publish_partial_checkout(self):
        def git(*arguments, cwd, capture=True):
            if arguments[0] == 'init':
                (Path(arguments[1]) / '.git').mkdir(parents=True)
            if arguments[0] == 'fetch':
                raise RuntimeError('download failed')
            return ''
        with mock.patch.object(builder, 'git', side_effect=git), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, 'download failed'):
                builder.ensure_source(REPOSITORY, REVISION, self.cache)
        self.assertFalse((self.cache / REVISION).exists())
        self.assertEqual([], list(self.cache.iterdir()))

    def test_download_is_pinned_and_verified_before_cache_publication(self):
        def git(*arguments, cwd, capture=True):
            if arguments[0] == 'init':
                Path(arguments[1]).mkdir(parents=True)
            return ''
        with mock.patch.object(builder, 'git', side_effect=git) as command, \
                mock.patch.object(builder, 'validate_checkout') as validate, \
                contextlib.redirect_stdout(io.StringIO()):
            target = builder.ensure_source(REPOSITORY, REVISION, self.cache)
        self.assertTrue(target.is_dir())
        validate.assert_called_once()
        fetch = next(call.args for call in command.call_args_list if call.args[0] == 'fetch')
        self.assertEqual(('fetch', '--depth=1', '--no-tags', 'origin', REVISION), fetch)
        self.assertEqual([target], list(self.cache.iterdir()))

    def test_invalid_png_stops_before_processing(self):
        self.source_assets(self.mod)
        texture = self.mod / 'common/src/main/resources/assets/sereneseasons/textures/item/calendar_00.png'
        texture.write_bytes(b'not an image')
        with self.assertRaisesRegex(RuntimeError, 'Invalid PNG'):
            builder.validate_assets(self.mod)

    def test_java_home_is_used_for_runtime_check(self):
        java = self.project / 'jdk/bin' / ('java.exe' if builder.os.name == 'nt' else 'java')
        java.parent.mkdir(parents=True)
        java.touch()
        result = subprocess.CompletedProcess([], 0, '', 'openjdk version "25.0.2"')
        with mock.patch.dict(builder.os.environ, {'JAVA_HOME': str(java.parent.parent)}), \
                mock.patch.object(builder.subprocess, 'run', return_value=result) as run:
            builder.check_tools(needs_git=False)
        self.assertEqual(str(java), run.call_args.args[0][0])


if __name__ == '__main__':
    unittest.main()
