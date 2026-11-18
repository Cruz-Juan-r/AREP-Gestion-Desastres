# Prototipo: nodo edge de alertas en tiempo real con conectividad intermitente

Proyecto AREP — *Sistema Nacional/Regional de Gestión de Respuesta a Desastres
Naturales y Asignación Resiliente de Recursos*.
Juan Esteban Cruz, Deisy Lorena Guzmán, Sebastián Villarraga.

El prototipo implementa el **nodo edge del puesto de mando unificado (PMU)**
del diagrama de despliegue C4. Responde esta pregunta: ¿puede un nodo edge
mantener la detección y las alertas en tiempo real mientras el enlace con la
nube está caído, sin perder lecturas, y sincronizar al reconectarse sin
duplicados ni pérdida de trazabilidad?

Los dos servicios reales del sistema, el **nodo edge** y la **API de la nube**,
están en **Java 21 con Spring Boot 3**. Las herramientas de prueba (simulador de
dispositivos, emulador del enlace WAN y experimento) siguen en Python y hablan
con los servicios solo por sus contratos: MQTT, HTTP/JSON y el archivo SQLite
del edge.

## Qué se construye y qué se simula

| Componente | Tratamiento | Dónde |
|---|---|---|
| Broker MQTT (QoS 1) | Construido (Mosquitto / amqtt) | `docker-compose.yml` |
| Ingesta MQTT, validación y deduplicación | Construido (Java) | `edge-node/…/mqtt/MqttIngestor.java`, `edge-node/…/node/EdgeNode.java` |
| Detector (umbrales + z-score móvil) | Construido (Java) | `shared/…/detect/Detector.java` |
| Almacén local con patrón outbox (SQLite WAL) | Construido (Java) | `edge-node/…/store/EdgeStore.java` |
| Agente de sincronización (lotes, backoff, ACK) | Construido (Java) | `edge-node/…/sync/SyncAgent.java` |
| Auditoría append-only encadenada con SHA-256 | Construido (Java) | `edge-node/…/store/AuditLog.java` |
| COP local (tablero con SSE) | Construido, mínimo | `edge-node/…/sse/AlertBroadcaster.java`, `edge-node/src/main/resources/static/index.html` |
| API central de la nube (sync idempotente + línea base) | Construida, mínima (Java) | `cloud-api/…/CloudController.java`, `CloudService.java` |
| Sensores, pluviómetros, acelerómetros, drones | **Simulados** (protocolo real: MQTT) | `simulator/simulator.py` |
| Enlace WAN (latencia, jitter, cortes) | **Emulado** con un proxy TCP | `wan/proxy.py` |
| Pérdida de ACK | **Inyectada** en la nube | `FAULT_ACK_LOSS` / `POST /admin/fault` |
| Optimización, IA sobre imágenes, GIS, interoperabilidad SNIGRD | Fuera del prototipo | — |

Los contratos están en `contracts/`: JSON Schema de cada mensaje, OpenAPI de
la nube y los tópicos MQTT. La migración a Java conservó los mismos contratos,
tópicos, endpoints y JSON de respuesta que la versión Python anterior.

## Tecnologías

| Pieza | edge-node | cloud-api |
|---|---|---|
| Lenguaje y framework | Java 21, Spring Boot 3.5.16 (Spring MVC sobre Tomcat) | Java 21, Spring Boot 3.5.16 (Spring MVC sobre Tomcat) |
| Construcción | Maven multimódulo con wrapper (`mvnw`, Maven 3.9.12) | ídem |
| Mensajería | Eclipse Paho MQTT v3 1.2.5 (`MqttAsyncClient`, MQTT 3.1.1, sesión persistente, QoS 1) | — |
| Persistencia | SQLite con `org.xerial:sqlite-jdbc` (versión gestionada por Spring Boot), Spring JDBC (`JdbcTemplate` + `TransactionTemplate`), HikariCP con 1 conexión | ídem |
| Cliente HTTP hacia la nube | `java.net.http.HttpClient` (forzado a HTTP/1.1) | — |
| Tiempo real hacia el tablero | Server-Sent Events con `SseEmitter` | — |
| JSON | Jackson (records en snake_case) y un serializador propio compatible con `json.dumps` de Python para la cadena de auditoría | Jackson |
| Documentación de la API | — | springdoc-openapi 2.8.17: Swagger UI en `/docs` y OpenAPI en `/openapi.json` |
| Pruebas | JUnit 5, Spring Boot Test (MockMvc) | ídem |

## Estructura

