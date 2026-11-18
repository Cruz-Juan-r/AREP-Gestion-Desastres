# Resultados del experimento

Escenario: 120 s, 50 dispositivos a 1.0 Hz, caída del enlace entre 40 s y 80 s, latencia WAN 0±0 ms por sentido, pérdida de ACK inyectada 10%.

| Métrica | Solo nube | Edge |
|---|---:|---:|
| Lecturas generadas | 6000 | 6000 |
| Lecturas perdidas | 1999 (33.32 %) | 0 (0.0 %) |
| Recall | 0.583 | 1.0 |
| Detectados durante la caída | 2/12 | 12/12 |
| Precisión | 1.0 | 1.0 |
| Latencia p50 / p95 (ms) | 129.0 / 2615.1 | 8.0 / 35.7 |
| Duplicados almacenados | 0 | 0 |
| Reenvíos rechazados por idempotencia | - | 650 |
| ACK perdidos inyectados | - | 13 |
| Backlog máximo / vaciado | - | 2108 / 2.38 s |

Auditoría: cadena válida = True (147 entradas).
Prueba de alteración: se modificó la entrada 73; detectada = True (primera entrada inválida: 73).

## Carga (solo edge, sin caída)

Tasa ofrecida 500.0 msg/s, procesadas 20000/20000, procesamiento p50/p95/p99 = 0.094/0.194/0.384 ms, latencia de alerta p95 = 5.4 ms, recall = 1.0.
