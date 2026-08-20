# HTTPS control UI

The gateway serves its control page at:

```text
https://<raspberry-pi-address>:8443/
```

The port, PEM certificate chain, PEM private key, optional private-key password,
and Basic authentication users are configured with the `web.*` properties.
There is no insecure HTTP listener. Every route, including health checks,
requires valid Basic credentials. Because Basic authentication sends a reusable
credential with each request, it must only be used over HTTPS.

The page is rendered entirely on the server and contains no JavaScript. Select
an air conditioner by its configured name; its stable ID remains in the URL and
in control form submissions. Status is a snapshot taken when the page is loaded.
Use `Refresh status` to request a new snapshot.

## Status display

| Display | Meaning |
|---|---|
| Modbus unit | Unit ID used to address this AC on the shared Modbus RTU bus. |
| Connection | `AC online` when a valid AC state frame is recent. Otherwise it shows `AC status stale` and the age of the last valid frame in seconds. Before any valid frame, the age is 65535 seconds. |
| Return air | Reported return-air temperature in degrees Celsius, shown to one decimal place. The underlying UART encoding has not yet been verified on hardware. |
| Power | Observed AC power state: `On` or `Off`. |
| Mode | Observed mode: `Auto`, `Cool`, `Dry`, `Fan`, or `Heat`. An unrecognized numeric code is displayed as a number. |
| Fan | Observed fan code from 0 through 7. The physical meaning of each code is model-specific. |

These rows show observed state from the selected air conditioner. They are
distinct from the requested values in its control section.

## Controls

Controls are populated from the selected AC's holding registers. The gateway
initializes those registers from the first valid A3 state frame received from
that AC.

| UI control | Holding address | Values | Effect |
|---|---:|---|---|
| Power | 0 | `Off` or `On` | Requests that the indoor unit turn off or on. |
| Mode | 1 | `Auto`, `Cool`, `Dry`, `Fan`, or `Heat` | Selects the requested operating mode. |
| Fan | 2 | Integer 0–7 | Selects the model-specific fan code. Hardware captures are still needed to name each code reliably. |
| Setpoint °C | 3 | Integer 16–31 | Sets the requested target temperature in degrees Celsius. |
| Turbo | 4 | `Off` or `On` | Requests maximum-output/turbo operation. Whether the unit accepts it can depend on the selected mode. |
| Quiet | 5 | `Off` or `On` | Requests quiet operation. Whether the unit accepts it can depend on the selected mode. |

`Apply controls` submits all six visible fields and the selected AC ID to
`POST /control`. The gateway validates the complete set before changing any
holding register, so the update is atomic and cannot spill into another AC.

After a successful submission, the server redirects the browser back to the
selected AC and displays `Control request accepted.` A rejected value or
unavailable control state is displayed as an error using the same layout.

The gateway rejects control requests until that AC has received its first valid
A3 state frame. A successful request means that the gateway accepted the value;
observed state confirms whether the air conditioner subsequently applied it.

## Health checks

- `GET /health` returns 200 only when every configured AC is current.
- `GET /health/{ac-id}` returns the health of one AC.

Both endpoints require the same Basic authentication credentials as the UI.

## Controls not currently shown

Holding registers 6–13—horizontal sweep, vertical sweep, indoor display,
ionizer, auxiliary heater, sleep, energy saving, and timer—are available
through Modbus, but the current web page does not expose fields for them. See
the [Modbus register map](register-map.md) for their values and limitations.
