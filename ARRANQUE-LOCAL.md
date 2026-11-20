# Arranque local de EduSpace (Windows)

Cómo se levantó **todo** el sistema en esta máquina el **20/11/2026** y cómo volver a dejarlo **exactamente igual**.
Estado verificado ese día: **13 servicios Java UP + frontend + 20 contenedores Docker**.

---

## 1. Volver a levantar todo (un solo comando)

Desde la carpeta del proyecto (`D:\hrms\proyecto_Eduspace`), en PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\arrancar-wt.ps1
```

Qué hace, en orden:

1. **Docker Desktop**: si está apagado lo abre y espera a que responda.
2. **Contenedores**: enciende los `e2e-*` (si no existen, los crea), crea Kafka UI si falta y levanta Grafana/Prometheus/Loki.
3. **Compila** (`mvnw package -DskipTests`) solo los servicios que no tengan JAR en `target/`, **uno por uno**. En paralelo fallan:
   chocan en el repositorio de Maven.
4. **Abre las pestañas en Windows Terminal, una por una**, y espera a que cada servicio responda UP antes de abrir la siguiente.
   Si un servicio ya estaba UP, solo abre una pestaña con su log (no lo duplica).
5. Al final abre el **frontend**.

Tiempo: unos **2-3 minutos** si ya está compilado; la primera vez (clon nuevo), bastante más.

Solo los contenedores, sin servicios: `powershell -ExecutionPolicy Bypass -File .\arrancar-wt.ps1 -SoloDocker`

---

## 2. Qué queda levantado

### Ventanas de Windows Terminal

| Ventana | Pestañas (con nombre y color) |
|---|---|
| **Sistema** | `kafka`, `config`, `eureka`, `gateway`, `frontend` |
| **Microservicios** | `auth-ms`, `planificacion-ms`, `alumnos-ms`, `matricula-ms`, `asistencia-ms`, `calificaciones-ms`, `pago-ms`, `examenes-ms`, `tareas-ms`, `notificaciones-ms` |

### Servicios Java (orden de arranque)

| # | Servicio | Puerto | Carpeta | JAR | Espera a |
|---|---|---|---|---|---|
| 1 | config | 18888 | `infra/eduspace-config` | `eduspace-config-0.0.1-SNAPSHOT.jar` | — |
| 2 | eureka | 18761 | `infra/eduspace-eureka` | `eduspace-eureka-0.0.1-SNAPSHOT.jar` | config |
| 3 | auth-ms | 8090 | `services/auth-ms` | `auth-ms-1.0.0-SNAPSHOT.jar` | config, eureka |
| 4 | planificacion-ms | 8091 | `services/planificacion-ms` | `planificacion-ms-0.0.1-SNAPSHOT.jar` | auth-ms |
| 5 | alumnos-ms | 8092 | `services/alumnos-ms` | `alumnos-ms-0.0.1-SNAPSHOT.jar` | anterior |
| 6 | matricula-ms | 8093 | `services/matricula-ms` | `matricula-ms-1.0.0-SNAPSHOT.jar` | anterior |
| 7 | asistencia-ms | 8094 | `services/asistencia-ms` | `asistencia-ms-1.0.0-SNAPSHOT.jar` | anterior |
| 8 | calificaciones-ms | 8095 | `services/calificaciones-ms` | `calificaciones-ms-1.0.0-SNAPSHOT.jar` | anterior |
| 9 | pago-ms | 8096 | `services/pago-ms` | `pago-ms-1.0.0-SNAPSHOT.jar` | anterior |
| 10 | examenes-ms | 8097 | `services/examenes-ms` | `examenes-ms-1.0.0-SNAPSHOT.jar` | anterior |
| 11 | tareas-ms | 8098 | `services/tareas-ms` | `tareas-ms-1.0.0-SNAPSHOT.jar` | anterior |
| 12 | notificaciones-ms | 8099 | `services/notificaciones-ms` | `notificaciones-ms-0.0.1-SNAPSHOT.jar` | anterior |
| 13 | gateway | 18080 | `infra/eduspace-gateway` | `eduspace-gateway-0.0.1-SNAPSHOT.jar` | anterior |
| 14 | frontend (Angular) | 4200 | `frontend` | `npm start` (`ng serve`) | gateway |

**Cómo se ejecuta cada servicio Java** (lo hace `tools/tab-servicio.ps1`, desde la carpeta del servicio):

```powershell
$env:KAFKA_BOOTSTRAP_SERVERS = "localhost:19092"
$env:JWK_SET_URI             = "http://localhost:8090/.well-known/jwks.json"   # tokens de auth-ms (no Keycloak)
$env:CORS_ALLOWED_ORIGINS    = "http://localhost:4200,http://127.0.0.1:4200"
java -Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -jar target\<servicio>.jar
```

- Perfil: `dev` (por defecto). La configuración la sirve **config** desde `infra/eduspace-config/config-repo/<servicio>-dev.yml`.
- El servicio corre **en segundo plano** y escribe su log en `tools/logs/<servicio>.log`. La pestaña solo muestra ese log
  (`Get-Content -Wait`). Así **hacer clic o seleccionar texto en la terminal no congela el servicio**.
- **Cerrar una pestaña NO detiene el servicio.** Cada pestaña muestra su PID.
- **Frontend**: antes de `npm start` copia `public/config.eduspace.json` → `public/config.json` (login con auth-ms, no Keycloak)
  y hace `npm install` si falta `node_modules`.

**Comprobar que un servicio está UP**: `GET http://localhost:<puerto>/actuator/health` → **HTTP 200**.
Ojo: en Windows PowerShell 5.1 el cuerpo llega como bytes; se mira el **código HTTP**, no el texto "UP".

### Contenedores Docker