```
pom.xml, mvnw, mvnw.cmd, .mvn/   proyecto Maven padre (Spring Boot 3.5.16, Java 21)
shared/      contratos Java (records + validación), detector y JSON compatible con Python
edge-node/   nodo edge (Spring Boot): MQTT, almacén outbox, sincronización, auditoría, SSE, API y tablero
cloud-api/   API central de la nube (Spring Boot): sync idempotente + línea base
common/      modelos y verificador de auditoría en Python (los usan simulator/ y experiment/)
wan/         emulador del enlace WAN (proxy TCP con latencia y cortes)        [Python]
simulator/   dispositivos de campo y escenario con verdad de terreno          [Python]
experiment/  experimento, métricas, figuras y tabla LaTeX                     [Python]
contracts/   JSON Schema, OpenAPI y tópicos MQTT
docker/      configuración de Mosquitto
docs/        diagrama de contenedores del prototipo (Graphviz) y despliegue en AWS (docs/aws/)
scripts/     arranque local sin Docker (run_local_stack.py) y despliegue en AWS (aws-up.ps1, aws-down.ps1)
results-aws/ resultados del experimento con la nube en AWS
docker-compose.aws.yml           stack del portátil cuando la nube corre en EC2
Dockerfile.tools                 imagen Python de las herramientas de prueba
edge-node/Dockerfile, cloud-api/Dockerfile   imágenes Java (construcción multietapa)
```

## Ejecutar

### Opción A: Docker (recomendada)

```bash
docker compose up --build -d                                # broker, edge, wan, cloud
docker compose --profile demo up -d simulator               # tráfico de demo (10 min)
```

- Tablero del nodo edge (COP local): http://localhost:8080
- API de la nube (Swagger): http://localhost:8000/docs

Para simular la caída del enlace en vivo durante la demo (en PowerShell use
`curl.exe` en lugar de `curl`):

```bash
curl -X POST "http://localhost:9100/link?up=0"   # se cae el enlace
curl -X POST "http://localhost:9100/link?up=1"   # vuelve
curl http://localhost:9100/state
```

Con el enlace caído, el tablero sigue mostrando alertas y el contador de
pendientes crece. Al reconectar, el contador baja a casi cero y la nube queda
con todas las lecturas.

### Opción B: sin Docker (Java 21 + Python 3.10+)

```powershell
.\mvnw.cmd -DskipTests package          # genera edge-node/target/edge-node.jar y cloud-api/target/cloud-api.jar
pip install -r requirements.txt
python scripts/run_local_stack.py       # deja corriendo; Ctrl+C para detener
```

En Linux/macOS use `./mvnw` en lugar de `.\mvnw.cmd`. Los servicios también se
pueden arrancar desde IntelliJ: abra `pom.xml` como proyecto y ejecute
`EdgeNodeApplication` y `CloudApiApplication`. Las variables de entorno son las
mismas de la versión anterior: `MQTT_HOST`, `MQTT_PORT`, `CLOUD_URL`, `EDGE_DB`
y `NODE_ID` para el edge; `CLOUD_DB` y `FAULT_ACK_LOSS` para la nube.

### Opción C: nube en AWS (EC2) y edge en el portátil

La API de la nube corre en una instancia EC2 del AWS Academy Learner Lab
(`arep-sngrd-cloud`) y el portátil hace de puesto de mando municipal: broker, edge,
emulador WAN y simulador siguen en Docker local, y el enlace edge → nube pasa por
internet real. Detalle completo, recursos creados y resultados en
[`docs/aws/despliegue-aws.md`](docs/aws/despliegue-aws.md).

Cada día de uso (con el lab iniciado y las credenciales del lab copiadas en
`%USERPROFILE%\.aws\credentials`):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\aws-up.ps1 -Demo   # enciende la EC2, escribe .env.aws y levanta docker-compose.aws.yml
docker compose -f docker-compose.aws.yml --profile experiment run --rm experiment   # experimento -> results-aws/
powershell -ExecutionPolicy Bypass -File scripts\aws-down.ps1 -Local                # apaga la EC2 (sin borrarla) y el stack local
```

`docker-compose.aws.yml` no levanta `cloud`; `wan` reenvía a `CLOUD_HOST:8000` (la IP
de la EC2, que `aws-up.ps1` escribe en `.env.aws`) con latencia emulada en 0, y sigue
permitiendo cortar el enlace con `POST http://localhost:9100/link?up=0`. El stack 100 %
local y el de AWS usan los mismos puertos, así que no corren a la vez (`aws-up.ps1`
detiene el local). `.env.aws`, el `.pem` y las credenciales no se versionan.

