# Despliegue híbrido en AWS: nube en EC2, edge en el portátil

Proyecto AREP — *Sistema Nacional/Regional de Gestión de Respuesta a Desastres*.

En la versión 100 % local (`docker-compose.yml`) el enlace entre el nodo edge y la
nube es un proxy que *emula* la WAN dentro del mismo equipo. En este despliegue la
**API de la nube (`cloud-api`) corre en una instancia EC2** del AWS Academy Learner
Lab y el **portátil hace de puesto de mando unificado (PMU) municipal**. El enlace
edge → nube atraviesa internet de verdad hasta `us-east-1`.

## 1. Arquitectura del despliegue

```
 Portátil (PMU municipal, Docker Desktop)                       AWS us-east-1 (Learner Lab)
┌──────────────────────────────────────────────────┐          ┌──────────────────────────────────┐
│ simulator ──MQTT──► broker (Mosquitto :1883)       │          │ EC2 arep-sngrd-cloud (t3.medium) │
│                         │                          │          │ Amazon Linux 2023, Docker        │
│                         ▼                          │ internet │                                  │
│ tablero :8080 ◄── edge (Spring Boot) ──HTTP──► wan ─┼─────────►│ :8000 cloud-api (Spring Boot)    │
│                   SQLite + outbox     :9000  (proxy │  TCP     │       SQLite en volumen Docker   │
│                                       :9100 control)│          │       arep-sngrd-cloud-data      │
│ experiment ─────────── admin/stats directo ─────────┼─────────►│                                  │
└──────────────────────────────────────────────────┘          └──────────────────────────────────┘
                                                     SG arep-sngrd-sg: 22 y 8000 solo desde la IP del portátil
```

| Componente | Dónde corre | Cómo |
|---|---|---|
| `cloud-api` (nube nacional) | **AWS EC2** | Contenedor `arep-sngrd-cloud`, construido en la instancia con `cloud-api/Dockerfile`, `--restart unless-stopped`, `CLOUD_DB=/data/cloud.db` en el volumen `arep-sngrd-cloud-data`, puerto 8000 |
| `edge` (nodo edge del PMU) | Portátil | `docker-compose.aws.yml`, igual que en local (`CLOUD_URL=http://wan:9000`) |
| `broker` (Mosquitto) | Portátil | `docker-compose.aws.yml` |
| `wan` (proxy del enlace) | Portátil | Reenvía a `CLOUD_HOST:8000` con latencia y jitter **emulados en 0** (la latencia ya es real). Se conserva para poder **cortar el enlace** en la demo |
| `simulator`, `experiment` | Portátil | Perfiles `demo` y `experiment` de `docker-compose.aws.yml` |

La IP pública de la EC2 cambia cada vez que se enciende. `scripts/aws-up.ps1` la
escribe en `.env.aws` (`CLOUD_HOST=<ip>`); `wan` y `experiment` la leen con
`env_file` y la expanden dentro del contenedor, así que no hace falta `--env-file`.
`.env.aws`, el `.pem` y las credenciales están en `.gitignore` y no se versionan.

## 2. Recursos creados en AWS

Todos con el prefijo `arep-sngrd`, en la cuenta del Learner Lab, región `us-east-1`.

| Recurso | Identificador | Detalle |
|---|---|---|
| Par de llaves | `arep-sngrd-key` | RSA; la llave privada está en `%USERPROFILE%\.ssh\arep-sngrd-key.pem` (fuera del proyecto, permisos solo para el usuario) |
| Security group | `sg-09ca75979c1bd9ee2` (`arep-sngrd-sg`) | VPC por defecto `vpc-072de015b097a466f`. Entrada: TCP 22 (`ssh-portatil`) y TCP 8000 (`cloud-api-portatil`) **solo desde la IP pública del portátil** (/32). `aws-up.ps1` la actualiza si cambia |
| Instancia EC2 | `i-01de46ad186b054b8` (`Name=arep-sngrd-cloud`) | Amazon Linux 2023 (`ami-00f43ee0e98d52529`), **t3.medium**, subred por defecto de `us-east-1a`, IP pública automática (sin Elastic IP), perfil `LabInstanceProfile` (rol `LabRole`), disco gp3 de 20 GB |

En la instancia: Docker 25 (paquete de AL2023), plugin `buildx` v0.39.0 (release
oficial de `docker/buildx`, checksum SHA-256 verificado; lo requiere el
`RUN --mount=type=cache` del Dockerfile) y el código mínimo para construir la nube en
`~/arep`: `pom.xml`, `mvnw`, `.mvn/`, `shared/`, `cloud-api/` y `edge-node/pom.xml`
(solo el pom: el `pom.xml` raíz declara ese módulo y Maven lo necesita para armar el
reactor, aunque no se compile).

