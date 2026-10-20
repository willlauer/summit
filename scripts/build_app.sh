#!/bin/sh
# Build ~/Applications/Summit.app (a launcher for this checkout). Pass --icon to re-render the icon first.
set -e
cd "$(dirname "$0")/.."
[ "$1" = "--icon" ] && uv run --with pillow scripts/make_icon.py
uv sync
uv run summit bundle
