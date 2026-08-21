# Modbus RTU register map

The Raspberry Pi is a Modbus RTU server. Each configured air conditioner has
an independent register bank at its configured unit ID. Addresses are
zero-based; some clients display holding address 0 as 40001 and input address 0
as 30001.

Supported functions are 03 (read holding), 04 (read input), 06 (write one
holding), and 16 (write multiple holding). Invalid addresses or values return
exception 03.

## Holding registers: requested controls

| Address | Name | Range |
|---:|---|---:|
| 0 | power | 0 off, 1 on |
| 1 | target temperature | 16–31 °C |
| 2 | sleep timer | 0–65535 minutes; 0 disables sleep |

Commands use fixed defaults for all hidden protocol fields: auto mode, automatic
fan code 0, and turbo, quiet, sweep, display, ionizer, auxiliary heat, and energy
saving off. They never inherit hidden settings from the AC's last state frame.
The initial requested values are power off, 24 °C, and sleep timer off.

The gateway waits for one valid A3 state frame before accepting a command,
because that establishes communication with the indoor unit. Function 16 can
set all three values atomically.

## Input registers: observed status

| Address | Name | Encoding |
|---:|---|---|
| 0 | power | 0 off, 1 on |
| 1 | measured temperature | tenths °C; encoding needs hardware verification |
| 2 | remaining sleep timer | minutes |

Modbus CRC bytes use standard RTU low-byte-first order. The proprietary AC
protocol transmits its numeric CRC high byte first.
