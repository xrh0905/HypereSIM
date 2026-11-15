#!/system/bin/sh
# Read-only boot diagnostics. Does not write Settings.Secure, reset properties,
# or change the mounted payload.

MODDIR=${0%/*}
. "$MODDIR/common.sh"

wait_boot_completed || exit 0

mkdir -p "$STATE_DIR" 2>/dev/null || true
chmod 700 "$STATE_DIR" 2>/dev/null || true

path=$(lpa_path)
version=$(lpa_version)
flags=$(lpa_flags)
system_ok=false
lpa_is_system && system_ok=true
features=$(euicc_features)
[ -n "$features" ] || features=none
apk_present=false
[ -f "$LPA_APK" ] && apk_present=true
perm_present=false
[ -f "$LPA_PERM_XML" ] && perm_present=true

if [ "$system_ok" = true ]; then
  message="LPA overlay OK: $path (version $version), SYSTEM flag present."
else
  message="LPA overlay NOT active: path=${path:-none} flags=${flags:-none}. Enable a KernelSU metamodule (Hybrid Mount or meta-overlayfs) and reboot."
fi
logmsg "$message"

{
  echo "time=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)"
  echo "lpa_apk_present=$apk_present"
  echo "lpa_perm_present=$perm_present"
  echo "lpa_path=$path"
  echo "lpa_version=$version"
  echo "lpa_flags=$flags"
  echo "lpa_system=$system_ok"
  echo "euicc_features=$features"
  echo "gsm_sim_state=$(getprop gsm.sim.state)"
  echo "message=$message"
} > "$STATE_DIR/last.log" 2>&1
