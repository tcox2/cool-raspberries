# Cool Raspberries

A reliability-oriented Java 21 gateway for Raspberry Pi:

```text
Air-conditioner UARTs ⇄ Java gateway ⇄ one Modbus RTU bus
                               ⇅
                    authenticated HTTPS control UI
```

The service talks to one or more split air conditioners over dedicated serial
ports. Each air conditioner is exposed as a separate Modbus unit on one shared
Modbus RTU serial port. The service also provides an authenticated HTTPS UI and
writes rotating log files.

## Implementation status

The software is complete enough for bench testing, but it has **not been
validated on an air conditioner**. The protocol contains unresolved fields,
notably return-air temperature and timer encoding. Keep the AC disconnected
from mains while identifying the low-voltage connector, and verify voltage
levels before connecting a Raspberry Pi or USB adapter.

## Build

Install Bazelisk. It reads the pinned Bazel version from `.bazelversion`, and
Bazel downloads the Java 21 toolchain:

```sh
bazel test //...
bazel build //:release-archive
java -jar bazel-bin/cool-raspberries_deploy.jar \
  config/gateway.properties.example
```

The Bazel build uses a pinned Java 21 toolchain. External JARs are downloaded
from Maven Central and pinned with SHA-256 checksums. The deploy JAR contains
the jSerialComm native serial library, Javalin, its embedded Jetty server, and
the Javalin TLS plugin; no separate application server is required.
The release archive is written to `bazel-bin/cool-raspberries.tar.gz`.

## Raspberry Pi installation

1. Download a release archive and its `SHA256SUMS` file, then verify and extract
   the archive:
   ```sh
   sha256sum --ignore-missing --check SHA256SUMS
   tar -xzf cool-raspberries-*.tar.gz
   ```
2. Enter the extracted `cool-raspberries` directory and run
   `sudo scripts/install.sh`.
3. Install a PEM certificate chain and its private key under
   `/etc/cool-raspberries/tls`, then edit
   `/etc/cool-raspberries/gateway.properties` with their paths and at least one
   Basic authentication user.
4. Prefer stable `/dev/serial/by-id/...` paths for every adapter.
5. Ensure SSH is enabled before disconnecting the display from a headless Pi.
6. Reboot once to activate the watchdog and start the gateway.
7. Inspect `systemctl status cool-raspberries` and the configured log file.

The service runs as the unprivileged `cool-raspberries` user in the `dialout`
group, restarts after failures, and has basic systemd hardening.
The installer installs the Java 21 headless JDK from Raspberry Pi OS and selects
Java 21 as the system `java` and `javac`.
The installer configures systemd to feed the Raspberry Pi hardware watchdog;
if the operating system stops feeding it for 30 seconds, the Pi resets.
It also selects `multi-user.target` so an installed graphical desktop does not
start at boot. Restore desktop boot with
`sudo systemctl set-default graphical.target`.

For Modbus RS-485, use an adapter that controls transmit direction
automatically. The service does not currently toggle a separate GPIO or RTS
line for a bare MAX485-style transceiver.

The HTTPS server uses Javalin/Jetty, always binds to `0.0.0.0`, disables its
insecure HTTP connector, and requires Basic authentication on every route.
TLS certificate, private-key, username, and password settings come from the
properties file. Use a host or network firewall as an additional boundary.

## Interfaces

- [Modbus register map](docs/register-map.md)
- [HTTP control UI](docs/web-ui.md)
- [Serial traffic logging](docs/logging.md)
- [Reverse-engineered UART protocol](docs/protocol.md)
- `POST /control` — server-rendered HTML form submission for visible controls
- `GET /health` — HTTPS 200 when every configured AC is current, otherwise 503
- `GET /health/{ac-id}` — health of one configured AC
- `GET /?ac={ac-id}` — AC selector, control, and status UI

## Design

Each AC worker is the only component that writes to its proprietary UART.
Modbus and HTTP requests update a validated register bank; the AC worker turns
the latest coherent snapshot into an A1 frame. Each serial worker reconnects
with bounded exponential backoff. Incoming AC frames are length-bounded and
CRC-validated before state is published.

The Modbus server routes each configured unit ID to an independent register
bank. It currently implements holding and input registers only and uses standard
RTU CRC byte ordering, which differs from the proprietary protocol's on-wire
ordering.

Every serial receive chunk and transmit attempt is logged at `INFO` with its
hexadecimal bytes and a description. Complete AC frames and Modbus messages get
additional protocol-aware entries describing their type, addresses, controls,
state, register range, values, CRC result, or exception. Logs rotate according
to `log.limitBytes` and `log.files`.

Authenticated web requests are audit-logged with username, client address,
method, path, response status, target AC, and a description of the action.
Accepted control changes include every submitted setting; rejected changes
include the reason. Passwords and authorization headers are never logged.

## Protocol research

An evidence-based description of the UART messages used between an Airton split
air-conditioner indoor unit and its Wi-Fi module.

The current evidence comes from revision
[`ffcedb4`](https://github.com/TheMiNuS/UnleashedAirConditionner/tree/ffcedb4f8865e0bb8a25cdabcfa9176f31f1610d)
of `TheMiNuS/UnleashedAirConditionner`. This repository restates observed facts
in new words; it does not contain firmware or copied implementation code.

Start with [the protocol specification](docs/protocol.md). The
[research notes](docs/research-notes.md) distinguish observations from
inferences and list inconsistencies that still need captures or hardware tests.

## What is known

- UART parameters used by the reference firmware: 9600 baud, 8 data bits, no
  parity, 1 stop bit.
- Frames begin with `7A 7A`, carry their total byte length at offset 4, and end
  with a two-byte CRC-16/MODBUS value.
- Address `21` is used by the Wi-Fi-side controller and `D5` by the indoor unit.
- Message types `A1`, `A3`, `A4`, `A6`, `AB`, and `AC` are at least partially
  evidenced by the implementation. Lengths are also declared for `A2` and `A5`.
- `A1` writes operating settings, `A3` reports state, and `AB` is a periodic
  keepalive.

## Important limitations

No electrical pinout, logic voltage, isolation requirement, or confirmed model
matrix was present in the reviewed material. Do not connect a microcontroller
until those properties have been measured. An indoor AC unit contains
hazardous mains voltages even when its low-voltage serial connector appears
accessible.

The protocol name in this repository is descriptive, not an assertion that all
Airton products share it.

## Contributing evidence

Packet captures are most useful when they include:

1. Exact indoor-unit model and PCB identifier.
2. UART voltage levels and idle polarity.
3. Timestamped TX/RX bytes without dropped data.
4. The physical action associated with the capture.
5. Repeated control experiments changing only one setting.

Please redact Wi-Fi credentials and hardware identifiers before sharing.

## Attribution and legal note

The factual starting point was the public
[UnleashedAirConditionner repository](https://github.com/TheMiNuS/UnleashedAirConditionner)
by TheMiNuS, whose material is published under CC BY-NC-ND 4.0. No permission
to distribute modified versions of that project is implied here. This is an
independently written research summary, not a fork.
