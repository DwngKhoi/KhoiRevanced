#!/system/bin/sh
# KhoiRevanced root-only runtime: allow-listed actions + bundled .sh scripts.
set -eu

ACTION="${1:-}"
ARG2="${2:-}"
ROOT_DIR="${KHOIREVANCED_ROOT:-/data/local/tmp/khoirevanced}"
ASSET_ROOT="${KHOIREVANCED_ASSET_ROOT:-}"
LOG_DIR="$ROOT_DIR/logs"
LOG_FILE="$LOG_DIR/runtime.log"
PROFILE_DIR="${ASSET_ROOT}/profiles"
SCRIPT_DIR="${ASSET_ROOT}/scripts"

mkdir -p "$LOG_DIR"
chmod 700 "$ROOT_DIR" "$LOG_DIR"
log() { printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" | tee -a "$LOG_FILE"; }
die() { log "$1"; exit "${2:-1}"; }
require_root() { [ "$(id -u)" = "0" ] || die 'Root access is required.' 77; }
validate_id() { case "$1" in ''|*[!a-z0-9-]*) die 'Invalid id.' 64;; esac; }
prop() { getprop "$1" 2>/dev/null || printf ''; }

read_profile() {
    PROFILE_ID="$ARG2"
    PROFILE_FILE="$PROFILE_DIR/$PROFILE_ID.properties"
    [ -n "$ASSET_ROOT" ] && [ -r "$PROFILE_FILE" ] || die 'Profile is not installed.' 66
    unset TARGET_PACKAGE TARGET_ACTIVITY TARGET_ABI MIN_SDK DEBUGGABLE_REQUIRED AGENT_LIBRARY
    while IFS='=' read -r key value; do
        case "$key" in
            id) [ "$value" = "$PROFILE_ID" ] || die 'Profile id mismatch.' 65;;
            package) TARGET_PACKAGE="$value";; activity) TARGET_ACTIVITY="$value";; abi) TARGET_ABI="$value";;
            min_sdk) MIN_SDK="$value";; debuggable_required) DEBUGGABLE_REQUIRED="$value";; agent_library) AGENT_LIBRARY="$value";;
            ''|'#'*) ;; *) die 'Unknown profile key.' 65;;
        esac
    done < "$PROFILE_FILE"
    [ "${TARGET_PACKAGE:-}" != "" ] && [ "${TARGET_ACTIVITY:-}" != "" ] && [ "${TARGET_ABI:-}" = "arm64-v8a" ] && [ "${DEBUGGABLE_REQUIRED:-}" = "true" ] && [ "${AGENT_LIBRARY:-}" = "lib/arm64-v8a/libkhoirevanced_agent.so" ] || die 'Invalid profile.' 65
}

doctor_report() {
    log 'KhoiRevanced standalone runtime (no LSPosed)'
    log "uid=$(id -u)"
    log "abi=$(prop ro.product.cpu.abi)"
    log "sdk=$(prop ro.build.version.sdk)"
    log "release=$(prop ro.build.version.release)"
    log "selinux=$(getenforce 2>/dev/null || printf unknown)"
    log "brand=$(prop ro.product.brand)"
    log "manufacturer=$(prop ro.product.manufacturer)"
    log "model=$(prop ro.product.model)"
    log "device=$(prop ro.product.device)"
    log "name=$(prop ro.product.name)"
    log "board=$(prop ro.product.board)"
    log "fingerprint=$(prop ro.build.fingerprint)"
    log "display_id=$(prop ro.build.display.id)"
    log "ota_version=$(prop ro.build.version.ota)"
    log "oxygen_ota=$(prop ro.oxygen.version)"
    log "oplus_rom=$(prop ro.oplus.version.ota)"
    log "coloros=$(prop ro.build.version.opporom)"
    model="$(prop ro.product.model)"
    device="$(prop ro.product.device)"
    display="$(prop ro.build.display.id)"
    ota="$(prop ro.build.version.ota)"
    case "$model$device$display$ota" in
      *Ace*6T*|*ace6t*|*PJX110*|*16.0.10.500*) log 'target_hint=matches OnePlus Ace 6T / OOS 16.0.10.500 family (soft check)';;
      *) log 'target_hint=generic arm64 device — configure scripts for your build if needed';;
    esac
}

run_script() {
    SCRIPT_ID="$ARG2"
    validate_id "$SCRIPT_ID"
    case "$SCRIPT_ID" in
      hello|device-info) ;;
      *) die 'Rejected unknown script id.' 64;;
    esac
    SCRIPT_FILE="$SCRIPT_DIR/$SCRIPT_ID.sh"
    [ -n "$ASSET_ROOT" ] && [ -r "$SCRIPT_FILE" ] || die 'Script is not installed.' 66
    log "Running allow-listed script $SCRIPT_ID"
    sh "$SCRIPT_FILE"
}

require_root
case "$ACTION" in
  doctor) doctor_report;;
  install)
    [ -n "$ASSET_ROOT" ] && [ -r "$ASSET_ROOT/lib/arm64-v8a/libkhoirevanced_agent.so" ] || die 'Agent asset missing.' 66
    [ -d "$SCRIPT_DIR" ] || die 'Scripts directory missing.' 66
    log 'Runtime, diagnostic agent, and allow-listed scripts are installed.'
    log "asset_root=$ASSET_ROOT"
    for f in "$SCRIPT_DIR"/*.sh; do
      [ -r "$f" ] || continue
      log "script=$(basename "$f" .sh)"
    done
    ;;
  status)
    log "runtime_dir=$ROOT_DIR"
    log "asset_root=${ASSET_ROOT:-unset}"
    log 'Allow-listed actions: doctor install status logs stop launch script'
    log 'Allow-listed scripts: hello device-info'
    log 'Profiles require an owned, debuggable test app.'
    ;;
  logs) [ -r "$LOG_FILE" ] && cat "$LOG_FILE" || log 'No runtime log yet.';;
  stop) log 'No persistent injected process is managed by this runtime.';;
  launch)
    validate_id "$ARG2"
    read_profile
    [ "$(prop ro.product.cpu.abi)" = "$TARGET_ABI" ] || die 'Unsupported device ABI.' 69
    sdk="$(prop ro.build.version.sdk)"; [ "$sdk" -ge "$MIN_SDK" ] || die 'Unsupported Android SDK.' 69
    agent="$ASSET_ROOT/$AGENT_LIBRARY"; [ -r "$agent" ] || die 'Agent library is missing.' 66
    dumpsys package "$TARGET_PACKAGE" | grep -q 'DEBUGGABLE' || die 'Target is not debuggable; attach refused.' 77
    log "Launching debuggable profile $ARG2"
    am start-activity --attach-agent "$agent=khoirevanced" -n "$TARGET_ACTIVITY" || die 'Agent attach launch failed.' 70
    log 'Start request sent; inspect target logcat for KhoiRevancedAgent.'
    ;;
  script) run_script;;
  *) die 'Usage: khoirevanced.sh {doctor|install|status|logs|stop|launch <profile>|script <id>}' 64;;
esac
