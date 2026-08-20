# Raspberry Pi OS APT repository

The project publishes a signed APT repository for 64-bit (`arm64`) Raspberry
Pi OS on Raspberry Pi 5, based on Debian 13 (Trixie). The application package
has architecture `all` because its Java payload is platform-independent; the
repository index is published only for `arm64` systems.

## Install on a Raspberry Pi

Install the repository's public signing key in a dedicated keyring:

```sh
curl -fsSL https://tcox2.github.io/cool-raspberries-apt/apt/repository-key.gpg \
  | sudo tee /usr/share/keyrings/cool-raspberries.gpg >/dev/null
```

Add the repository and install the package:

```sh
echo "deb [arch=arm64 signed-by=/usr/share/keyrings/cool-raspberries.gpg] https://tcox2.github.io/cool-raspberries-apt/apt stable main" \
  | sudo tee /etc/apt/sources.list.d/cool-raspberries.list
sudo apt update
sudo apt install cool-raspberries
```

The package enables the systemd unit but deliberately does not start it on the
first installation. Edit `/etc/cool-raspberries/gateway.properties`, place the
configured PEM certificate and private key under `/etc/cool-raspberries/tls`,
and then start the service:

```sh
sudo systemctl start cool-raspberries
sudo systemctl status cool-raspberries
```

The package creates the properties file only when it does not already exist.
Package upgrades and removal therefore do not overwrite or delete Pi-local
credentials, certificate paths, serial-port assignments, or passwords.

## Repository signing and publishing

The `Publish Raspberry Pi APT repository` GitHub Actions workflow runs for each
push to `main`. It tests the code, creates the `.deb`, signs the APT metadata,
and publishes the static files to the separate public `cool-raspberries-apt`
repository, which GitHub Pages serves.

The repository contains only the public signing key and placeholder
configuration. The OpenPGP private key and its passphrase must exist only as
GitHub Actions repository secrets named:

- `APT_SIGNING_PRIVATE_KEY_B64`: base64-encoded exported private key
- `APT_SIGNING_KEY_PASSPHRASE`: the private key passphrase
- `APT_REPOSITORY_DEPLOY_KEY`: private half of a write-enabled deploy key for
  the public `cool-raspberries-apt` artifact repository

The generated Pages content and package history are public. Application source
and Pi-local configuration remain in the private source repository or on the
Pi. The deploy key grants access only to the public artifact repository.

## Generate a signing key

Run these commands once on a trusted Linux workstation. Keep the resulting
private export offline as a recovery backup and never commit it:

```sh
gpg --quick-generate-key "Cool Raspberries APT Repository" rsa3072 sign 2y
fingerprint=$(gpg --with-colons --list-secret-keys \
  "Cool Raspberries APT Repository" | awk -F: '$1 == "fpr" {print $10; exit}')
gpg --batch --armor --export-secret-keys "$fingerprint" \
  | base64 -w0
```

Copy only the final base64 output into `APT_SIGNING_PRIVATE_KEY_B64`. Store the
key's passphrase separately in `APT_SIGNING_KEY_PASSPHRASE`. Rotate the key
before its expiry and publish the replacement public key before signing solely
with the replacement.
