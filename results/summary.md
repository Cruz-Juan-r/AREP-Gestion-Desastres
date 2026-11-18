# Resultados del experimento

Escenario: 120 s, 50 dispositivos a 1.0 Hz, caída del enlace entre 40 s y 80 s, latencia WAN 200±80 ms por sentido, pérdida de ACK inyectada 10%.

| Métrica | Solo nube | Edge |
|---|---:|---:|
| Lecturas generadas | 6000 | 6000 |
| Lecturas perdidas | 2009 (33.48 %) | 0 (0.0 %) |
| Recall | 0.583 | 1.0 |
| Detectados durante la caída | 2/12 | 12/12 |
| Precisión | 1.0 | 1.0 |
| Latencia p50 / p95 (ms) | 379.5 / 2414.4 | 10.5 / 41.7 |
| Duplicados almacenados | 0 | 0 |
| Reenvíos rechazados por idempotencia | - | 502 |
| ACK perdidos inyectados | - | 10 |
| Backlog máximo / vaciado | - | 2433 / 9.66 s |

Auditoría: cadena válida = True (116 entradas).
Prueba de alteración: se modificó la entrada 58; detectada = True (primera entrada inválida: 58).

## Carga (solo edge, sin caída)

Tasa ofrecida 500.0 msg/s, procesadas 20000/20000, procesamiento p50/p95/p99 = 0.105/0.257/0.515 ms, latencia de alerta p95 = 6.4 ms, recall = 1.0.
