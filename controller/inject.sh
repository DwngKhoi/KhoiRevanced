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
STATUS_QUIET=0

APP_DATA="/data/user/0/$PACKAGE"
RUNTIME_DIR="$APP_DATA/code_cache/khoirevanced"
RUN_DIR="$RUNTIME_DIR/run"
CACHE_DIR="$RUNTIME_DIR/cache"

prepare_payload() {
    # adb push does not preserve the executable bit. Validate the source file
    # first; it becomes executable after copying into the app runtime dir.
    [ -f "$PAYLOAD_DIR/khoirevanced-injector" ] || die "missing arm64 injector payload"
    [ -r "$PAYLOAD_DIR/libkhoirevanced_agent.so" ] || die "missing native agent payload"
    [ -r "$PAYLOAD_DIR/libpine.so" ] || die "missing Pine hook-engine payload"
    [ -r "$PAYLOAD_DIR/nexalloy.dexpack" ] || die "missing NexAlloy compatibility payload"
    [ -r "$PAYLOAD_DIR/libdexkit.so" ] || die "missing DexKit payload"
    [ -r "$PAYLOAD_DIR/classes.dex" ] || die "missing DEX payload"
    [ -d "$APP_DATA" ] || die "$PACKAGE is not installed for user 0"
    uid=$(stat -c '%u' "$APP_DATA")
    mkdir -p "$RUN_DIR" "$CACHE_DIR"
    cp -f "$PAYLOAD_DIR/khoirevanced-injector" "$RUNTIME_DIR/"
    cp -f "$PAYLOAD_DIR/libkhoirevanced_agent.so" "$RUNTIME_DIR/"
    cp -f "$PAYLOAD_DIR/libpine.so" "$RUNTIME_DIR/"
    # DexClassLoader uses the APK/ZIP suffix to select the multidex APK path.
    # Keep the build artifact named .dexpack, but expose it to ART as a real
    # APK so classes.dex through classes19.dex are all discovered.
    cp -f "$PAYLOAD_DIR/nexalloy.dexpack" "$RUNTIME_DIR/nexalloy.apk"
    cp -f "$PAYLOAD_DIR/libdexkit.so" "$RUNTIME_DIR/"
    cp -f "$PAYLOAD_DIR/classes.dex" "$RUNTIME_DIR/"
    # ART rejects a DEX owned by the app when it remains writable. Keep runtime
    # payload immutable and root-owned; only cache/run are app-owned.
    chown root:root "$RUNTIME_DIR/khoirevanced-injector" \
        "$RUNTIME_DIR/libkhoirevanced_agent.so" "$RUNTIME_DIR/classes.dex"
    chown root:root "$RUNTIME_DIR/libpine.so"
    chown root:root "$RUNTIME_DIR/nexalloy.apk" "$RUNTIME_DIR/libdexkit.so"
    chmod 0755 "$RUNTIME_DIR/khoirevanced-injector"
    chmod 0444 "$RUNTIME_DIR/libkhoirevanced_agent.so" "$RUNTIME_DIR/libpine.so" \
        "$RUNTIME_DIR/libdexkit.so" "$RUNTIME_DIR/nexalloy.apk" "$RUNTIME_DIR/classes.dex"
    chown "$uid:$uid" "$RUNTIME_DIR" "$RUN_DIR" "$CACHE_DIR"
    chmod 0700 "$RUNTIME_DIR" "$RUN_DIR" "$CACHE_DIR"
}

main_pid() {
    # Apps such as YouTube create multiple package-owned processes.  Never
    # inject a renderer/service process by accident: select the process whose
    # cmdline is the package's main process.
    for candidate in $(pidof "$PACKAGE" 2>/dev/null || true); do
        process=$(tr '\000' ' ' < "/proc/$candidate/cmdline" 2>/dev/null || true)
        case "$process" in
            "$PACKAGE"|"$PACKAGE "*) printf '%s' "$candidate"; return 0 ;;
        esac
    done
    return 1
}
wait_for_pid() {
    # Poll fast enough to attach before the launcher Activity inflates its
    # player hierarchy. A one-second poll lets most of YouTube finish startup,
    # which is too late for hooks designed for zygote-time installation.
    attempt=0
    while [ "$attempt" -lt 400 ]; do
        pid=$(main_pid || true)
        [ -n "$pid" ] && { printf '%s' "$pid"; return; }
        attempt=$((attempt + 1))
        sleep 0.05
    done
    die "timed out waiting for $PACKAGE"
}

