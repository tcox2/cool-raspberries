#!/bin/bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 DEB_DIRECTORY OUTPUT_DIRECTORY CODENAME" >&2
  exit 2
fi

deb_dir=$1
output_dir=$2
codename=$3

for command_name in apt-ftparchive dpkg-scanpackages gpg gzip install mktemp; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Missing required command: $command_name" >&2
    exit 1
  fi
done

: "${APT_SIGNING_FINGERPRINT:?APT_SIGNING_FINGERPRINT must be set}"
: "${APT_SIGNING_KEY_PASSPHRASE:?APT_SIGNING_KEY_PASSPHRASE must be set}"

if ! compgen -G "$deb_dir/*.deb" >/dev/null; then
  echo "No .deb files found in $deb_dir" >&2
  exit 1
fi

if [[ -e "$output_dir" ]]; then
  echo "Output directory already exists: $output_dir" >&2
  exit 1
fi
install -d "$output_dir/pool/main/c/cool-raspberries"
install -m 0644 "$deb_dir"/*.deb \
  "$output_dir/pool/main/c/cool-raspberries/"

for architecture in arm64; do
  package_dir="$output_dir/dists/$codename/main/binary-$architecture"
  install -d "$package_dir"
  (
    cd "$output_dir"
    dpkg-scanpackages --arch all pool /dev/null > \
      "dists/$codename/main/binary-$architecture/Packages"
  )
  gzip -n -9 -c "$package_dir/Packages" > "$package_dir/Packages.gz"
done

release_file="$output_dir/dists/$codename/Release"
(
  cd "$output_dir"
  apt-ftparchive \
    -o APT::FTPArchive::Release::Origin="Cool Raspberries" \
    -o APT::FTPArchive::Release::Label="Cool Raspberries" \
    -o APT::FTPArchive::Release::Suite="$codename" \
    -o APT::FTPArchive::Release::Codename="$codename" \
    -o APT::FTPArchive::Release::Architectures="arm64" \
    -o APT::FTPArchive::Release::Components="main" \
    -o APT::FTPArchive::Release::Description="Cool Raspberries packages for Raspberry Pi OS" \
    release "dists/$codename" > "dists/$codename/Release"
)

passphrase_file=$(mktemp)
trap 'rm -f "$passphrase_file"' EXIT HUP INT TERM
chmod 0600 "$passphrase_file"
printf '%s' "$APT_SIGNING_KEY_PASSPHRASE" > "$passphrase_file"

gpg --batch --yes --pinentry-mode loopback \
  --passphrase-file "$passphrase_file" \
  --local-user "$APT_SIGNING_FINGERPRINT" \
  --clearsign --digest-algo SHA256 \
  --output "$output_dir/dists/$codename/InRelease" "$release_file"
gpg --batch --yes --pinentry-mode loopback \
  --passphrase-file "$passphrase_file" \
  --local-user "$APT_SIGNING_FINGERPRINT" \
  --detach-sign --armor --digest-algo SHA256 \
  --output "$output_dir/dists/$codename/Release.gpg" "$release_file"
gpg --batch --yes --armor --export "$APT_SIGNING_FINGERPRINT" > \
  "$output_dir/repository-key.asc"
gpg --batch --yes --export "$APT_SIGNING_FINGERPRINT" > \
  "$output_dir/repository-key.gpg"

echo "$output_dir"
