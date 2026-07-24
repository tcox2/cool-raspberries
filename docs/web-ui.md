# HTTP control UI

The gateway serves a small, responsive control page at:

```text
http://<raspberry-pi-address>:8080/
```

The port is set by `web.port`. The server always listens on `0.0.0.0` and has
no authentication, so use a firewall or reverse proxy to control which clients
can reach it.

The page reads `/api/status` when it opens and every three seconds thereafter.
An update failure changes the connection message to `Gateway unavailable`.

## Status display

| Display | Meaning |
|---|---|
| Connection | `AC online` when a valid AC state frame is recent. Otherwise it shows `AC status stale` and the age of the last valid frame in seconds. Before any valid frame, the age is 65535 seconds. |
| Return air | Reported return-air temperature in degrees Celsius, shown to one decimal place. The underlying UART encoding has not yet been verified on hardware. |
| Power | Observed AC power state: `On` or `Off`. |
| Mode | Observed mode: `Auto`, `Cool`, `Dry`, `Fan`, or `Heat`. An unrecognized numeric code is displayed as a number. |
| Fan | Observed fan code from 0 through 7. The physical meaning of each code is model-specific. |

These cards show observed state from the air conditioner. They are distinct
from the requested values in the control section.

## Controls

Controls are populated from the gateway's holding registers. The gateway
initializes those registers from the first valid A3 state frame received from
the air conditioner.

| UI control | Holding address | Values | Effect |
|---|---:|---|---|
| Power | 0 | `Off` or `On` | Requests that the indoor unit turn off or on. |
| Mode | 1 | `Auto`, `Cool`, `Dry`, `Fan`, or `Heat` | Selects the requested operating mode. |
| Fan | 2 | Integer 0–7 | Selects the model-specific fan code. Hardware captures are still needed to name each code reliably. |
| Setpoint °C | 3 | Integer 16–31 | Sets the requested target temperature in degrees Celsius. |
| Turbo | 4 | `Off` or `On` | Requests maximum-output/turbo operation. Whether the unit accepts it can depend on the selected mode. |
| Quiet | 5 | `Off` or `On` | Requests quiet operation. Whether the unit accepts it can depend on the selected mode. |
| Sweep L/R | 6 | Integer 0–15 | Selects the horizontal-vane/sweep code. The exact positions represented by individual codes are model-specific. |
| Sweep U/D | 7 | Integer 0–15 | Selects the vertical-vane/sweep code. The exact positions represented by individual codes are model-specific. |

Changing a field marks it as pending. Automatic status refreshes do not
overwrite pending fields.

### Apply changed controls

`Apply changed controls` sends each pending field to `POST /api/control`, then
refreshes the displayed state.

The result text beside the button shows:

- `Applying…` while requests are in progress.
- `Applied` after all changed fields are accepted.
- `Error: ...` when a request is rejected or the gateway cannot be reached.

Each changed field is currently sent as a separate request. Consequently, a
multi-field UI change is not atomic and may produce more than one A1 control
frame. Modbus function 16 should be used by automation that requires an atomic
mode/fan/setpoint update.

The gateway rejects control requests until it has received the first valid A3
state frame. It also rejects values outside the documented range. The page may
remain usable while status is stale, but a successful request only means that
the gateway accepted the requested value; observed state confirms whether the
air conditioner subsequently applied it.

## Controls not currently shown

Holding registers 8–13—indoor display, ionizer, auxiliary heater, sleep,
energy saving, and timer—are available through Modbus and `/api/control`, but
the current web page does not expose fields for them. See the
[Modbus register map](register-map.md) for their values and limitations.

