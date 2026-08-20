# Modbus RTU register map

The Raspberry Pi is a Modbus RTU server (traditionally called a slave). The
external controller is the client/master.

Every configured air conditioner has an independent register bank and a unique
unit ID from `ac.<id>.modbusUnitId`. All unit IDs share the single serial port
configured by `modbus.*`. Requests to an unconfigured unit ID are ignored.
Write broadcasts to unit 0 are applied to every configured AC and produce no
response, as required by Modbus RTU.

Addresses below are zero-based protocol addresses. Software that displays
holding registers as `40001` may show address 0 as 40001; input register 0 may
similarly appear as `30001`.

Supported functions:

| Function | Operation |
|---:|---|
| 03 | read holding registers |
| 04 | read input registers |
| 06 | write one holding register |
| 16 | write multiple holding registers |

Unsupported functions return Modbus exception 01. Invalid addresses or values
return exception 03. Each multi-register write is validated before any value is
changed.

## Holding registers: requested controls

| Address | Name | Range |
|---:|---|---:|
| 0 | power | 0 off, 1 on |
| 1 | operating mode | 0 auto, 1 cool, 2 dry, 3 fan, 4 heat |
| 2 | fan code | 0–7; model-specific meanings |
| 3 | setpoint | 16–31 °C |
| 4 | turbo | 0/1 |
| 5 | quiet | 0/1 |
| 6 | left/right sweep code | 0–15 |
| 7 | up/down sweep code | 0–15 |
| 8 | indoor display | 0/1 |
| 9 | ionizer | 0/1 |
| 10 | auxiliary heater | 0/1 |
| 11 | sleep | 0/1 |
| 12 | energy saving | 0/1 |
| 13 | timer | 0–65535 minutes; byte order needs hardware verification |
| 14–31 | reserved | writes rejected by control validation policy |

On the first valid A3 state message, the gateway initializes the requested
controls from the actual AC state. Later writes update the requested values and
queue a complete A1 control frame. No control frame is sent before a valid A3
has been received.

## Input registers: observed status

| Address | Name | Encoding |
|---:|---|---|
| 0 | return-air temperature | tenths °C; underlying encoding is not yet verified |
| 1 | power | 0/1 |
| 2 | current operating mode | enum above |
| 3 | fan code | 0–7 |
| 4 | setpoint | °C |
| 5 | status flags | bit field below |
| 6 | left/right sweep | 0–15 |
| 7 | up/down sweep | 0–15 |
| 8 | remaining timer | minutes |
| 9 | operating time | hours, wraps at 65535 |
| 10 | requested/returned operating mode | 0–7 |
| 11 | remote-control state | 0 disabled, 1 enabled, 2 third state, 65535 unknown |
| 12 | AC online | 1 when a valid state frame is recent |
| 13 | last valid frame age | seconds, saturated at 65535 |
| 14 | accepted-frame counter | low 16 bits |
| 15 | CRC/error counter | low 16 bits |
| 16–31 | reserved | currently zero |

Status register 5:

| Bit | Meaning |
|---:|---|
| 0 | turbo |
| 1 | quiet |
| 2 | display |
| 3 | ionizer |
| 4 | auxiliary heater |
| 5 | sleep |
| 6 | energy saving |
| 7 | controls locked |
| 8 | 3D air |
| 9 | cleaning requested |

## Atomicity and timing

Function 16 should be used when changing related values together, such as mode,
fan and setpoint. The gateway validates the complete range and emits one A1
frame. Separate function-06 writes may produce one frame per write.

Modbus CRC bytes use the standard RTU low-byte-first order. The proprietary AC
protocol uses the same CRC calculation but transmits its numeric CRC high byte
first.