write_config() {
    pid=$1
    {
        echo "dex=$RUNTIME_DIR/classes.dex"
        echo "cache=$CACHE_DIR"
        echo "agent=$RUNTIME_DIR/libkhoirevanced_agent.so"
        echo "module=$RUNTIME_DIR/nexalloy.apk"
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
    am force-stop "$PACKAGE"
    # Never reuse an ART/DexClassLoader cache from an older embedded payload.
    # This also removes stale hook status and makes Manager launches identical
    # to the clean ADB deployment path.
    rm -rf "$RUNTIME_DIR"
    prepare_payload
    # Start the package's resolved launcher Activity directly.  `monkey -p`
    # is intended for test-event streams and can make OEM launchers briefly
    # recalculate orientation while the manager is in the foreground.
    activity=$(cmd package resolve-activity --brief "$PACKAGE" 2>/dev/null | tail -n 1)
    [ -n "$activity" ] || die "could not resolve launcher activity for $PACKAGE"
    # Do not wait for ActivityManager's command client to return before looking
    # for the zygote child. The agent itself waits for Application creation.
    am start -n "$activity" >/dev/null 2>&1 &
    pid=$(wait_for_pid)
    write_config "$pid"
    log "injecting launched package=$PACKAGE pid=$pid"
    "$RUNTIME_DIR/khoirevanced-injector" "$pid" "$RUNTIME_DIR/libkhoirevanced_agent.so"
    # The injector reports only that dlopen succeeded.  Wait for its mapped
    # agent and emit the canonical status line the Manager uses as success.
    attempt=0
    STATUS_QUIET=1
    while [ "$attempt" -lt 15 ]; do
        sleep 1
        if status; then
            STATUS_QUIET=0
            status
            return 0
        fi
        if [ -r "$CACHE_DIR/agent-status.txt" ] &&
            grep -q '^state=failed$' "$CACHE_DIR/agent-status.txt"; then
            break
        fi
        attempt=$((attempt + 1))
    done
    STATUS_QUIET=0
    status || true
    diagnose_failure
    die "$PACKAGE agent did not become ready"
}

status() {
    pid=$(main_pid || true)
    [ -n "$pid" ] || {
        [ "$STATUS_QUIET" = 1 ] || log "$PACKAGE is not running"
        return 1
    }
    grep -q 'libkhoirevanced_agent.so' "/proc/$pid/maps" 2>/dev/null && {
        [ "$STATUS_QUIET" = 1 ] || log "$PACKAGE pid=$pid: agent loaded"
        if [ ! -r "$CACHE_DIR/agent-status.txt" ]; then
            [ "$STATUS_QUIET" = 1 ] || log "$PACKAGE pid=$pid: agent is still starting"
            return 1
        fi
        [ "$STATUS_QUIET" = 1 ] || cat "$CACHE_DIR/agent-status.txt"
        grep -q "^pid=$pid$" "$CACHE_DIR/agent-status.txt" &&
            grep -q '^state=ready$' "$CACHE_DIR/agent-status.txt" &&
            grep -q 'module=nexalloy-loaded' "$CACHE_DIR/agent-status.txt" && return 0
        [ "$STATUS_QUIET" = 1 ] || log "$PACKAGE pid=$pid: NexAlloy runtime is not ready"
        return 1
    }
    [ "$STATUS_QUIET" = 1 ] || log "$PACKAGE pid=$pid: agent not loaded"
    return 1
}

diagnose_failure() {
    log "$PACKAGE launch failed; collecting runtime diagnostics"
    [ -r "$CACHE_DIR/agent-stage.txt" ] && cat "$CACHE_DIR/agent-stage.txt"
    [ -r "$CACHE_DIR/agent-status.txt" ] && cat "$CACHE_DIR/agent-status.txt"
    if command -v logcat >/dev/null 2>&1; then
        crash_log="$CACHE_DIR/crash.log"
        logcat -d -b crash -t 240 2>/dev/null |
            grep -F "$PACKAGE" > "$crash_log" 2>/dev/null || true
        if [ -s "$crash_log" ]; then
            log "--- crash buffer ($PACKAGE) ---"
            cat "$crash_log"
        fi
    fi
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