## Pruebas unitarias

```powershell
.\mvnw.cmd verify
```

| Test | Qué verifica |
|---|---|
| `shared/…/DetectorTest` | Umbrales por tipo de sensor, z-score (ventana 60, mínimo 20 muestras, \|z\| ≥ 5), enfriamiento de 10 s por dispositivo, que las lecturas anómalas no entren a la ventana y redondeo igual a `round()` de Python |
| `shared/…/PyJsonTest`, `ContractsTest` | JSON byte a byte igual a `json.dumps` de Python y validación del contrato `Telemetry` |
| `edge-node/…/AuditLogTest` | Cadena desde el génesis, verificación íntegra, y detección de una entrada alterada, de un hash recalculado y de una entrada borrada |
| `edge-node/…/PythonAuditCompatibilityTest` | Genera la auditoría desde Java y la verifica con `common/auditlog.verify()` de Python, la misma función que usa el experimento; también aplica la alteración del experimento y comprueba que se detecta. Requiere Python 3 en el `PATH` (o la variable `PYTHON`); sin él, el test se omite |
| `edge-node/…/EdgeStoreTest` | Outbox: deduplicación por `event_id`, lectura + alerta + auditoría en una sola transacción, rollback completo, marcado de sincronizados |
| `edge-node/…/EdgeApiTest` | JSON de `/metrics`, `/health`, `/alerts/recent` y `/audit/verify`, con las mismas claves y orden que la versión Python |
| `cloud-api/…/SyncIdempotencyTest` | Reenvío del mismo lote, ACK perdido (se confirma la escritura y responde 503) seguido de reenvío, lotes solapados, idempotencia de `/api/v1/ingest` y errores 400/422 |

Los Dockerfiles compilan con `-DskipTests`. Las pruebas se corren en el equipo
de desarrollo porque una de ellas necesita Python.

## Repetir el experimento

Compara el nodo edge contra una línea base solo nube en el mismo escenario:
120 s, 50 dispositivos a 1 Hz, 24 episodios anómalos con verdad de terreno,
caída del enlace entre los segundos 40 y 80, latencia WAN de 200±80 ms por
sentido y 10 % de ACK perdidos. Además corre una prueba de carga de
500 mensajes por segundo y una prueba de alteración de la auditoría.
Tarda unos 6 minutos.

```bash
# Docker
docker compose --profile experiment run --rm experiment
# Sin Docker (con el stack local corriendo)
python -m experiment.run_experiment
```

Salida en `results/`: `results.json` (todo), `summary.md`,
`tabla_resultados.tex` (la usa el documento), `fig_latencia_alertas.png` y
`fig_linea_tiempo.png`.

## Resultados de referencia (corrida incluida en `results/`)

| Métrica | Solo nube | Edge |
|---|---:|---:|
| Lecturas perdidas | 2 009 de 6 000 (33,5 %) | 0 |
| Episodios detectados | 14/24 | 24/24 |
| Detectados durante la caída | 2/12 | 12/12 |
| Falsas alarmas | 0 | 0 |
| Latencia de alerta p50 / p95 | 380 / 2 414 ms | 10,5 / 42 ms (máx. 47 ms) |
| Duplicados almacenados en la nube | 0 | 0 (502 reenvíos rechazados; 10 ACK perdidos inyectados) |
| Vaciado del backlog tras reconectar | — | 9,7 s (backlog máximo 2 433 lecturas; unos 8,1 s de espera del backoff) |
| Auditoría | — | íntegra (116 entradas); alteración de la entrada 58 detectada |
| Carga 500 msg/s | — | 20 000/20 000, procesamiento p95 0,26 ms, latencia de alerta p95 6,4 ms |

Medido en un equipo Windows 11 con Docker Desktop: edge y nube en Spring Boot
(JVM 21) y broker Mosquitto, todos en contenedores. La pérdida de ACK es
aleatoria (10 %), así que el número de reenvíos cambia de una corrida a otra.
Lo que no debe cambiar son las conclusiones: 0 lecturas perdidas, 24/24
episodios, 0 duplicados almacenados y la auditoría íntegra.

Comparada con la corrida de la versión Python (Linux, procesos locales,
broker amqtt), la latencia del edge subió de 2/23 ms a 10,5/42 ms (p50/p95). Sigue
dos órdenes de magnitud por debajo de la línea base y muy lejos del umbral de
1 s de H1. Las demás conclusiones no cambian.
