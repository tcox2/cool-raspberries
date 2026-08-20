#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "Run this installer as root." >&2
  exit 1
fi

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
watchdog_timeout=30

if [ -f "$project_dir/cool-raspberries.jar" ]; then
  jar="$project_dir/cool-raspberries.jar"
else
  jar="$project_dir/bazel-bin/cool-raspberries_deploy.jar"
fi

if [ ! -f "$jar" ]; then
  echo "Missing deployable JAR; extract a release archive or run:" >&2
  echo "  bazel build //:cool-raspberries_deploy.jar" >&2
  exit 1
fi

if ! dpkg-query -W -f='${Status}\n' openjdk-21-jdk-headless 2>/dev/null \
    | grep -q '^install ok installed$'; then
  echo "Installing the Java 21 headless JDK."
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    openjdk-21-jdk-headless
fi

java_21=$(update-alternatives --list java 2>/dev/null \
  | awk '/java-21-openjdk/ { print; exit }')
javac_21=$(update-alternatives --list javac 2>/dev/null \
  | awk '/java-21-openjdk/ { print; exit }')
if [ -z "$java_21" ] || [ -z "$javac_21" ]; then
  echo "Java 21 JDK was installed but its alternatives were not found." >&2
  exit 1
fi
update-alternatives --set java "$java_21"
update-alternatives --set javac "$javac_21"

if ! getent group cool-raspberries >/dev/null; then
  groupadd --system cool-raspberries
fi
if ! getent passwd cool-raspberries >/dev/null; then
  useradd --system --gid cool-raspberries --home-dir /nonexistent \
    --shell /usr/sbin/nologin cool-raspberries
fi
usermod -a -G dialout cool-raspberries

install -d -m 0755 /opt/cool-raspberries /etc/cool-raspberries
install -d -m 0750 -o root -g cool-raspberries /etc/cool-raspberries/tls
install -m 0644 "$jar" /opt/cool-raspberries/cool-raspberries.jar
install -m 0644 "$project_dir/deploy/cool-raspberries.service" /etc/systemd/system/cool-raspberries.service

install -d -m 0755 /etc/systemd/system.conf.d
install -m 0644 "$project_dir/deploy/10-cool-raspberries-watchdog.conf" \
  /etc/systemd/system.conf.d/10-cool-raspberries-watchdog.conf

boot_config=
for candidate in /boot/firmware/config.txt /boot/config.txt; do
  if [ -f "$candidate" ]; then
    boot_config=$candidate
    break
  fi
done

if [ -n "$boot_config" ]; then
  sed -i '/^# BEGIN cool-raspberries-watchdog$/,/^# END cool-raspberries-watchdog$/d' "$boot_config"
  sed -i '/^[[:space:]]*kernel_watchdog_timeout[[:space:]]*=/d' "$boot_config"
  {
    echo
    echo "# BEGIN cool-raspberries-watchdog"
    echo "[all]"
    echo "kernel_watchdog_timeout=$watchdog_timeout"
    echo "# END cool-raspberries-watchdog"
  } >>"$boot_config"
else
  echo "Warning: Raspberry Pi boot config not found; watchdog boot handover was not configured." >&2
fi

if [ ! -f /etc/cool-raspberries/gateway.properties ]; then
  install -m 0640 -o root -g cool-raspberries \
    "$project_dir/config/gateway.properties.example" \
    /etc/cool-raspberries/gateway.properties
  echo "Created /etc/cool-raspberries/gateway.properties; configure it before starting."
fi

echo "Install the configured PEM certificate and private key under /etc/cool-raspberries/tls."
echo "Keep the private key owned by root:cool-raspberries with mode 0640 or stricter."

systemctl daemon-reload
systemctl enable cool-raspberries.service
systemctl set-default multi-user.target
echo "Installed with a ${watchdog_timeout}s whole-Pi hardware watchdog."
echo "Graphical desktop boot disabled; the desktop packages remain installed."
echo "Reboot to activate the watchdog and start cool-raspberries."
