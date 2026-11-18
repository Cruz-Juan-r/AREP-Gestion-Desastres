<#
.SYNOPSIS
  Stops (does not terminate) the EC2 instance tagged Name=arep-sngrd-cloud.

.DESCRIPTION
  The EBS volume, the cloud database and the Docker image stay on the instance; the next
  scripts\aws-up.ps1 starts it again. With -Local it also stops the local stack started from
  docker-compose.aws.yml (volumes are kept).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\aws-down.ps1
  powershell -ExecutionPolicy Bypass -File scripts\aws-down.ps1 -Local
#>
[CmdletBinding()]
param(
    [switch]$Local,
    [switch]$NoWait,
    [string]$Region = 'us-east-1',
    [string]$InstanceName = 'arep-sngrd-cloud'
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot

function Invoke-Aws {
    $ErrorActionPreference = 'Continue'
    $out = & aws @args --region $Region 2>&1
    if ($LASTEXITCODE -ne 0) { throw "aws $($args -join ' ') -> $(($out | Out-String).Trim())" }
    return ($out | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] })
}

if ($Local) {
    Push-Location $Root
    try {
        Write-Host '==> Deteniendo el stack local (docker-compose.aws.yml)' -ForegroundColor Cyan
        docker compose -f docker-compose.aws.yml --profile demo --profile experiment down
    } finally { Pop-Location }
}

Write-Host "==> Buscando la instancia $InstanceName" -ForegroundColor Cyan
try {
    $found = Invoke-Aws ec2 describe-instances `
        --filters "Name=tag:Name,Values=$InstanceName" "Name=instance-state-name,Values=pending,running" `
        --query 'Reservations[].Instances[].InstanceId' --output text
} catch {
    Write-Host 'No se pudo consultar AWS (credenciales vencidas?). Si la sesion del Learner Lab ya termino,' -ForegroundColor Yellow
    Write-Host 'la instancia ya fue detenida por AWS Academy.'
    exit 1
}
if (-not $found) { Write-Host '    No hay ninguna instancia encendida; nada que hacer.'; exit 0 }

foreach ($id in (@($found) -join ' ' -split '\s+' | Where-Object { $_ })) {
    Write-Host "==> Deteniendo $id (no se borra)" -ForegroundColor Cyan
    Invoke-Aws ec2 stop-instances --instance-ids $id | Out-Null
    if (-not $NoWait) {
        Invoke-Aws ec2 wait instance-stopped --instance-ids $id | Out-Null
        Write-Host "    $id detenida"
    }
}
