# Arranca EduSpace en Windows Terminal:
#   Ventana "Microservicios" -> 10 pestanas (auth + 9 de negocio)
#   Ventana "Sistema"        -> config, eureka, gateway, frontend
# Uso: powershell -ExecutionPolicy Bypass -File .\arrancar-wt.ps1

$raiz = Split-Path -Parent $MyInvocation.MyCommand.Path
$tab  = Join-Path $raiz "tools\tab-servicio.ps1"

# 0. Docker Desktop + contenedores (10 Postgres, Kafka, MinIO, Mailpit)
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Iniciando Docker Desktop..." -ForegroundColor Cyan
    Start-Process "$env:LOCALAPPDATA\Programs\DockerDesktop\Docker Desktop.exe"
    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Seconds 2
        docker info *> $null
        if ($LASTEXITCODE -eq 0) { break }
    }
    if ($LASTEXITCODE -ne 0) { Write-Error "Docker no arranco."; exit 1 }
}
$contenedores = docker ps -a -q --filter "name=e2e-"
if ($contenedores) {
    Write-Host "Encendiendo contenedores e2e-*..." -ForegroundColor Cyan
    # Kafka UI comparte la red del contenedor de Kafka: se enciende al final
    $ui    = docker ps -a -q --filter "name=e2e-kafka-ui"
    $resto = $contenedores | Where-Object { $_ -notin $ui }
    docker start $resto | Out-Null
    if ($ui) { docker start e2e-kafka-ui e2e-kafka-ui-proxy | Out-Null }
} else {
    Write-Host "Creando contenedores e2e-* por primera vez..." -ForegroundColor Cyan
    $kp = 19092
    docker run -d --name e2e-kafka -p "${kp}:${kp}" `
        -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller `
        -e "KAFKA_LISTENERS=INTERNAL://0.0.0.0:9092,EXTERNAL://0.0.0.0:${kp},CONTROLLER://0.0.0.0:9093" `
        -e "KAFKA_ADVERTISED_LISTENERS=INTERNAL://localhost:9092,EXTERNAL://localhost:${kp}" `
        -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT `
        -e KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER `
        -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 `
        -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 `
        -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=false `
        -e "KAFKA_HEAP_OPTS=-Xmx384m -Xms256m" apache/kafka:4.3.1
    docker run -d --name e2e-minio -p 19000:9000 -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin `
        cgr.dev/chainguard/minio:latest server /data
    docker run -d --name e2e-mailpit -p 11025:1025 -p 18025:8025 axllent/mailpit:v1.21
    $dbs = [ordered]@{ auth=15440; planificacion=15441; alumnos=15442; matricula=15443; asistencia=15444;
                       calificaciones=15445; pago=15446; examenes=15447; tareas=15448; notificaciones=15449 }
    foreach ($n in $dbs.Keys) {
        docker run -d --name "e2e-pg-$n" -p "$($dbs[$n]):5432" -e "POSTGRES_DB=${n}_db" `
            -e POSTGRES_USER=eduspace -e POSTGRES_PASSWORD=eduspace -e TZ=America/Lima `
            postgres:16-alpine -c shared_buffers=32MB -c max_connections=60
    }
    Start-Sleep -Seconds 10
}

if ($args -contains "-SoloDocker") { exit 0 }

# Orden de arranque: [ventana, titulo, carpeta, puerto]
$orden = @(
    @("Sistema",        "config",            "infra\eduspace-config",       18888),
    @("Sistema",        "eureka",            "infra\eduspace-eureka",       18761),
    @("Microservicios", "auth-ms",           "services\auth-ms",            8090),
    @("Microservicios", "planificacion-ms",  "services\planificacion-ms",   8091),
    @("Microservicios", "alumnos-ms",        "services\alumnos-ms",         8092),
    @("Microservicios", "matricula-ms",      "services\matricula-ms",       8093),
    @("Microservicios", "asistencia-ms",     "services\asistencia-ms",      8094),
    @("Microservicios", "calificaciones-ms", "services\calificaciones-ms",  8095),
    @("Microservicios", "pago-ms",           "services\pago-ms",            8096),
    @("Microservicios", "examenes-ms",       "services\examenes-ms",        8097),
    @("Microservicios", "tareas-ms",         "services\tareas-ms",          8098),
    @("Microservicios", "notificaciones-ms", "services\notificaciones-ms",  8099),
    @("Sistema",        "gateway",           "infra\eduspace-gateway",      18080)
)

