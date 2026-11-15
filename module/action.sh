#!/system/bin/sh
# Read-only diagnostics for the manager's Action button. No toggles.

MODDIR=${0%/*}
. "$MODDIR/common.sh"

report_pkg() {
  prefix=$1
  pkg=$2
  path=$(pkg_path "$pkg")
  version=$(pkg_version "$pkg")
  flags=$(pkg_flags "$pkg")
  system_ok=false
  pkg_is_system "$pkg" && system_ok=true
  echo "${prefix}_pkg=$pkg"
  echo "${prefix}_path=${path:-none}"
  echo "${prefix}_version=${version:-none}"
  echo "${prefix}_flags=${flags:-none}"
  echo "${prefix}_system=$system_ok"
}

features=$(euicc_features)
[ -n "$features" ] || features="(none)"

VERSION=$(sed -n 's/^version=//p' "$MODDIR/module.prop" | head -n 1)
echo "HypereSIM ${VERSION:-unknown}"
echo "lpa_apk=$LPA_APK"
report_pkg lpa "$LPA_PKG"
echo "google_apk=$GOOGLE_APK"
report_pkg google "$GOOGLE_PKG"
echo "euicc_features:"
echo "$features" | sed 's/^/  /'
echo "recent euicc logcat:"
logcat -d 2>/dev/null | grep -E 'HypereSIM|Euicc-' | tail -n 40
