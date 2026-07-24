#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "Run this installer as root." >&2
  exit 1
fi

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
jar="$project_dir/target/cool-raspberries.jar"

if [ ! -f "$jar" ]; then
  bazel_jar="$project_dir/bazel-bin/cool-raspberries_deploy.jar"
  if [ -f "$bazel_jar" ]; then
    jar="$bazel_jar"
  else
    echo "Missing application JAR; run one of:" >&2
    echo "  ./mvnw clean verify" >&2
    echo "  bazel build //:cool-raspberries_deploy.jar" >&2
    exit 1
  fi
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

if [ ! -f /etc/cool-raspberries/gateway.properties ]; then
  install -m 0640 -o root -g cool-raspberries \
    "$project_dir/config/gateway.properties.example" \
    /etc/cool-raspberries/gateway.properties
  echo "Created /etc/cool-raspberries/gateway.properties; configure it before starting."
fi

systemctl daemon-reload
systemctl enable cool-raspberries.service
echo "Installed. Run: systemctl start cool-raspberries"
