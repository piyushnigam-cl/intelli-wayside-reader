#!/bin/bash
# Installs the wayside reader to /opt/intelli so the service never launches out of target/.
# Copied from the tunnel's install.sh; read that one for the history behind each step.
#
#   sudo deploy/install.sh
#
# It installs the unit but does NOT enable it: this app and the tunnel cannot share a board, and
# enabling it on a tunnel unit would take the tunnel down at the next boot.
set -euo pipefail

APP=intelli-wayside-reader
PREFIX=/opt/intelli
SERVICE_USER=intelli-sbc
JAR_SRC="$(cd "$(dirname "$0")/.." && pwd)/target/${APP}-1.0.0-SNAPSHOT.jar"
JAR_DST="${PREFIX}/${APP}/${APP}.jar"
# The .so must match the vendor jar core resolves (v260827: libs/linux/aarch64).
SDK_LIB="$(cd "$(dirname "$0")/../../.." && pwd)/API-java-v260827/libs/linux/aarch64/libModuleAPIJni.so"

if [ "$(id -u)" -ne 0 ]; then
    echo "Run me with sudo: writes under ${PREFIX}" >&2
    exit 1
fi

if [ ! -f "$JAR_SRC" ]; then
    echo "No jar at ${JAR_SRC}. Build it first:  mvn -o package" >&2
    exit 1
fi

install -d -m 755 "${PREFIX}/${APP}" "${PREFIX}/lib"
# Owned by the service user, or logback cannot open the file and logs go to journald only.
install -d -m 755 -o "$SERVICE_USER" "${PREFIX}/logs"
install -d -m 755 -o "$SERVICE_USER" /var/lib/intelli/wayside

if [ -f "$SDK_LIB" ]; then
    install -m 755 "$SDK_LIB" "${PREFIX}/lib/libModuleAPIJni.so"
elif [ ! -f "${PREFIX}/lib/libModuleAPIJni.so" ]; then
    echo "No libModuleAPIJni.so at ${SDK_LIB} and none installed at ${PREFIX}/lib." >&2
    exit 1
fi

install -m 644 "$JAR_SRC" "${JAR_DST}.new"
mv -f "${JAR_DST}.new" "$JAR_DST"

UNIT_SRC="$(cd "$(dirname "$0")" && pwd)/${APP}.service"
UNIT_DST="/etc/systemd/system/${APP}.service"
if [ -f "$UNIT_DST" ] && ! cmp -s "$UNIT_SRC" "$UNIT_DST"; then
    cp -a "$UNIT_DST" "${UNIT_DST}.bak.$(date +%Y%m%d-%H%M%S)"
fi
install -m 644 "$UNIT_SRC" "$UNIT_DST"
systemctl daemon-reload

if [ ! -f "/etc/intelli/${APP}/application.yml" ]; then
    echo "NOTE: no site config at /etc/intelli/${APP}/application.yml. The app refuses to start"
    echo "      without API keys. Start from deploy/site-config.example.yml."
fi

echo "Installed:"
ls -l "$JAR_DST" "${PREFIX}/lib/libModuleAPIJni.so" "$UNIT_DST"
echo
echo "Not enabled. On a wayside board:  sudo systemctl enable --now ${APP}"
