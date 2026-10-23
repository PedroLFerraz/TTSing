#!/bin/sh
# Removes what install-linux.sh put in place. The library index, reading positions and
# downloaded voices in ~/.local/share/ttsing stay; delete that folder too to forget them.
set -e
rm -rf "$HOME/.local/lib/ttsing"
rm -f "$HOME/.local/bin/ttsing" \
    "$HOME/.local/share/applications/ttsing.desktop" \
    "$HOME/.local/share/icons/hicolor/scalable/apps/ttsing.svg"
command -v update-desktop-database >/dev/null && update-desktop-database "$HOME/.local/share/applications" || true
echo "TTSing is uninstalled. Your library data is still in ~/.local/share/ttsing."