No se crearon balanceadores, NAT, RDS ni Elastic IP. Costo aproximado, descontado del
crédito del lab: t3.medium ≈ US$0,042/h encendida y gp3 20 GB ≈ US$1,6/mes.

## 3. Levantarlo otro día

AWS Academy apaga la instancia al terminar la sesión del laboratorio (4 h) y las
credenciales caducan; el disco, la imagen Docker y la base de la nube se conservan.

1. En AWS Academy: **Start Lab** y esperar el indicador verde.
2. **AWS Details → AWS CLI: Show**, copiar el bloque `[default]` completo y pegarlo en
   `%USERPROFILE%\.aws\credentials` (reemplazando el anterior).
3. Abrir Docker Desktop.
4. Desde la raíz del repositorio:

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\aws-up.ps1 -Demo
   ```

   El script verifica las credenciales, enciende `arep-sngrd-cloud`, actualiza el
   security group con la IP actual del portátil, espera `/health`, escribe `.env.aws`,
   detiene el stack 100 % local si estaba arriba y levanta `docker-compose.aws.yml`
   (con `-Demo` también el simulador). Al final imprime la IP y las URLs.
   `-NoStack` hace solo la parte de AWS.

5. Al terminar:

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\aws-down.ps1 -Local
   ```

   Detiene la instancia (no la borra) y, con `-Local`, también el stack local.

URLs con el sistema arriba:

- Tablero del edge (COP local): http://localhost:8080
- Nube en AWS: `http://<IP>:8000/docs` (Swagger) y `http://<IP>:8000/api/v1/stats`
- La IP vigente está en `.env.aws` y la imprime `aws-up.ps1`.

## 4. Cortar el enlace en la demo

```powershell
curl.exe -X POST "http://localhost:9100/link?up=0"     # se cae el enlace municipio -> nube
curl.exe -X POST "http://localhost:9100/link?up=1"     # se restablece
curl.exe http://localhost:9100/state
```

Qué se ve en el tablero (capturas 04 a 06):

- **Enlace arriba:** píldora "enlace nube: arriba", pendientes en decenas.
- **Enlace cortado:** "caído (operando offline)"; las alertas siguen llegando en
  milisegundos y los pendientes crecen (≈ 50 lecturas/s). En la nube,
  `events_stored` deja de crecer.
- **Restablecido:** en unos 9 s (espera del *backoff* más el vaciado) los pendientes
  vuelven a decenas y la nube en AWS tiene todas las lecturas, sin duplicados
  (`events_stored` = `events_distinct`).

Nota: en el tablero, las filas que llegan en vivo por SSE se pintan con
"Sincronizada: no" y no se actualizan solas; al recargar la página muestran el estado
real (así funcionaba también el tablero original).

## 5. Resultados del experimento contra AWS

```powershell
docker compose -f docker-compose.aws.yml --profile experiment run --rm experiment
```

Mismo experimento y mismo escenario (120 s, 50 dispositivos a 1 Hz, 24 episodios,
caída entre 40 y 80 s, 10 % de ACK perdidos, carga de 500 msg/s), con estos cambios
solo de argumentos: `--cloud http://$CLOUD_HOST:8000`, `--latency 0 --jitter 0`
(latencia real de internet) y `--out /app/results-aws`. `tamper_test()` copia la base
del edge a la ruta fija `/app/results/`; en este compose esa ruta es un `tmpfs`, así
que `results/` del proyecto no se toca.

Comparación entre la corrida local (`results/`, WAN emulada de 200 ± 80 ms por
sentido) y la corrida contra AWS (`results-aws/`, internet real Colombia → us-east-1):

| Métrica | Local: solo nube | **AWS: solo nube** | Local: edge | **AWS: edge** |
|---|---:|---:|---:|---:|
| Lecturas generadas | 6 000 | 6 000 | 6 000 | 6 000 |
| Lecturas perdidas | 2 009 (33,5 %) | **1 999 (33,3 %)** | 0 | **0** |
| Recall (episodios detectados) | 0,583 (14/24) | **0,583 (14/24)** | 1,0 (24/24) | **1,0 (24/24)** |
| Detectados durante la caída | 2/12 | **2/12** | 12/12 | **12/12** |
| Precisión | 1,0 | **1,0** | 1,0 | **1,0** |
| Latencia de alerta p50 / p95 (ms) | 380 / 2 414 | **129 / 2 615** | 10,5 / 42 | **8 / 36** |
| Latencia con enlace arriba p50 / p95 (ms) | 371 / 445 | **129 / 311** | — | — |
| Duplicados almacenados en la nube | 0 | **0** | 0 | **0** |
| Reenvíos rechazados por idempotencia | — | — | 502 | **650** |
| ACK perdidos inyectados | — | — | 10 | **13** |
| Backlog máximo / vaciado tras reconectar | — | — | 2 433 / 9,7 s | **2 108 / 2,4 s** |
| Auditoría | — | — | íntegra (116); alteración detectada | **íntegra (147); alteración detectada** |
| Carga 500 msg/s: procesamiento p95 / alerta p95 | — | — | 0,26 / 6,4 ms | **0,19 / 5,4 ms** |

