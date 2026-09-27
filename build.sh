#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -P "$(dirname "$0")" && pwd)

if command -v python3 >/dev/null 2>&1 &&
        python3 -c 'import sys; sys.exit(sys.version_info < (3, 10))' >/dev/null 2>&1; then
    python_command=python3
elif command -v python >/dev/null 2>&1 &&
        python -c 'import sys; sys.exit(sys.version_info < (3, 10))' >/dev/null 2>&1; then
    python_command=python
else
    printf '%s\n' 'ERROR: Python 3.10 or newer is required. Install Python and add it to PATH.' >&2
    exit 127
fi

exec "$python_command" -X utf8 "$script_dir/build.py" "$@"
