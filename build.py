#!/usr/bin/env python3
"""Fetch pinned SereneSeasons assets, test, and build the addon and CE pack."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent
LOCK = ROOT / 'tools/sereneseasons-source.json'


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def source_lock(path=LOCK):
    value = json.loads(path.read_text(encoding='utf-8-sig'))
    repository = value.get('repository', '')
    revision = value.get('revision', '')
    url = urlsplit(repository)
    require(url.scheme == 'https' and url.hostname and not url.username
            and not url.password and not url.query and not url.fragment,
            'Source repository must be an HTTPS URL without credentials or query parameters')
    require(isinstance(revision, str) and re.fullmatch(r'[0-9a-f]{40}', revision),
            'Source revision must be a full, lowercase 40-character Git commit SHA')
    return repository, revision


def run(command, cwd=ROOT, capture=False, env=None):
    result = subprocess.run(command, cwd=cwd, env=env,
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None,
                            text=True, encoding='utf-8', errors='replace')
    if result.returncode:
        detail = (result.stderr or result.stdout or '').strip()
        raise RuntimeError(f'{command[0]} failed (exit {result.returncode})'
                           + (f': {detail}' if detail else ''))
    return result.stdout.strip() if capture else None


def git(*arguments, cwd, capture=True):
    env = os.environ.copy()
    env['GIT_TERMINAL_PROMPT'] = '0'
    return run(['git', *arguments], cwd=cwd, capture=capture, env=env)


def validate_assets(mod):
    require((mod / 'LICENSE').is_file(), f'Missing SereneSeasons LICENSE: {mod}')
    textures = mod / 'common/src/main/resources/assets/sereneseasons/textures'
    names = [f'item/calendar_{i:02d}.png' for i in range(12)]
    names += [f'item/calendar_tropical_{i:02d}.png' for i in range(6)]
    names += ['item/calendar_null.png', 'block/season_sensor_side.png']
    names += [f'block/season_sensor_{season}_top.png'
              for season in ('spring', 'summer', 'autumn', 'winter')]
    for name in names:
        path = textures / name
        require(path.is_file(), f'Missing SereneSeasons texture: {path}')
        data = path.read_bytes()
        require(len(data) >= 24 and data[:8] == b'\x89PNG\r\n\x1a\n',
                f'Invalid PNG header: {path}')


def validate_checkout(path, repository, revision):
    require(path.is_dir() and not path.is_symlink() and (path / '.git').is_dir(),
            f'Cache is not a standalone Git checkout: {path}')
    top = Path(git('rev-parse', '--show-toplevel', cwd=path)).resolve()
    require(top == path.resolve(), f'Unexpected Git working tree: {top}')
    require(git('remote', 'get-url', 'origin', cwd=path) == repository,
            f'Cached repository URL differs from source lock: {path}')
    require(git('rev-parse', 'HEAD', cwd=path) == revision,
            f'Cached checkout is not at the locked revision: {path}')
    require(not git('status', '--porcelain', '--untracked-files=all', cwd=path),
            f'Cached checkout has local changes; preserve or move it before retrying: {path}')
    validate_assets(path)


def ensure_source(repository, revision, cache_root, offline=False):
    target = cache_root / revision
    if target.exists():
        validate_checkout(target, repository, revision)
        print(f'Using verified source cache: {target}', flush=True)
        return target
    require(not offline, f'Offline source cache is missing: {target}; run online once or pass --mod')
    cache_root.mkdir(parents=True, exist_ok=True)
    print(f'Fetching SereneSeasons {revision} from {repository}', flush=True)
    # Build in a separate directory: an interrupted fetch never becomes a valid cache.
    with tempfile.TemporaryDirectory(prefix='.fetch-', dir=cache_root) as temporary:
        checkout = Path(temporary) / 'source'
        git('init', str(checkout), cwd=cache_root, capture=False)
        git('remote', 'add', 'origin', repository, cwd=checkout)
        git('fetch', '--depth=1', '--no-tags', 'origin', revision, cwd=checkout, capture=False)
        git('-c', 'core.autocrlf=false', 'checkout', '--detach', 'FETCH_HEAD', cwd=checkout, capture=False)
        validate_checkout(checkout, repository, revision)
        checkout.rename(target)
    return target


def check_tools(needs_git):
    require(sys.version_info >= (3, 10), 'Python 3.10 or newer is required')
    if needs_git:
        require(shutil.which('git'), 'Git is missing from PATH; install Git or use --mod with local sources')
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        java = Path(java_home.strip('"')) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        require(java.is_file(), f'JAVA_HOME does not contain Java: {java}')
    else:
        java = shutil.which('java')
        require(java, 'Java 25 is required; install a JDK and set JAVA_HOME')
    result = subprocess.run([str(java), '-version'], capture_output=True, text=True,
                            encoding='utf-8', errors='replace')
    output = result.stdout + result.stderr
    version = re.search(r'version\s+"(\d+)', output)
    require(result.returncode == 0 and version and int(version.group(1)) >= 25,
            'Java 25 or newer is required to launch the build; configure the Java 25 toolchain.\n' + output)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mod', type=Path, help='Use an existing SereneSeasons source directory; never fetch or modify it')
    parser.add_argument('--dry-run', action='store_true', help='Show the plan without fetching, writing or building')
    parser.add_argument('--offline', action='store_true', help='Require cached/local sources and use Gradle offline mode')
    args = parser.parse_args(argv)
    for name in ('build.gradle.kts', 'tools/finish_seasons.py', 'content-pack/ce_seasons/pack.yml',
                 'gradle/wrapper/gradle-wrapper.jar'):
        require((ROOT / name).is_file(), f'Missing project file: {ROOT / name}')
    check_tools(needs_git=args.mod is None)
    repository, revision = source_lock() if args.mod is None else (None, None)
    mod = args.mod.resolve() if args.mod else ROOT / '.cache/sereneseasons' / revision
    print(f'Project: {ROOT}\nSource: {mod}', flush=True)
    print('Pipeline: source -> 24 original PNGs/models -> seasonal atmosphere -> tests -> JAR + CE content ZIP', flush=True)
    if args.dry_run:
        if mod.exists():
            if args.mod:
                validate_assets(mod)
            else:
                validate_checkout(mod, repository, revision)
            run([sys.executable, '-X', 'utf8', str(ROOT / 'tools/finish_seasons.py'),
                 '--project', str(ROOT), '--mod', str(mod), '--dry-run'])
        else:
            require(args.mod is None, f'Local SereneSeasons directory does not exist: {mod}')
            require(not args.offline, f'Offline source cache is missing: {mod}')
            print(f'Would fetch pinned source: {repository} @ {revision}', flush=True)
            print('Texture validation requires the first download; not performed in dry-run.', flush=True)
        print('Dry-run complete: no files written, no download, no tests or build.', flush=True)
        return
    if args.mod:
        validate_assets(mod)
    else:
        mod = ensure_source(repository, revision, ROOT / '.cache/sereneseasons', args.offline)
    command = [sys.executable, '-X', 'utf8', str(ROOT / 'tools/finish_seasons.py'),
               '--project', str(ROOT), '--mod', str(mod)]
    # Validate every required asset and biome before any build-side source mutation.
    run([*command, '--dry-run'])
    run([sys.executable, '-X', 'utf8', '-m', 'unittest', 'discover', '-s', 'src/test/python', '-v'])
    if args.offline:
        command.append('--offline')
    run(command)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('\nBuild interrupted; existing local sources were not reset.', file=sys.stderr)
        sys.exit(130)
    except (RuntimeError, OSError, ValueError) as error:
        print(f'\nERROR: {error}', file=sys.stderr)
        sys.exit(1)
