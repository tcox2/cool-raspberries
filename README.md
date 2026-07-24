# Cool Raspberries

A reliability-oriented Java 21 gateway for Raspberry Pi:

```text
Air-conditioner UART ⇄ Java gateway ⇄ Modbus RTU
                              ⇅
                         HTTP control UI
```

The service talks to the split air conditioner over one serial port, exposes
control and status registers as a Modbus RTU server on a second serial port,
serves a small web UI, and writes rotating log files.

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
bazel build //:cool-raspberries_deploy.jar
java -jar bazel-bin/cool-raspberries_deploy.jar \
  config/gateway.properties.example
```

The Bazel build uses a pinned Java 21 toolchain. Its two external JARs are
downloaded from Maven Central and verified against SHA-256 checksums.
The deploy JAR contains the jSerialComm native serial library. The web server
is the JDK's built-in `jdk.httpserver`; no application server is required.

## Raspberry Pi installation

1. Build on the Pi or copy the built JAR and repository deployment files to it.
2. Run `sudo scripts/install.sh`.
3. Edit `/etc/cool-raspberries/gateway.properties`.
4. Prefer stable `/dev/serial/by-id/...` paths for both adapters.
5. Start with `sudo systemctl start cool-raspberries`.
6. Inspect `systemctl status cool-raspberries` and the configured log file.

The service runs as the unprivileged `cool-raspberries` user in the `dialout`
group, restarts after failures, and has basic systemd hardening.

For Modbus RS-485, use an adapter that controls transmit direction
automatically. The service does not currently toggle a separate GPIO or RTS
line for a bare MAX485-style transceiver.

The built-in HTTP server has no authentication. Do not expose it directly to
the internet. Keep it bound to loopback, or put access control and TLS on a
reverse proxy or firewall before making it reachable from another machine.

## Interfaces

- [Modbus register map](docs/register-map.md)
- [Reverse-engineered UART protocol](docs/protocol.md)
- `GET /api/status` — JSON state
- `POST /api/control` — form fields `address` and `value`; requires request
  header `X-Cool-Raspberries: 1`
- `GET /health` — HTTP 200 when AC state is current, otherwise 503
- `GET /` — control and status UI

## Design

The AC worker is the only component that writes to the proprietary UART.
Modbus and HTTP requests update a validated register bank; the AC worker turns
the latest coherent snapshot into an A1 frame. Each serial worker reconnects
with bounded exponential backoff. Incoming AC frames are length-bounded and
CRC-validated before state is published.

The Modbus server currently implements holding and input registers only. It
uses standard RTU CRC byte ordering, which differs from the proprietary
protocol's on-wire ordering.

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
