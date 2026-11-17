#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${HOST_PORT:-8765}"

# Open the dashboard in the default browser once the server is listening
(
  sleep 1.2
  open "http://localhost:${PORT}/"
) &

exec "$ROOT_DIR/server/.venv/bin/python" "$ROOT_DIR/server/host_server.py"
