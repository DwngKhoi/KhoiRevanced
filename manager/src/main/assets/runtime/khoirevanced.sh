#!/system/bin/sh
# KhoiRevanced root-only runtime for debuggable test applications.
set -eu

ACTION="${1:-}"
PROFILE_ID="${2:-}"
ROOT_DIR="${KHOIREVANCED_ROOT:-/data/local/tmp/khoirevanced}"
ASSET_ROOT="${KHOIREVANCED_ASSET_ROOT:-}"
LOG_DIR="$ROOT_DIR/logs"
LOG_FILE="$LOG_DIR/runtime.log"
PROFILE_DIR="${ASSET_ROOT}/profiles"

mkdir -p "$LOG_DIR"
chmod 700 "$ROOT_DIR" "$LOG_DIR"
log() { printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" | tee -a "$LOG_FILE"; }
die() { log "$1"; exit "${2:-1}"; }
require_root() { [ "$(id -u)" = "0" ] || die 'Root access is required.' 77; }
validate_profile_id() { case "$1" in ''|*[!a-z0-9-]*) die 'Invalid profile id.' 64;; esac; }
read_profile() {
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
require_root
case "$ACTION" in
  doctor)
    log 'KhoiRevanced standalone runtime'; log "uid=$(id -u)"; log "abi=$(getprop ro.product.cpu.abi 2>/dev/null || printf unknown)"; log "sdk=$(getprop ro.build.version.sdk 2>/dev/null || printf unknown)"; log "selinux=$(getenforce 2>/dev/null || printf unknown)";;
  install)
    [ -n "$ASSET_ROOT" ] && [ -r "$ASSET_ROOT/lib/arm64-v8a/libkhoirevanced_agent.so" ] || die 'Agent asset missing.' 66
    log 'Runtime and diagnostic JVMTI agent are installed.';;
  status) log "runtime_dir=$ROOT_DIR"; log 'Profiles require an owned, debuggable test app.';;
  logs) [ -r "$LOG_FILE" ] && cat "$LOG_FILE" || log 'No runtime log yet.';;
  stop) log 'No persistent injected process is managed by this runtime.';;
  launch)
    validate_profile_id "$PROFILE_ID"; read_profile
    [ "$(getprop ro.product.cpu.abi)" = "$TARGET_ABI" ] || die 'Unsupported device ABI.' 69
    sdk="$(getprop ro.build.version.sdk)"; [ "$sdk" -ge "$MIN_SDK" ] || die 'Unsupported Android SDK.' 69
    agent="$ASSET_ROOT/$AGENT_LIBRARY"; [ -r "$agent" ] || die 'Agent library is missing.' 66
    dumpsys package "$TARGET_PACKAGE" | grep -q 'DEBUGGABLE' || die 'Target is not debuggable; attach refused.' 77
    log "Launching debuggable profile $PROFILE_ID"
    am start-activity --attach-agent "$agent=khoirevanced" -n "$TARGET_ACTIVITY" || die 'Agent attach launch failed.' 70
    log 'Start request sent; inspect target logcat for KhoiRevancedAgent.';;
  *) die 'Usage: khoirevanced.sh {doctor|install|status|logs|stop|launch <profile>}' 64;;
esac