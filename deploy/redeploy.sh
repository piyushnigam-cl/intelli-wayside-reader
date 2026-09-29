#!/bin/bash
# One command: stop, pull, build, install, start.
#
#   deploy/redeploy.sh            wayside reader only
#   deploy/redeploy.sh --core     rebuild intelli-rfid-core first (needed if core changed)
#   deploy/redeploy.sh --no-pull  build and deploy what is already checked out
#
# RUN IT AS intelli-sbc, NOT UNDER sudo. It calls sudo itself for the three privileged steps.
# Running the whole thing as root would run Maven as root, which writes /root/.m2 and leaves
# target/ owned by root - and the next ordinary build then fails on permissions in a way that
# looks like a broken checkout.
set -euo pipefail

APP=intelli-wayside-reader
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CORE_DIR="$(cd "$APP_DIR/.." && pwd)/intelli-rfid-core"
PULL=1
CORE=0

for arg in "$@"; do
    case "$arg" in
        --core)    CORE=1 ;;
        --no-pull) PULL=0 ;;
        *) echo "Unknown option: $arg" >&2; exit 2 ;;
    esac
done

if [ "$(id -u)" -eq 0 ]; then
    echo "Do not run this under sudo - it runs Maven as root and leaves target/ root-owned." >&2
    echo "Run it as $(logname 2>/dev/null || echo intelli-sbc); it will sudo where it needs to." >&2
    exit 1
fi

say() { printf '\n=== %s\n' "$1"; }

# A tunnel board is not a wayside board. The unit declares Conflicts= with the tunnel, so starting
# this one would stop a working tunnel rather than fail.
if systemctl is-enabled --quiet intelli-rfid-tunnel 2>/dev/null; then
    echo "intelli-rfid-tunnel is enabled on this board: it is a tunnel reader, and starting" >&2
    echo "${APP} would stop the tunnel. Build and test with mvn instead, or disable the tunnel" >&2
    echo "first if this board really is becoming a wayside reader." >&2
    exit 1
fi

# --- 1. stop ---------------------------------------------------------------------------------
say "Stopping ${APP}"
sudo systemctl stop "$APP"

# --- 2. confirm nothing is still holding the module ------------------------------------------
# The serial port and the JNI library are single-owner. A JVM that outlived the stop will hold
# /dev/ttyAMA0, and the restarted service then fails to open the module - which reads as a
# hardware fault and is not one. pgrep -x java, never pkill -f: a pattern match on the app name
# also matches the shell running this script.
say "Checking no JVM is left running"
for _ in $(seq 1 10); do
    pgrep -x java >/dev/null || break
    sleep 1
done

if pgrep -x java >/dev/null; then
    echo "A java process is STILL running after systemctl stop:" >&2
    ps -o pid,etime,user,args -p "$(pgrep -x java | tr '\n' ',' | sed 's/,$//')" >&2
    echo >&2
    echo "Not killing it - it may be a probe or another app you are using. Deal with it, then" >&2
    echo "re-run. If it is an orphaned tunnel: kill <pid>, and kill -9 only if that fails." >&2
    exit 1
fi
echo "No JVM running."

# --- 3. pull ---------------------------------------------------------------------------------
if [ "$PULL" -eq 1 ]; then
    say "Pulling ${APP}"
    git -C "$APP_DIR" pull --ff-only
    if [ "$CORE" -eq 1 ]; then
        say "Pulling intelli-rfid-core"
        git -C "$CORE_DIR" pull --ff-only
    fi
fi

# --- 4. build --------------------------------------------------------------------------------
# clean install on core deliberately: incremental compile goes stale there, and the failure is an
# app that bundles the previous class while Maven reports success.
if [ "$CORE" -eq 1 ]; then
    say "Building intelli-rfid-core"
    (cd "$CORE_DIR" && mvn -o clean install -DskipTests=false)
fi

say "Building ${APP}"
(cd "$APP_DIR" && mvn -o package)

# --- 5. install ------------------------------------------------------------------------------
say "Installing to /opt/intelli"
sudo "$APP_DIR/deploy/install.sh"

# --- 6. start --------------------------------------------------------------------------------
say "Starting ${APP}"
sudo systemctl start "$APP"

# Give it a moment to either come up or die, so this script's exit code means something.
sleep 5
if ! systemctl is-active --quiet "$APP"; then
    echo >&2
    echo "${APP} did not stay up. Last 30 lines:" >&2
    journalctl -u "$APP" -n 30 --no-pager >&2
    exit 1
fi

say "Up"
systemctl status "$APP" --no-pager -n 3 | head -5
echo
echo "Logs:    journalctl -u ${APP} -f"
echo "         /opt/intelli/logs/${APP}.log"
