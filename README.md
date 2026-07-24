# Airton split AC UART protocol notes

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

