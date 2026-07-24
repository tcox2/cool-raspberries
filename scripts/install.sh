#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "Run this installer as root." >&2
  exit 1
fi

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
jar="$project_dir/bazel-bin/cool-raspberries_deploy.jar"
watchdog_timeout=30

if [ ! -f "$jar" ]; then
  echo "Missing $jar; run:" >&2
  echo "  bazel build //:cool-raspberries_deploy.jar" >&2
  exit 1
fi

if ! getent group cool-raspberries >/dev/null; then
  groupadd --system cool-raspberries
fi
if ! getent passwd cool-raspberries >/dev/null; then
  useradd --system --gid cool-raspberries --home-dir /nonexistent \
    --shell /usr/sbin/nologin cool-raspberries
fi
usermod -a -G dialout cool-raspberries

install -d -m 0755 /opt/cool-raspberries /etc/cool-raspberries
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

systemctl daemon-reload
systemctl enable cool-raspberries.service
systemctl set-default multi-user.target
echo "Installed with a ${watchdog_timeout}s whole-Pi hardware watchdog."
echo "Graphical desktop boot disabled; the desktop packages remain installed."
echo "Reboot to activate the watchdog and start cool-raspberries."
