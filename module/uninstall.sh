#!/system/bin/sh
# Remove only HypereSIM's own diagnostics.
# Settings.Secure, /vendor declarations, the LSPosed module, and every eUICC
# profile are intentionally left untouched.

STATE_DIR=${STATE_DIR:-/data/adb/hyperesim}
rm -rf "$STATE_DIR"
