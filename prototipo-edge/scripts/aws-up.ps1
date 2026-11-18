<#
.SYNOPSIS
  Starts the hybrid deployment: national cloud (cloud-api) on EC2, edge stack on this laptop.

.DESCRIPTION
  Learner Lab stops instances when the session ends and the public IP changes on every start,
  so this script is meant to be the only thing to run each day:
    1. checks the AWS CLI credentials (Learner Lab tokens expire with the session);
    2. finds the instance tagged Name=arep-sngrd-cloud and starts it if it is stopped;
    3. points the SSH (22) and cloud-api (8000) rules of its security group to the current
       public IP of this laptop;
    4. waits until http://<ip>:8000/health answers;
    5. writes CLOUD_HOST=<ip> to .env.aws (never versioned);
    6. stops the all-local stack (docker-compose.yml) and starts docker-compose.aws.yml.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\aws-up.ps1
  powershell -ExecutionPolicy Bypass -File scripts\aws-up.ps1 -Demo      # also starts the simulator
  powershell -ExecutionPolicy Bypass -File scripts\aws-up.ps1 -NoStack   # only the AWS side
#>
[CmdletBinding()]
param(
    [switch]$Demo,
    [switch]$NoStack,
    [string]$Region = 'us-east-1',
    [string]$InstanceName = 'arep-sngrd-cloud',
    [int]$HealthTimeoutSec = 300
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot          # prototipo-edge/
$EnvFile = Join-Path $Root '.env.aws'

function Step([string]$msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

# Runs the AWS CLI and returns stdout; throws with the CLI message on failure.
function Invoke-Aws {
    $ErrorActionPreference = 'Continue'           # native stderr must not abort here
    $out = & aws @args --region $Region 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "aws $($args -join ' ') -> $(($out | Out-String).Trim())"
    }
    return ($out | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] })
}

# ---------------------------------------------------------------- 1. credentials
Step 'Verificando credenciales de AWS'
try {
    $arn = Invoke-Aws sts get-caller-identity --query Arn --output text
    Write-Host "    $arn"
} catch {
    Write-Host ''
    Write-Host 'Las credenciales de AWS no son validas o expiraron.' -ForegroundColor Red
    Write-Host 'En AWS Academy: Start Lab -> AWS Details -> AWS CLI: Show, y copie el bloque [default]'
    Write-Host "completo en $HOME\.aws\credentials. Luego vuelva a ejecutar este script."
    exit 1
}

# ---------------------------------------------------------------- 2. instance
Step "Buscando la instancia $InstanceName"
$found = Invoke-Aws ec2 describe-instances `
    --filters "Name=tag:Name,Values=$InstanceName" "Name=instance-state-name,Values=pending,running,stopping,stopped" `
    --query 'Reservations[].Instances[].[InstanceId,State.Name]' --output text
if (-not $found) { throw "No existe una instancia con la etiqueta Name=$InstanceName (ver docs/aws/despliegue-aws.md)." }
$id, $state = (@($found)[0] -split '\s+')
Write-Host "    $id ($state)"

