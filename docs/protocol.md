# UART protocol specification

Status: incomplete, based on static analysis rather than a verified bus capture.

Byte offsets are zero-based. Multi-byte ordering below describes the behaviour
of the reviewed implementation; questionable cases are explicitly marked.

## Physical and serial layer

| Property | Value | Confidence |
|---|---:|---|
| Baud rate | 9600 | High |
| Data bits | 8 | High |
| Parity | none | High |
| Stop bits | 1 | High |
| Logic voltage | unknown | Unknown |
| Idle polarity | conventional UART assumed | Low |
| Flow control | none observed | Medium |

The reference ESP8266 firmware assigns GPIO 4 to receive and GPIO 5 to
transmit. Those are properties of that implementation, not necessarily the
connector pinout of an indoor unit.

## Common frame envelope

| Offset | Size | Meaning |
|---:|---:|---|
| 0 | 2 | Synchronization marker, always `7A 7A` |
| 2 | 1 | Source address |
| 3 | 1 | Destination address |
| 4 | 1 | Total frame length in bytes, including CRC |
| 5 | 1 | Unknown header field 1 |
| 6 | 1 | Unknown header field 2 |
| 7 | 1 | Message type |
| 8 | 1 | Type-dependent header/payload byte |
| 9 | 1 | Type-dependent header/payload byte |
| 10 | variable | Type-dependent payload |
| length−2 | 2 | CRC, most-significant byte transmitted first |

Known addresses:

| Value | Endpoint |
|---:|---|
| `21` | Wi-Fi-side controller |
| `D5` | indoor unit |

Offsets 8 and 9 must not be treated as a universal `0A 0A` delimiter. The
transmitted `A1` constructed by the reviewed firmware uses `00 00`, while its
`AB` keepalive uses `0A 0A`. Received frames preserve whatever values appeared
on the bus.

### Framing algorithm

1. Scan for two consecutive `7A` bytes.
2. Read through offset 9.
3. Reject lengths below 12 or beyond an implementation-defined safe maximum.
4. Read `length − 10` remaining bytes.
5. Verify the CRC before interpreting any payload.
6. Dispatch on the message type at offset 7.

A production parser should recover from false synchronization, partial frames,
timeouts, and unsupported lengths. The reference parser does not demonstrate
all of these safeguards.

## CRC

The checksum matches CRC-16/MODBUS:

| Parameter | Value |
|---|---:|
| Width | 16 |
| Polynomial (reflected) | `A001` |
| Initial value | `FFFF` |
| Input reflection | yes |
| Output reflection | yes |
| Final XOR | `0000` |

Calculate over bytes `0..length−3`. Despite the usual MODBUS wire convention,
this protocol stores the numeric CRC with its high byte first:

```text
frame[length-2] = (crc >> 8) & FF
frame[length-1] = crc & FF
```

For the ten-byte keepalive prefix `7A 7A 21 D5 0C 00 00 AB 0A 0A`, the complete
frame is:

```text
7A 7A 21 D5 0C 00 00 AB 0A 0A FC F9
```

## Message catalogue

| Type | Declared length | Observed direction | Interpretation | Confidence |
|---:|---:|---|---|---|
| `A1` | 24 | `21` → `D5` | write operating configuration | High |
| `A2` | 12 | reportedly `21` → `D5` | configuration/enrolment completion | Low |
| `A3` | 34 | `D5` → `21` | periodic operating state | High |
| `A4` | 13 | `D5` → `21` | remote-control status/event | Medium |
| `A5` | 27 | reportedly `21` → `D5` | enrolment or display-related command | Low |
| `A6` | 28 | `D5` → `21` | unknown | Low |
| `AB` | 12 | `21` → `D5` | keepalive | High |
| `AC` | 18 | `D5` → `21` | likely keepalive response | Medium |

Only `A1` and `AB` are constructed for transmission in the reviewed device
class. `A3`, `A4`, `A6`, and `AC` are stored on receipt. Names for `A2`, `A5`,
`A6`, and `AC` should therefore remain provisional until supported by captures.

## A1 — write operating configuration

Envelope values produced by the reference implementation:

```text
7A 7A 21 D5 18 00 00 A1 00 00 ... CRC_H CRC_L
```