| Contenedor | Imagen | Puerto(s) | Para qué |
|---|---|---|---|
| `e2e-pg-auth` … `e2e-pg-notificaciones` (10) | `postgres:16-alpine` | 15440 … 15449 | Una base por servicio (ver tabla abajo) |
| `e2e-kafka` | `apache/kafka:4.3.1` | 19092 | Broker (KRaft). Los tópicos los crean los servicios productores |
| `e2e-kafka-ui` + `e2e-kafka-ui-proxy` | `ghcr.io/kafbat/kafka-ui:v1.5.0` + `alpine/socat:1.8.0.3` | 18085 | Kafka UI. Comparte la red de `e2e-kafka`; socat publica el puerto |
| `e2e-minio` | `cgr.dev/chainguard/minio:latest` | 19000 | Archivos (comprobantes PDF, documentos). `minioadmin` / `minioadmin` |
| `e2e-mailpit` | `axllent/mailpit:v1.21` | 11025 (SMTP), 18025 (web) | Correos de prueba |
| `eduspace-grafana` | `grafana/grafana:11.4.0` | 13000 | Paneles (`obs/compose-dev.yml`) |
| `eduspace-prometheus` | `prom/prometheus:v3.14.0` | 19090 | Métricas |
| `eduspace-loki` / `eduspace-promtail` | `grafana/loki:3.7.6` / `grafana/promtail:3.6.8` | 13100 / — | Logs |

`pagatu-postgres-catalogo-dev` (15432) también corre en esta máquina pero **es de otro proyecto**: no se toca.

**Bases de datos** (usuario `eduspace`, clave `eduspace`, host `localhost`):

| Base | Puerto | Base | Puerto |
|---|---|---|---|
| `auth_db` | 15440 | `calificaciones_db` | 15445 |
| `planificacion_db` | 15441 | `pago_db` | 15446 |
| `alumnos_db` | 15442 | `examenes_db` | 15447 |
| `matricula_db` | 15443 | `tareas_db` | 15448 |
| `asistencia_db` | 15444 | `notificaciones_db` | 15449 |

---

## 3. URLs y accesos

| Qué | URL | Acceso |
|---|---|---|
| **Frontend** | http://localhost:4200 | `admin` / `admin123` |
| Gateway (API) | http://localhost:18080 | token de auth-ms |
| Eureka | http://localhost:18761 | — |
| Kafka UI | http://localhost:18085 | — |
| Grafana | http://localhost:13000 | `admin` / `admin` |
| Prometheus | http://localhost:19090 | — |
| Mailpit (correos) | http://localhost:18025 | — |
| Swagger de un servicio | http://localhost:&lt;puerto&gt;/swagger-ui.html | — |

Login por API: `POST http://localhost:8090/api/v1/auth/login` con `{"username":"admin","password":"admin123"}` → campo `access_token`.

---

## 4. Configuración local que NO está en git

| Archivo | Contenido |
|---|---|
| `services/pago-ms/.env` | Credenciales de **Mercado Pago (cuenta REAL)**: `MERCADOPAGO_ACCESS_TOKEN`, `MERCADOPAGO_PUBLIC_KEY`, `PAGO_FRONTEND_URL`. pago-ms lo lee al arrancar (`optional:file:./.env`). En un clon nuevo hay que crearlo. |
| `tools/logs/` | Logs de cada servicio (se reescriben en cada arranque) |
| `tools/respaldos/` | Respaldos de bases y PDFs de prueba |

Mercado Pago: con la cuenta real **los cobros son de verdad**. Mínimo S/ 1.00 (Yape y tarjeta), S/ 10.00 (PagoEfectivo).

---

## 5. Operaciones frecuentes

**Reiniciar un solo servicio después de cambiar código** (ejemplo: pago-ms, puerto 8096):

```powershell
# 1) detener el proceso y cerrar su pestaña
Get-NetTCPConnection -LocalPort 8096 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
# 2) recompilar (con el servicio detenido: si no, el JAR está bloqueado)
cd services\pago-ms; .\mvnw.cmd -q -DskipTests package; cd ..\..
# 3) abrir de nuevo su pestaña
wt -w Microservicios new-tab --title pago-ms --suppressApplicationTitle powershell -NoExit -ExecutionPolicy Bypass `
   -File .\tools\tab-servicio.ps1 -Titulo pago-ms -Dir services\pago-ms -Modo java
```

**Apagar todos los servicios Java**: `Get-Process java | Stop-Process` (cuidado: también cierra el Java de VS Code).
**Apagar contenedores**: `docker stop $(docker ps -q --filter name=e2e-)` y `docker compose -f obs\compose-dev.yml stop`.

---

## 6. Problemas que ya aparecieron (y su arreglo)

| Síntoma | Causa | Arreglo aplicado |
|---|---|---|
| Todo se quedaba "esperando a config" | Compilar los 13 servicios a la vez hacía fallar el JAR de config | El script compila **en secuencia** antes de abrir pestañas |
| El script nunca veía "UP" | PowerShell 5.1 devuelve `/actuator/health` como bytes | Se comprueba el código **HTTP 200** |
| Un servicio se congelaba en su pestaña | Hacer clic o seleccionar texto en la terminal pausa la salida | El servicio escribe a un log; la pestaña solo lo muestra |
| Pestañas sin nombre | PowerShell sobrescribía el título | `--title` + `--suppressApplicationTitle` + color por pestaña |
| `python` no encontrado | Solo está el alias de la Microsoft Store | El script crea los contenedores con `docker run` (sin Python) |
| `docker` no responde | Docker Desktop apagado | El script lo abre y espera |
| `mvnw package` falla con "Unable to rename … .jar" | El servicio está corriendo y bloquea el JAR | Detenerlo antes de recompilar |
