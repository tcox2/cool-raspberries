# HTTPS control UI

The gateway serves a JavaScript-free HTTPS page on the configured `web.port`.
Every route requires Basic authentication, which must only be used over HTTPS.

For each configured air conditioner the page shows connection state, Modbus unit
ID, observed power, measured temperature, and remaining sleep timer. The only
controls are power on/off, target temperature (16–31 °C), and sleep timer in
minutes (0 disables it).

Requested controls start at fixed defaults: power off, 24 °C, and no sleep
timer. Hidden protocol settings also use fixed defaults and are never copied
from the air conditioner's last state. A valid A3 frame is still required before
the first command can be submitted.

`POST /control` validates and applies all three visible fields atomically. The
health endpoints are `GET /health` for all configured units and
`GET /health/{ac-id}` for one unit; they require the same credentials.
