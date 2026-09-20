#!/system/bin/sh
# Allow-listed device fingerprint helper for OnePlus / OxygenOS targets.
set -eu
prop() { getprop "$1" 2>/dev/null || printf ''; }
printf '=== KhoiRevanced device-info ===\n'
printf 'brand=%s\n' "$(prop ro.product.brand)"
printf 'manufacturer=%s\n' "$(prop ro.product.manufacturer)"
printf 'model=%s\n' "$(prop ro.product.model)"
printf 'device=%s\n' "$(prop ro.product.device)"
printf 'name=%s\n' "$(prop ro.product.name)"
printf 'board=%s\n' "$(prop ro.product.board)"
printf 'abi=%s\n' "$(prop ro.product.cpu.abi)"
printf 'sdk=%s\n' "$(prop ro.build.version.sdk)"
printf 'release=%s\n' "$(prop ro.build.version.release)"
printf 'display_id=%s\n' "$(prop ro.build.display.id)"
printf 'fingerprint=%s\n' "$(prop ro.build.fingerprint)"
printf 'ota_version=%s\n' "$(prop ro.build.version.ota)"
printf 'oxygen_ota=%s\n' "$(prop ro.oxygen.version)"
printf 'oplus_rom=%s\n' "$(prop ro.oplus.version.ota)"
printf 'coloros=%s\n' "$(prop ro.build.version.opporom)"
printf 'selinux=%s\n' "$(getenforce 2>/dev/null || printf unknown)"
printf 'expected_dev_target=OnePlus Ace 6T / OxygenOS 16.0.10.500 / arm64-v8a\n'