| Offset | Bits | Meaning | Encoding |
|---:|---|---|---|
| 10–11 | — | timer value | byte order unresolved; see note |
| 12 | 7 | turbo | boolean |
| 12 | 6:4 | fan speed | unsigned 3-bit value |
| 12 | 3 | power | boolean |
| 12 | 2:0 | requested mode | enum below |
| 13 | 6 | quiet | boolean |
| 13 | 5 | temperature unit | `0/1`, mapping not established |
| 13 | 3:0 | temperature setpoint | encoded value + 16 °C |
| 14 | 7:4 | left/right sweep | unsigned 4-bit value |
| 14 | 3:0 | up/down sweep | unsigned 4-bit value |
| 15 | 7 | display | boolean |
| 15 | 6 | ionizer | boolean |
| 15 | 4 | auxiliary heater | boolean |
| 15 | 3:2 | temperature display mode | unsigned 2-bit value |
| 15 | 1 | sleep | boolean |
| 15 | 0 | energy saving | boolean |
| 16–21 | — | controller MAC address | six raw octets |
| 22–23 | — | CRC | high byte, then low byte |

Bits 5 and 4 of offset 12 cannot both represent a four-bit fan value as one
earlier description suggested: the code explicitly uses bits 6:4, leaving bit
7 for turbo.

Timer caution: the setter writes the low eight bits to offset 10 and the high
eight bits to offset 11, implying little-endian storage. The A3 decoder,
however, computes `offset10 × 256 + offset11`. In addition, the setter combines
new timer bits using OR rather than replacement. Treat timer writes as
unverified until tested against a capture.

### Mode values

| Value | Meaning |
|---:|---|
| 0 | automatic |
| 1 | cooling |
| 2 | dehumidify/dry |
| 3 | fan only |
| 4 | heating |
| 5–7 | unknown/reserved |

### Fan values

The implementation accepts values 0–7 but the semantic mapping is not proven.
A previous project note described 0 as automatic and 5 as maximum; captures are
needed to establish every value.

## A3 — operating state

Expected length is 34 bytes, with CRC at offsets 32–33.

| Offset | Bits | Meaning | Decoding |
|---:|---|---|---|
| 10–11 | — | return-air temperature | `10 × byte10 + byte11`, in tenths °C |
| 12 | 2 | cleaning requested | boolean |
| 12 | 1 | “3D air” | boolean |
| 12 | 0 | controls locked | boolean |
| 13 | 7 | turbo | boolean |
| 13 | 6:4 | fan speed | unsigned 3-bit value |
| 13 | 3 | power | boolean |
| 13 | 2:0 | current operation mode | enum above |
| 14 | 6 | quiet | boolean |
| 14 | 5 | temperature unit | `0/1`, mapping unknown |
| 14 | 3:0 | setpoint | encoded value + 16 °C |
| 15 | 7:4 | left/right sweep | unsigned 4-bit value |
| 15 | 3:0 | up/down sweep | unsigned 4-bit value |
| 16 | 7 | display | boolean |
| 16 | 6 | ionizer | boolean |
| 16 | 4 | auxiliary heater | boolean |
| 16 | 3:2 | temperature display mode | unsigned 2-bit value |
| 16 | 1 | sleep | boolean |
| 16 | 0 | energy saving | boolean |
| 17–18 | — | unknown | — |
| 19–20 | — | remaining timer | `byte19 × 256 + byte20` in implementation |
| 21 | 2:0 | requested/returned operation mode | enum above |
| 22 | — | unknown | — |
| 23–24 | — | operating hours | big-endian unsigned 16-bit |
| 25–31 | — | unknown | — |
| 32–33 | — | CRC | high byte, then low byte |

The unusual temperature formula is not ordinary base-256 or packed BCD. For
example, bytes `01 05` decode to 15 tenths (1.5 °C), not 16.5 °C. A previously
published example claiming 16.5 °C is arithmetically inconsistent with the
implementation. Captures should determine whether byte 10 represents tens of
degrees and byte 11 represents tenths, whether an offset is missing, or whether
the code is wrong.

## A4 — remote-control state/event

Expected length is 13 bytes.

| Offset | Value | Interpretation in reference implementation |
|---:|---:|---|
| 10 | `00` | remote control enabled |
| 10 | `01` | remote control disabled |
| 10 | `A5` | third/unknown state |
| 11–12 | — | presumed CRC |

The semantic label is inferred from variable names and has not been confirmed
against a physical remote-control experiment.

## AB — keepalive

The Wi-Fi-side controller transmits a 12-byte `AB` frame once every 60 seconds:

```text
7A 7A 21 D5 0C 00 00 AB 0A 0A FC F9
```

The reviewed firmware marks the connection alive after receiving an `A4`
message, not specifically after receiving `AC`. The relationship between `AB`,
`AC`, and `A4` therefore needs capture-level verification.

## Unknown messages

### A2

Only the length of 12 bytes is evidenced in the device class. No construction
or receive processing was found.

### A5

Only the length of 27 bytes is evidenced in the device class. No construction
or receive processing was found.

### A6

The parser stores all 28 bytes but does not interpret them or validate their
CRC.

### AC

The parser stores all 18 bytes but does not interpret them or validate their
CRC. Its role as the keepalive response is plausible but not demonstrated by
the implementation alone.
