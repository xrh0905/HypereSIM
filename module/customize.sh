#!/system/bin/sh
# Sourced by the KernelSU/Magisk module installer after extraction.

[ "$BOOTMODE" = true ] || abort "Install from KernelSU, SukiSU, APatch, or Magisk while Android is running."

MODDIR=$MODPATH
. "$MODPATH/common.sh"

SDK=$(getprop ro.build.version.sdk)
if [ -n "$SDK" ] && [ "$SDK" -lt 35 ] 2>/dev/null; then
  ui_print "! HypereSIM targets HyperOS 4 (Android 15+, SDK 35+); detected SDK $SDK."
fi

for old_id in XiaomiEsimLPA XiaomiEsimLPAHyperOS4; do
  if [ -d "/data/adb/modules/$old_id" ]; then
    abort "Conflicting module $old_id is installed. Remove it, reboot, then flash HypereSIM."
  fi
done

# The payload and permission files are always installed. There is no version
# gate or conditional skip: the module always shadows the product/vendor files.
if pm list packages -3 "$LPA_PKG" 2>/dev/null | grep -qx "package:$LPA_PKG"; then
  if ! lpa_is_updated_system; then
    ui_print "- Removing unprivileged /data/app $LPA_PKG"
    pm uninstall "$LPA_PKG" >/dev/null 2>&1 || true
  fi
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/system" 0 0 0755 0644 u:object_r:system_file:s0
for script in common.sh customize.sh service.sh action.sh uninstall.sh; do
  set_perm "$MODPATH/$script" 0 0 0755
done
set_perm "$MODPATH/META-INF/com/google/android/update-binary" 0 0 0755

ui_print "- HypereSIM v0.1.0: privileged LPA payload, privapp allowlist, MEP unavailable-feature."
ui_print "- Behaviour hooks are provided by the separate LSPosed module; enable it before toggling eSIM."
ui_print "- Reboot to activate the /product and /vendor overlays."