if ($state -eq 'stopping') {
    Step 'La instancia se esta deteniendo; esperando a que termine'
    Invoke-Aws ec2 wait instance-stopped --instance-ids $id | Out-Null
    $state = 'stopped'
}
if ($state -eq 'stopped') {
    Step 'Encendiendo la instancia'
    Invoke-Aws ec2 start-instances --instance-ids $id | Out-Null
}
Invoke-Aws ec2 wait instance-running --instance-ids $id | Out-Null
$ip, $sg = (Invoke-Aws ec2 describe-instances --instance-ids $id `
    --query 'Reservations[0].Instances[0].[PublicIpAddress,SecurityGroups[0].GroupId]' --output text) -split '\s+'
if (-not $ip -or $ip -eq 'None') { throw "La instancia $id no tiene IP publica." }
Write-Host "    IP publica: $ip   security group: $sg"

# ---------------------------------------------------------------- 3. security group
Step 'Actualizando el security group con la IP actual del portatil'
$myIp = (Invoke-RestMethod -Uri 'https://checkip.amazonaws.com' -TimeoutSec 10).Trim()
$myCidr = "$myIp/32"
Write-Host "    IP del portatil: $myIp"
$rulesJson = Invoke-Aws ec2 describe-security-group-rules --filters "Name=group-id,Values=$sg" --output json
$rules = (($rulesJson | Out-String) | ConvertFrom-Json).SecurityGroupRules |
    Where-Object { -not $_.IsEgress -and $_.IpProtocol -eq 'tcp' -and ($_.FromPort -eq 22 -or $_.FromPort -eq 8000) }
foreach ($port in 22, 8000) {
    $forPort = @($rules | Where-Object { $_.FromPort -eq $port })
    $stale = @($forPort | Where-Object { $_.CidrIpv4 -ne $myCidr })
    if ($stale.Count -gt 0) {
        Invoke-Aws ec2 revoke-security-group-ingress --group-id $sg `
            --security-group-rule-ids ($stale | ForEach-Object { $_.SecurityGroupRuleId }) | Out-Null
        Write-Host "    puerto ${port}: revocado $(($stale | ForEach-Object { $_.CidrIpv4 }) -join ', ')"
    }
    if (-not ($forPort | Where-Object { $_.CidrIpv4 -eq $myCidr })) {
        $desc = if ($port -eq 22) { 'ssh-portatil' } else { 'cloud-api-portatil' }
        Invoke-Aws ec2 authorize-security-group-ingress --group-id $sg `
            --ip-permissions "IpProtocol=tcp,FromPort=$port,ToPort=$port,IpRanges=[{CidrIp=$myCidr,Description=$desc}]" | Out-Null
        Write-Host "    puerto ${port}: permitido $myCidr"
    } else {
        Write-Host "    puerto ${port}: ya permitido para $myCidr"
    }
}

# ---------------------------------------------------------------- 4. health
Step "Esperando a que responda http://${ip}:8000/health"
$deadline = (Get-Date).AddSeconds($HealthTimeoutSec)
$healthy = $false
while ((Get-Date) -lt $deadline) {
    try {
        $h = Invoke-RestMethod -Uri "http://${ip}:8000/health" -TimeoutSec 5
        if ($h.status -eq 'ok') { $healthy = $true; break }
    } catch { Start-Sleep -Seconds 5 }
}
if (-not $healthy) {
    throw "cloud-api no respondio en $HealthTimeoutSec s. Revise por SSH: docker ps -a; docker logs arep-sngrd-cloud"
}
Write-Host '    cloud-api OK'

# ---------------------------------------------------------------- 5. .env.aws
Step "Escribiendo $EnvFile"
# ASCII without BOM: docker compose env_file must not start with a BOM.
[IO.File]::WriteAllText($EnvFile, "# Generado por scripts/aws-up.ps1 - no versionar`nCLOUD_HOST=$ip`n", [Text.Encoding]::ASCII)

if ($NoStack) {
    Write-Host ''
    Write-Host "Listo (solo AWS). Nube: http://${ip}:8000/docs   Instancia: $id" -ForegroundColor Green
    exit 0
}

# ---------------------------------------------------------------- 6. local stack
Push-Location $Root
try {
    Step 'Deteniendo el stack 100 % local (docker-compose.yml), si estaba arriba'
    docker compose -f docker-compose.yml --profile demo --profile experiment down
    Step 'Levantando broker, wan, edge con docker-compose.aws.yml'
    docker compose -f docker-compose.aws.yml up -d --build
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up fallo' }
    # wan reads CLOUD_HOST at start: recreate it so a new EC2 IP is always picked up.
    docker compose -f docker-compose.aws.yml up -d --no-deps --force-recreate wan
    if ($Demo) {
        Step 'Arrancando el simulador de demo (10 min)'
        docker compose -f docker-compose.aws.yml --profile demo up -d simulator
    }
} finally {
    Pop-Location
}

Write-Host ''
Write-Host 'Despliegue hibrido arriba' -ForegroundColor Green
Write-Host "  Instancia EC2 : $id ($Region)"
Write-Host "  Nube (AWS)    : http://${ip}:8000/docs   stats: http://${ip}:8000/api/v1/stats"
Write-Host '  Tablero edge  : http://localhost:8080'
Write-Host '  Cortar enlace : curl.exe -X POST "http://localhost:9100/link?up=0"   (restablecer: up=1)'
Write-Host '  Apagar la EC2 : powershell -ExecutionPolicy Bypass -File scripts\aws-down.ps1'
