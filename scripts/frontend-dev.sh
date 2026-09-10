#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source .env
set +a
exec npm --prefix frontend run dev -- --hostname 127.0.0.1
