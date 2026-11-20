# Ejecuta UN componente de EduSpace dentro de una pestana de Windows Terminal.
# Lo invoca arrancar-wt.ps1; no hace falta llamarlo a mano.
#   -Dir      carpeta del modulo (relativa a la raiz)
#   -Esperar  puertos (separados por coma) que deben estar UP antes de arrancar
#   -Modo     'java' (por defecto) o 'frontend'
param(
    [string]$Titulo,
    [string]$Dir,
    [string]$Esperar = "",
    [string]$Modo = "java"
)

$raiz = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Host.UI.RawUI.WindowTitle = $Titulo
Set-Location (Join-Path $raiz $Dir)

# Encabezado grande para saber en que pestana estas
$linea = "=" * 60
Write-Host $linea -ForegroundColor Cyan
Write-Host ("   " + $Titulo.ToUpper()) -ForegroundColor Black -BackgroundColor Cyan
Write-Host $linea -ForegroundColor Cyan

$logs = Join-Path $raiz "tools\logs"; New-Item -ItemType Directory -Force $logs | Out-Null
$log  = Join-Path $logs "$Titulo.log"

# Modo "kafka": topicos + log en vivo del broker (contenedor e2e-kafka)
if ($Modo -eq "kafka") {
    Write-Host "Broker: localhost:19092   (contenedor e2e-kafka)" -ForegroundColor Green
    Write-Host "`nTopicos:" -ForegroundColor Yellow
    docker exec e2e-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
    Write-Host "`nLog del broker (en vivo):" -ForegroundColor Yellow
    docker logs -f --tail 30 e2e-kafka
    return
}

# Modo "ver": el servicio ya esta corriendo; solo se muestra su log
if ($Modo -eq "ver") {
    Write-Host "Ya estaba corriendo. Mostrando su log: $log" -ForegroundColor Green
    Get-Content $log -Wait -Tail 50
    return
}

function Esperar-Up([int]$puerto) {
    Write-Host "Esperando a :$puerto ..." -ForegroundColor Yellow
    while ($true) {
        try {
            $r = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:$puerto/actuator/health" -TimeoutSec 2
            if ($r.StatusCode -eq 200) { Write-Host ":$puerto UP" -ForegroundColor Green; return }
        } catch { }
        Start-Sleep -Seconds 3
    }
}

if ($Modo -eq "frontend") {
    if (-not (Test-Path node_modules)) {
        Write-Host "Instalando dependencias npm..." -ForegroundColor Cyan
        npm.cmd install
    }
    Copy-Item public\config.eduspace.json public\config.json -Force
    foreach ($p in ($Esperar -split ',' | Where-Object { $_ })) { Esperar-Up $p }
    npm.cmd start
    return
}

function Buscar-Jar { Get-ChildItem target\*.jar -ErrorAction SilentlyContinue | Where-Object Name -notmatch 'original' | Select-Object -First 1 }

$jar = Buscar-Jar
if (-not $jar) {
    Write-Host "No hay JAR: compilando $Titulo (mvnw package -DskipTests)..." -ForegroundColor Cyan
    & .\mvnw.cmd -q -DskipTests package
    $jar = Buscar-Jar
    if (-not $jar) { Write-Host "ERROR: la compilacion de $Titulo fallo." -ForegroundColor Red; return }
}

foreach ($p in ($Esperar -split ',' | Where-Object { $_ })) { Esperar-Up $p }

$env:KAFKA_BOOTSTRAP_SERVERS = "localhost:19092"
$env:JWK_SET_URI             = "http://localhost:8090/.well-known/jwks.json"
$env:CORS_ALLOWED_ORIGINS    = "http://localhost:4200,http://127.0.0.1:4200"

# El servicio escribe a un archivo y la pestana solo lo muestra: asi, si se hace clic
# o se selecciona texto en la terminal, el servicio NO se congela.
Write-Host "Arrancando $Titulo -> $($jar.Name)  (log: $log)" -ForegroundColor Green
$proc = Start-Process java -WindowStyle Hidden -PassThru `
    -ArgumentList "-Xmx256m","-XX:+UseSerialGC","-XX:TieredStopAtLevel=1","-jar","`"$($jar.FullName)`"" `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err"
Write-Host "PID $($proc.Id). Ctrl+C cierra solo el visor; para detener: Stop-Process -Id $($proc.Id)" -ForegroundColor DarkGray
Start-Sleep -Seconds 1
Get-Content $log -Wait -Tail 50
