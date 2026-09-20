#!/system/bin/sh
# Allow-listed smoke test. No extra args.
set -eu
printf 'hello from KhoiRevanced bundled script\n'
printf 'uid=%s\n' "$(id -u)"
printf 'date=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')"