Lectura de los resultados:

- **Las conclusiones se mantienen con una nube real.** Sin edge se pierde un tercio
  de las lecturas (todo lo producido durante la caída) y solo se detectan 2 de los 12
  episodios ocurridos en ella; con edge, 0 pérdidas, 24/24 episodios, 0 duplicados y
  auditoría íntegra.
- **La latencia real hacia AWS es menor que la emulada.** La línea base solo nube
  paga ≈ 129 ms de mediana (ida y vuelta Colombia → us-east-1 más procesamiento),
  frente a ≈ 370 ms con la WAN emulada de 200 ms por sentido. Aun así es un orden de
  magnitud más que el edge (8 ms), y su p95 sigue dominado por los episodios
  detectados tarde al volver el enlace.
- **El vaciado de 2,4 s no es una mejora del sistema, sino del momento.** El agente
  reintenta con *backoff* exponencial (0,5 → 8 s); en esta corrida el primer reintento
  exitoso llegó 1,7 s después de la reconexión, frente a 8,1 s en la corrida local.
  Depende de en qué punto de la espera estaba el agente al volver el enlace. El
  hallazgo de H4 (el tope del backoff domina la convergencia) sigue vigente.
- El número de reenvíos y ACK perdidos varía entre corridas porque la pérdida de ACK
  es aleatoria (10 %).

Archivos: `results-aws/results.json`, `summary.md`, `tabla_resultados.tex`,
`fig_latencia_alertas.png` y `fig_linea_tiempo.png`.

## 6. Evidencias

En `docs/aws/evidencias/`:

| Archivo | Qué muestra |
|---|---|
| `01-ec2-instancia-en-ejecucion.jpg` | Consola EC2: `arep-sngrd-cloud` en ejecución, t3.medium, IP pública, rol `LabRole` |
| `02-security-group-reglas-entrada.jpg` | `arep-sngrd-sg`: entrada 22 y 8000 solo desde la IP del portátil (/32) |
| `03-swagger-cloud-api-en-aws.jpg` | Swagger UI de `cloud-api` servido desde la EC2 |
| `04-tablero-edge-enlace-arriba.jpg` | Tablero del edge con el enlace a AWS arriba |
| `05-tablero-edge-enlace-cortado.jpg` | Enlace cortado: operando offline, backlog creciendo, alertas en tiempo real |
| `06-tablero-edge-enlace-restablecido.jpg` | Enlace restablecido: backlog vaciado, auditoría íntegra |

## 7. Solución de problemas

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| `aws-up.ps1` dice que las credenciales no son válidas | La sesión del lab terminó o se copió un bloque incompleto | Start Lab y copiar de nuevo el bloque `[default]` completo |
| `/health` no responde | La IP del portátil cambió y el SG no se actualizó, o el contenedor no arrancó | Volver a ejecutar `aws-up.ps1`; por SSH: `docker ps -a` y `docker logs arep-sngrd-cloud` |
| Puertos 1883/8080/9000 ocupados | El stack 100 % local sigue arriba | `docker compose -f docker-compose.yml down` (el script ya lo hace) |
| `.ps1` bloqueado por la política de ejecución | Política por defecto de Windows | Usar `powershell -ExecutionPolicy Bypass -File ...` |

Conexión SSH a la instancia (con la IP de `.env.aws`):

```powershell
ssh -i $HOME\.ssh\arep-sngrd-key.pem ec2-user@<IP>
```

Para actualizar la nube tras cambiar el código de `cloud-api`, copiar de nuevo los
mismos archivos a `~/arep` y repetir en la instancia:

```bash
cd ~/arep && sudo docker build -f cloud-api/Dockerfile -t arep-sngrd/cloud-api .
sudo docker rm -f arep-sngrd-cloud
sudo docker run -d --name arep-sngrd-cloud --restart unless-stopped -p 8000:8000 \
  -e CLOUD_DB=/data/cloud.db -v arep-sngrd-cloud-data:/data arep-sngrd/cloud-api
```
