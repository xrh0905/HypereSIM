#!/system/bin/sh
# Shared helpers for HypereSIM. Sourced by the installer and the Action button.
# Read-only: nothing here writes system state.

MODDIR=${MODDIR:-${0%/*}}
LPA_PKG=${LPA_PKG:-com.miui.euicc}
LPA_APK=${LPA_APK:-/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk}
GOOGLE_PKG=${GOOGLE_PKG:-com.google.android.euicc}
GOOGLE_APK=${GOOGLE_APK:-/product/priv-app/EuiccGoogle/EuiccGoogle.apk}

pkg_path() {
  pm path "$1" 2>/dev/null | sed -n 's/^package://p' | head -n 1
}

pkg_flags() {
  # Only pkgFlags carries the SYSTEM/UPDATED_SYSTEM_APP flags; the earlier
  # flags=0x0 line is the install-state bitmask and never contains them.
  dumpsys package "$1" 2>/dev/null \
    | grep -m 1 -E '^[[:space:]]*pkgFlags=' \
    | sed 's/^[[:space:]]*//'
}

pkg_version() {
  dumpsys package "$1" 2>/dev/null \
    | sed -n 's/^[[:space:]]*versionName=//p' \
    | head -n 1
}

pkg_is_system() {
  path=$(pkg_path "$1")
  case "$path" in
    /product/priv-app/*) ;;
    *) return 1 ;;
  esac
  pkg_flags "$1" | grep -Eq '=\[[^]]*(^|[[:space:]])SYSTEM([[:space:]]|]|$)'
}

pkg_is_updated_system() {
  pkg_flags "$1" | grep -Eq '=\[[^]]*(^|[[:space:]])UPDATED_SYSTEM_APP([[:space:]]|]|$)'
}

euicc_features() {
  pm list features 2>/dev/null \
    | sed -n 's/^feature://p' \
    | grep -E '^android\.hardware\.telephony\.euicc(\.mep)?$'
}
