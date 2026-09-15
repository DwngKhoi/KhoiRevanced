#!/system/bin/sh
# KhoiRevanced root-only runtime. This MVP performs no process injection.
set -eu

ACTION="${1:-}"
ROOT_DIR="${KHOIREVANCED_ROOT:-/data/local/tmp/khoirevanced}"
LOG_DIR="$ROOT_DIR/logs"
LOG_FILE="$LOG_DIR/runtime.log"

mkdir -p "$LOG_DIR"
chmod 700 "$ROOT_DIR" "$LOG_DIR"
log() { printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" | tee -a "$LOG_FILE"; }
require_root() { [ "$(id -u)" = "0" ] || { log 'Root access is required.'; exit 77; }; }

require_root
case "$ACTION" in
  doctor)
    log 'KhoiRevanced standalone runtime'
    log "uid=$(id -u)"
    log "abi=$(getprop ro.product.cpu.abi 2>/dev/null || printf unknown)"
    log "sdk=$(getprop ro.build.version.sdk 2>/dev/null || printf unknown)"
    log "selinux=$(getenforce 2>/dev/null || printf unknown)"
    ;;
  install)
    log 'Runtime directory is ready. No LSPosed/Xposed components are installed.'
    ;;
  status)
    log "runtime_dir=$ROOT_DIR"
    log 'MVP state: ready; no target profile or injection payload is enabled.'
    ;;
  stop)
    log 'No injected process exists in this MVP; cleanup completed.'
    ;;
  *)
    log 'Usage: khoirevanced.sh {doctor|install|status|stop}'
    exit 64
    ;;
esac