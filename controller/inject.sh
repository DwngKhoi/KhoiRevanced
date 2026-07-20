#!/system/bin/sh
# KhoiRevanced runtime controller. Run as root on the Android device.
set -eu

TAG="KhoiRevanced"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PAYLOAD_DIR="$SCRIPT_DIR/payload/arm64-v8a"
PROFILE_DIR="$SCRIPT_DIR/profiles"

log() { printf '%s: %s\n' "$TAG" "$*" >&2; }
die() { log "$*"; exit 1; }
usage() { echo "Usage: su -c $0 <inject|launch|status|watch|clean> <profile>"; }

[ "$(id -u)" = 0 ] || die "run through su -c"
[ "$#" -eq 2 ] || { usage; exit 64; }
COMMAND=$1
PROFILE=$2
PROFILE_FILE="$PROFILE_DIR/$PROFILE.conf"
[ -r "$PROFILE_FILE" ] || die "unknown profile: $PROFILE"
. "$PROFILE_FILE"
: "${PACKAGE:?PROFILE must set PACKAGE}"
APPLICATION_TIMEOUT_MS=${APPLICATION_TIMEOUT_MS:-15000}

APP_DATA="/data/user/0/$PACKAGE"
RUNTIME_DIR="$APP_DATA/code_cache/khoirevanced"
RUN_DIR="$RUNTIME_DIR/run"
CACHE_DIR="$RUNTIME_DIR/cache"

prepare_payload() {
    # adb push does not preserve the executable bit. Validate the source file
    # first; it becomes executable after copying into the app runtime dir.
    [ -f "$PAYLOAD_DIR/khoirevanced-injector" ] || die "missing arm64 injector payload"
    [ -r "$PAYLOAD_DIR/libkhoirevanced_agent.so" ] || die "missing native agent payload"
    [ -r "$PAYLOAD_DIR/classes.dex" ] || die "missing DEX payload"
    [ -d "$APP_DATA" ] || die "$PACKAGE is not installed for user 0"
    uid=$(stat -c '%u' "$APP_DATA")
    mkdir -p "$RUN_DIR" "$CACHE_DIR"
    cp -f "$PAYLOAD_DIR/khoirevanced-injector" "$RUNTIME_DIR/"
    cp -f "$PAYLOAD_DIR/libkhoirevanced_agent.so" "$RUNTIME_DIR/"
    cp -f "$PAYLOAD_DIR/classes.dex" "$RUNTIME_DIR/"
    # ART rejects a DEX owned by the app when it remains writable. Keep runtime
    # payload immutable and root-owned; only cache/run are app-owned.
    chown root:root "$RUNTIME_DIR/khoirevanced-injector" \
        "$RUNTIME_DIR/libkhoirevanced_agent.so" "$RUNTIME_DIR/classes.dex"
    chmod 0755 "$RUNTIME_DIR/khoirevanced-injector"
    chmod 0444 "$RUNTIME_DIR/libkhoirevanced_agent.so" "$RUNTIME_DIR/classes.dex"
    chown "$uid:$uid" "$RUNTIME_DIR" "$RUN_DIR" "$CACHE_DIR"
    chmod 0700 "$RUNTIME_DIR" "$RUN_DIR" "$CACHE_DIR"
}

main_pid() { pidof "$PACKAGE" 2>/dev/null | awk '{print $1}'; }
wait_for_pid() {
    deadline=$(( $(date +%s) + 20 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        pid=$(main_pid || true)
        [ -n "$pid" ] && { printf '%s' "$pid"; return; }
        sleep 1
    done
    die "timed out waiting for $PACKAGE"
}

write_config() {
    pid=$1
    {
        echo "dex=$RUNTIME_DIR/classes.dex"
        echo "cache=$CACHE_DIR"
        echo "agent=$RUNTIME_DIR/libkhoirevanced_agent.so"
        echo "action=inject"
        echo "package=$PACKAGE"
        echo "profile=$PROFILE"
        echo "cache_dir=$CACHE_DIR"
        echo "application_timeout_ms=$APPLICATION_TIMEOUT_MS"
    } > "$RUN_DIR/$pid.conf"
    uid=$(stat -c '%u' "$APP_DATA")
    chown "$uid:$uid" "$RUN_DIR/$pid.conf"
    chmod 0600 "$RUN_DIR/$pid.conf"
}

inject() {
    prepare_payload
    pid=$(main_pid || true)
    [ -n "$pid" ] || die "$PACKAGE is not running; use launch"
    write_config "$pid"
    log "injecting profile=$PROFILE package=$PACKAGE pid=$pid"
    "$RUNTIME_DIR/khoirevanced-injector" "$pid" "$RUNTIME_DIR/libkhoirevanced_agent.so"
}

launch() {
    prepare_payload
    am force-stop "$PACKAGE"
    monkey -p "$PACKAGE" 1 >/dev/null
    pid=$(wait_for_pid)
    sleep 1
    write_config "$pid"
    log "injecting launched package=$PACKAGE pid=$pid"
    "$RUNTIME_DIR/khoirevanced-injector" "$pid" "$RUNTIME_DIR/libkhoirevanced_agent.so"
}

status() {
    pid=$(main_pid || true)
    [ -n "$pid" ] || { log "$PACKAGE is not running"; return 1; }
    grep -q 'libkhoirevanced_agent.so' "/proc/$pid/maps" 2>/dev/null &&
        {
            log "$PACKAGE pid=$pid: agent loaded"
            [ -r "$CACHE_DIR/agent-status.txt" ] && cat "$CACHE_DIR/agent-status.txt"
            return
        }
    log "$PACKAGE pid=$pid: agent not loaded"
    return 1
}

watch() {
    prepare_payload
    previous=""
    while :; do
        pid=$(main_pid || true)
        if [ -n "$pid" ] && [ "$pid" != "$previous" ]; then
            sleep 1; write_config "$pid"
            "$RUNTIME_DIR/khoirevanced-injector" "$pid" "$RUNTIME_DIR/libkhoirevanced_agent.so" ||
                log "inject failed for pid=$pid; waiting for next process"
            previous=$pid
        elif [ -z "$pid" ]; then previous=""; fi
        sleep 1
    done
}

clean() { rm -rf "$RUNTIME_DIR"; log "removed runtime files for $PACKAGE"; }
case "$COMMAND" in
    inject) inject ;; launch) launch ;; status) status ;; watch) watch ;; clean) clean ;;
    *) usage; exit 64 ;;
esac
