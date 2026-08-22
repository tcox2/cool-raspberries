#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 VERSION OUTPUT_DIRECTORY" >&2
  exit 2
fi

version=$1
output_dir=$2
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
jar="$project_dir/bazel-bin/cool-raspberries_deploy.jar"

case "$version" in
  ''|*[!0-9A-Za-z.+:~_-]*)
    echo "Invalid Debian version: $version" >&2
    exit 2
    ;;
esac

for command_name in dpkg-deb sed du install mktemp; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Missing required command: $command_name" >&2
    exit 1
  fi
done

if [ ! -f "$jar" ]; then
  echo "Missing $jar; run bazel build //:cool-raspberries_deploy.jar first." >&2
  exit 1
fi

staging=$(mktemp -d)
trap 'rm -rf "$staging"' EXIT HUP INT TERM
package_root="$staging/cool-raspberries"

install -d "$package_root/DEBIAN"
install -d "$package_root/opt/cool-raspberries"
install -d "$package_root/lib/systemd/system"
install -d "$package_root/usr/lib/systemd/system.conf.d"
install -d "$package_root/usr/share/doc/cool-raspberries"

install -m 0644 "$jar" \
  "$package_root/opt/cool-raspberries/cool-raspberries.jar"
printf '%s\n' "$version" > "$package_root/opt/cool-raspberries/version"
chmod 0644 "$package_root/opt/cool-raspberries/version"
install -m 0644 "$project_dir/deploy/cool-raspberries.service" \
  "$package_root/lib/systemd/system/cool-raspberries.service"
install -m 0644 "$project_dir/deploy/10-cool-raspberries-watchdog.conf" \
  "$package_root/usr/lib/systemd/system.conf.d/10-cool-raspberries-watchdog.conf"
install -m 0644 "$project_dir/config/gateway.properties.example" \
  "$package_root/usr/share/doc/cool-raspberries/gateway.properties.example"
install -m 0644 "$project_dir/README.md" \
  "$package_root/usr/share/doc/cool-raspberries/README.md"

for maintainer_script in postinst prerm postrm; do
  install -m 0755 "$project_dir/packaging/debian/$maintainer_script" \
    "$package_root/DEBIAN/$maintainer_script"
done

installed_size=$(du -sk "$package_root" | awk '{print $1}')
sed -e "s/@VERSION@/$version/g" \
  -e "s/@INSTALLED_SIZE@/$installed_size/g" \
  "$project_dir/packaging/debian/control.in" > "$package_root/DEBIAN/control"

mkdir -p "$output_dir"
output_file="$output_dir/cool-raspberries_${version}_all.deb"
dpkg-deb --root-owner-group --build "$package_root" "$output_file"
echo "$output_file"
