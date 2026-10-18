#!/bin/sh
# Vendor the per-platform binaries the desktop bundle ships:
#   cloudflared (Apache-2.0) · BtbN ffmpeg+ffprobe builds (GPL, run as a
#   separate process) · Temurin JRE (GPLv2+CE). Licenses ship in the app via
#   desktop/THIRD-PARTY.org.
#
# Usage: scripts/fetch-vendor.sh [linux|windows|all]
# Pin with env: CLOUDFLARED_VERSION=2026.10.0 JRE_VERSION=25
set -eu
cd "$(dirname "$0")/.."

if [ -n "${CLOUDFLARED_VERSION:-}" ]; then
  CF="https://github.com/cloudflare/cloudflared/releases/download/$CLOUDFLARED_VERSION"
else
  CF="https://github.com/cloudflare/cloudflared/releases/latest/download"
fi
JRE=${JRE_VERSION:-25}
WHICH=${1:-all}

fetch() { # url dest
  echo "  fetching $1"
  curl -fsSL --retry 3 -o "$2" "$1"
}

vendor_linux() {
  mkdir -p vendor/linux
  fetch "$CF/cloudflared-linux-amd64" vendor/linux/cloudflared
  chmod +x vendor/linux/cloudflared
  fetch "https://github.com/BtbN/FFmpeg-Builds/releases/latest/download/ffmpeg-master-latest-linux64-gpl.tar.xz" /tmp/ff.tar.xz
  rm -rf /tmp/ffx && mkdir -p /tmp/ffx && tar -xJf /tmp/ff.tar.xz -C /tmp/ffx
  FF=$(ls -d /tmp/ffx/ffmpeg-*-linux64-gpl | head -1)
  cp "$FF/bin/ffmpeg" "$FF/bin/ffprobe" vendor/linux/
  chmod +x vendor/linux/ffmpeg vendor/linux/ffprobe
  fetch "https://api.adoptium.net/v3/binary/latest/$JRE/ga/linux/x64/jre/hotspot/normal/eclipse" /tmp/jre.tgz
  rm -rf vendor/linux/jre && mkdir -p vendor/linux/jre
  tar -xzf /tmp/jre.tgz -C vendor/linux/jre --strip-components=1
}

vendor_windows() {
  mkdir -p vendor/windows
  fetch "$CF/cloudflared-windows-amd64.exe" vendor/windows/cloudflared.exe
  fetch "https://github.com/BtbN/FFmpeg-Builds/releases/latest/download/ffmpeg-master-latest-win64-gpl.zip" /tmp/ffwin.zip
  unzip -j -q -o /tmp/ffwin.zip '*/bin/ffmpeg.exe' '*/bin/ffprobe.exe' -d vendor/windows
  fetch "https://api.adoptium.net/v3/binary/latest/$JRE/ga/windows/x64/jre/hotspot/normal/eclipse" /tmp/jre.zip
  rm -rf /tmp/jrex vendor/windows/jre && mkdir -p /tmp/jrex vendor/windows/jre
  unzip -q -o /tmp/jre.zip -d /tmp/jrex
  mv "$(ls -d /tmp/jrex/jdk-*/ | head -1)"* vendor/windows/jre/
}

case "$WHICH" in
  linux)   vendor_linux ;;
  windows) vendor_windows ;;
  all)     vendor_linux; vendor_windows ;;
  *) echo "usage: $0 [linux|windows|all]" >&2; exit 2 ;;
esac

# record what we got — release reproducibility breadcrumb
{
  echo "built: $(date -u +%FT%TZ)"
  echo "cloudflared: ${CLOUDFLARED_VERSION:-latest}"
  echo "jre: $JRE (temurin)"
  echo "ffmpeg: linux=BtbN-linux64-gpl windows=BtbN-win64-gpl (rolling)"
  command -v sha256sum >/dev/null && find vendor -maxdepth 2 -type f \
    \( -name 'cloudflared*' -o -name 'ffmpeg*' -o -name 'ffprobe*' \) \
    -exec sha256sum {} \; | sort
} > vendor/MANIFEST
echo "vendored -> vendor/ ($(du -sh vendor | cut -f1))"
