# Serial traffic logging

Serial traffic is logged at `INFO` so the rotating file log contains a complete
wire-level record without requiring a debug setting.

Every entry has these searchable fields:

- `serial`: `ac/<instance-id>` or `modbus`
- `direction`: `RX`, `RX-FRAME`, `RX-REQUEST`, `RX-REJECTED`, `RX-IGNORED`,
  `TX`, or `TX-RESPONSE`
- `bytes`: uppercase, space-separated hexadecimal bytes
- `meaning`: protocol-aware interpretation

For example:

```text
serial=ac/living-room direction=TX bytes="7A 7A 21 D5 ..." meaning="AC A1 21→D5, length=24, CRC=valid, write configuration: power=1, mode=cool(1), ..."
serial=modbus direction=RX-REQUEST bytes="02 03 00 04 00 02 ..." meaning="Modbus request unit=2, function=03/read-holding, CRC=valid, address=4, count=2"
```

Raw receive chunks are logged as they arrive. A second entry is emitted when
enough bytes have arrived to decode a complete AC frame or Modbus request. This
intentional duplication lets an operator reconstruct noise, truncated traffic,
and decoder resynchronization while still having readable message summaries.

AC descriptions identify the source and destination addresses, message type,
length, CRC state, and known fields. A1 control frames and A3 state frames
include their decoded mode, fan, setpoint, flags, timer, and other evidenced
values. Provisional message meanings are labelled as such.

Modbus descriptions include unit ID, broadcast status, function, addresses,
counts, values, CRC state, acknowledgements, and named exception codes. Valid
requests for unconfigured unit IDs are explicitly logged as ignored.

Transmit entries are written immediately before the serial write. Consequently,
an exception after a partial hardware write still leaves the attempted bytes in
the log alongside the subsequent write error and stack trace.

Full wire logging can be high volume. Size `log.limitBytes` and `log.files` for
the number of connected air conditioners and the desired retention period.

## Web audit logging

Every HTTPS request also produces a structured `web-audit` entry containing:

- authenticated username
- client address
- HTTP method and path
- response status
- a description of the action and its result

For example:

```text
web-audit user="admin" client="192.0.2.10" method=GET path="/" status=200 action="viewed air conditioner id=living-room name=Living room"
web-audit user="operator" client="192.0.2.11" method=POST path="/control" status=303 action="updated air conditioner id=bedroom name=Bedroom: power=1, mode=Cool(1), fan=2, setpoint=25°C, turbo=0, quiet=1"
```

Health checks identify whether aggregate or per-AC health was requested and the
reported online state. Rejected control submissions include the target AC and
reason. Authentication failures are logged as `user="unauthenticated"` with a
401 result.

Passwords, Basic authorization headers, and encoded credentials are never
included. User-controlled text is escaped before it is placed in an audit line
to prevent forged multiline log entries.
