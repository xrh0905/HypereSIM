#!/system/bin/sh
# Read-only diagnostics for the manager's Action button. No toggles.

MODDIR=${0%/*}
. "$MODDIR/common.sh"

path=$(lpa_path)
version=$(lpa_version)
flags=$(lpa_flags)
system_ok=false
lpa_is_system && system_ok=true
features=$(euicc_features)
[ -n "$features" ] || features="(none)"

echo "HypereSIM v0.1.0"
echo "lpa_apk=$LPA_APK"
echo "lpa_path=${path:-none}"
echo "lpa_version=${version:-none}"
echo "lpa_flags=${flags:-none}"
echo "lpa_system=$system_ok"
echo "euicc_features:"
echo "$features" | sed 's/^/  /'
echo "recent euicc logcat:"
logcat -d 2>/dev/null | grep -E 'HypereSIM|Euicc-' | tail -n 40
