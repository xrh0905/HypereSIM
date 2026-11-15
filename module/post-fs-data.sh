#!/system/bin/sh
# MODE=off is implemented by skip_mount, so the /product and /vendor overlays
# are not applied on the next boot.

MODDIR=${0%/*}
. "$MODDIR/common.sh"
read_mode

if [ "$MODE" = on ]; then
  rm -f "$MODDIR/skip_mount"
else
  touch "$MODDIR/skip_mount"
fi
