# HTTPS observation UI

The gateway serves a JavaScript-free, read-only HTTPS page on the configured
`web.port`. Every route requires Basic authentication, which must only be used
over HTTPS.

For each configured air conditioner the page shows its connection state,
Modbus unit ID, observed power, measured temperature, and remaining sleep
timer. The page also documents the holding and input register map.

HTTPS cannot change air-conditioner state and exposes no control endpoint.
Power, target temperature, and sleep timer may be changed only by writing the
documented Modbus holding registers.

The read-only health endpoints are `GET /health` for all configured units and
`GET /health/{ac-id}` for one unit; they require the same credentials.
