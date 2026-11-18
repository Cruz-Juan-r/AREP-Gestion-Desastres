# Contratos MQTT (red local del municipio)

| Tópico | Publica | Suscribe | QoS | Carga útil |
|---|---|---|---|---|
| `sensors/{sensor_type}/{device_id}` | dispositivos / gateway de campo | nodo edge | 1 | `telemetry.schema.json` |
| `alerts/{severity}/{device_id}` | nodo edge | respondientes locales, COP | 1 | `alert.schema.json` |

- `sensor_type` ∈ {`river_level`, `rainfall`, `seismic`, `drone`}; `severity` ∈ {`warning`, `critical`}.
- QoS 1 = al menos una vez: puede haber reentregas; el nodo edge las descarta por `event_id`.
- `event_id` (UUID) es la clave de idempotencia de extremo a extremo: dispositivo → edge → nube.