# 1. Compilar (una sola vez, en secuencia) lo que no tenga JAR
foreach ($s in $orden) {
    $dir = Join-Path $raiz $s[2]
    $jar = Get-ChildItem "$dir\target\*.jar" -ErrorAction SilentlyContinue | Where-Object Name -notmatch 'original'
    if (-not $jar) {
        Write-Host "Compilando $($s[1])..." -ForegroundColor Cyan
        Push-Location $dir; & .\mvnw.cmd -q -DskipTests package; Pop-Location
    }
}
if (-not (Test-Path "$raiz\frontend\node_modules")) {
    Write-Host "Instalando dependencias del frontend..." -ForegroundColor Cyan
    npm.cmd --prefix "$raiz\frontend" install
}

# /actuator/health responde 200 si esta UP (503 si esta DOWN). En PowerShell 5.1 el cuerpo
# llega como bytes, por eso se mira el codigo HTTP y no el texto.
function Test-Up($puerto) {
    try { return (Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:$puerto/actuator/health" -TimeoutSec 2).StatusCode -eq 200 }
    catch { return $false }
}

function Esperar-Up($puerto, $nombre) {
    Write-Host "  esperando a $nombre (:$puerto)..." -NoNewline
    for ($i = 0; $i -lt 90; $i++) {
        if (Test-Up $puerto) { Write-Host " UP" -ForegroundColor Green; return $true }
        Start-Sleep -Seconds 2
    }
    Write-Host " NO RESPONDIO (revisa su pestana)" -ForegroundColor Red; return $false
}

$colores = @{
    "kafka"="#212529"; "config"="#1E6FD9"; "eureka"="#7B3FE4"; "gateway"="#D9480F"; "frontend"="#C2255C"
    "auth-ms"="#E03131"; "planificacion-ms"="#2F9E44"; "alumnos-ms"="#1098AD"; "matricula-ms"="#F08C00"
    "asistencia-ms"="#5C7CFA"; "calificaciones-ms"="#AE3EC9"; "pago-ms"="#37B24D"; "examenes-ms"="#E8590C"
    "tareas-ms"="#0CA678"; "notificaciones-ms"="#F59F00"
}

function Nueva-Pestana($ventana, $titulo, $dir, $modo = "java") {
    $color = $colores[$titulo]; if (-not $color) { $color = "#868E96" }
    wt -w $ventana new-tab --title "$titulo" --tabColor "$color" --suppressApplicationTitle `
        powershell -NoExit -ExecutionPolicy Bypass -File $tab `
        -Titulo $titulo -Dir $dir -Modo $modo
    Start-Sleep -Milliseconds 800
}

# 2. Pestana de Kafka (broker en Docker)
Nueva-Pestana "Sistema" "kafka" "kafka" "kafka"

# 3. Arrancar uno por uno: abre la pestana y espera a que este UP antes de seguir
foreach ($s in $orden) {
    Write-Host "[$($s[1])]" -ForegroundColor Cyan
    if (Test-Up $s[3]) {
        Write-Host "  ya estaba UP (abro pestana con su log)" -ForegroundColor Green
        Nueva-Pestana $s[0] $s[1] $s[2] "ver"
        continue
    }
    Nueva-Pestana $s[0] $s[1] $s[2]
    Esperar-Up $s[3] $s[1] | Out-Null
}

# 3. Frontend
Write-Host "[frontend]" -ForegroundColor Cyan
Nueva-Pestana "Sistema" "frontend" "frontend" "frontend"

Write-Host ""
Write-Host "Pestanas abiertas. Cuando todo este UP: http://localhost:4200 (admin / admin123)" -ForegroundColor Green
