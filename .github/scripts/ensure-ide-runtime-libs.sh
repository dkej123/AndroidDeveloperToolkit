#!/usr/bin/env bash
# The IDE that :intellij:buildSearchableOptions and the platform/visual tests start runs on its
# bundled JBR, which links the host's freetype/fontconfig and X11 client libraries even when
# headless. GitHub-hosted runners have them; a slim self-hosted image may not
# (.github/runner/Dockerfile installs them). Installs whatever is missing, so CI also works on a
# runner whose image has not been rebuilt yet.
set -euo pipefail

packages=(libfreetype6 libfontconfig1 fontconfig fonts-dejavu-core
          libx11-6 libxext6 libxrender1 libxtst6 libxi6 libxrandr2)

if ! command -v dpkg-query >/dev/null 2>&1; then
  echo "Not a Debian-based runner; assuming the IDE runtime libraries are present."
  exit 0
fi

missing=()
for package in "${packages[@]}"; do
  if ! dpkg-query -W -f='${Status}' "$package" 2>/dev/null | grep -q 'install ok installed'; then
    missing+=("$package")
  fi
done

if [ ${#missing[@]} -eq 0 ]; then
  echo "IDE runtime libraries present."
  exit 0
fi

echo "Installing missing IDE runtime libraries: ${missing[*]}"
if [ "$(id -u)" -eq 0 ]; then
  sudo=()
elif sudo -n true 2>/dev/null; then
  sudo=(sudo -n)
else
  echo "::error::Missing ${missing[*]} and no passwordless sudo. Rebuild the runner from .github/runner/Dockerfile."
  exit 1
fi
export DEBIAN_FRONTEND=noninteractive
"${sudo[@]}" apt-get update -qq
"${sudo[@]}" apt-get install -y -qq --no-install-recommends "${missing[@]}"
