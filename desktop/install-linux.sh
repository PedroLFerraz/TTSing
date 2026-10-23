#!/bin/sh
# Builds TTSing for the desktop and installs it for the current user, no root needed:
#   the app (with its own Java runtime)  ~/.local/lib/ttsing
#   the command                          ~/.local/bin/ttsing
#   the launcher entry and icon          ~/.local/share/applications, ~/.local/share/icons
# Run it again to update. Your library, positions and voices live in ~/.local/share/ttsing
# and are left alone, here and by uninstall-linux.sh.
set -e
cd "$(dirname "$0")/.."

./gradlew :desktop:createDistributable

built=desktop/build/compose/binaries/main/app/ttsing
lib="$HOME/.local/lib/ttsing"
bin="$HOME/.local/bin"
apps="$HOME/.local/share/applications"
icons="$HOME/.local/share/icons/hicolor/scalable/apps"

rm -rf "$lib"
mkdir -p "$lib" "$bin" "$apps" "$icons"
cp -r "$built"/. "$lib"/
ln -sf "$lib/bin/ttsing" "$bin/ttsing"
cp docs/brand/icon.svg "$icons/ttsing.svg"

cat > "$apps/ttsing.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=TTSing
GenericName=Book reader
Comment=Reads your EPUBs and PDFs aloud, highlighting every word as it's spoken
Exec=$lib/bin/ttsing %f
Icon=ttsing
Terminal=false
Categories=Office;Viewer;Education;Literature;
MimeType=application/epub+zip;application/pdf;
StartupWMClass=ttsing
EOF

command -v update-desktop-database >/dev/null && update-desktop-database "$apps" || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q "$HOME/.local/share/icons/hicolor" 2>/dev/null || true

echo "Installed TTSing: run \`ttsing\` or open it from your launcher."
case ":$PATH:" in
    *":$bin:"*) ;;
    *) echo "Note: $bin is not on your PATH." ;;
esac
