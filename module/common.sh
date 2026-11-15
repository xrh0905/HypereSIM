#!/system/bin/sh
# Shared helpers for HypereSIM. Sourced by the installer and the Action button.
# Read-only: nothing here writes system state.

MODDIR=${MODDIR:-${0%/*}}
LPA_PKG=${LPA_PKG:-com.miui.euicc}
LPA_APK=${LPA_APK:-/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk}
LPA_PERM_XML=${LPA_PERM_XML:-/product/etc/permissions/privapp-permissions-euicc.xml}

lpa_path() {
  pm path "$LPA_PKG" 2>/dev/null | sed -n 's/^package://p' | head -n 1
}

lpa_flags() {
  # Only pkgFlags carries the SYSTEM flag; the earlier flags=0x0 line is the
  # install-state bitmask and never contains it.
  dumpsys package "$LPA_PKG" 2>/dev/null \
    | grep -m 1 -E '^[[:space:]]*pkgFlags=' \
    | sed 's/^[[:space:]]*//'
}

lpa_version() {
  dumpsys package "$LPA_PKG" 2>/dev/null \
    | sed -n 's/^[[:space:]]*versionName=//p' \
    | head -n 1
}

lpa_is_system() {
  path=$(lpa_path)
  case "$path" in
    /product/priv-app/*) ;;
    *) return 1 ;;
  esac
  lpa_flags | grep -Eq '=\[[^]]*(^|[[:space:]])SYSTEM([[:space:]]|]|$)'
}

lpa_is_updated_system() {
  lpa_flags | grep -Eq '=\[[^]]*(^|[[:space:]])UPDATED_SYSTEM_APP([[:space:]]|]|$)'
}

euicc_features() {
  pm list features 2>/dev/null \
    | sed -n 's/^feature://p' \
    | grep -E '^android\.hardware\.telephony\.euicc(\.mep)?$'
}
