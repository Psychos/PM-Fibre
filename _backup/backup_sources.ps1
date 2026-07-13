# Sauvegarde des sources E:\PM vers le NAS PCTV (\\192.168.1.19\Backup\pm-sources)
# 1) Miroir "current" (robocopy /MIR, ne copie que les changements)
# 2) Si quelque chose a changé -> instantané zip horodaté (rotation 40)
# Lancé par le Planificateur de tâches (toutes les heures + à l'ouverture de session).

$src     = 'E:\PM'
$nasRoot = '\\192.168.1.19\Backup\pm-sources'
$current = Join-Path $nasRoot 'current'
$snapDir = Join-Path $nasRoot 'snapshots'
$log     = Join-Path $nasRoot 'backup_sources.log'

function Log($msg) { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $msg" | Add-Content -Path $log }

try {
    New-Item -ItemType Directory -Force -Path $current, $snapDir | Out-Null
} catch {
    exit 1   # NAS injoignable : on réessaiera à la prochaine exécution
}

# Miroir. Exclusions : outillage, caches et sorties de build (regénérables, volumineux).
$xd = @("$src\_tools", "$src\.gradle", "$src\app\build", "$src\build", "$src\.kotlin", "$src\_backup\tmp")
robocopy $src $current /MIR /R:1 /W:2 /NFL /NDL /NP /XD $xd /XF *.tmp | Out-Null
$rc = $LASTEXITCODE

if ($rc -ge 8) { Log "ERREUR robocopy rc=$rc"; exit 1 }

if ($rc -band 7) {
    # Des fichiers ont été ajoutés/modifiés/supprimés -> instantané
    $stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
    $zip = Join-Path $snapDir "pm_sources_$stamp.zip"
    try {
        Compress-Archive -Path "$current\*" -DestinationPath $zip -CompressionLevel Optimal -ErrorAction Stop
        Log "OK modifs detectees (rc=$rc) -> snapshot $(Split-Path $zip -Leaf) ($([math]::Round((Get-Item $zip).Length/1MB,1)) Mo)"
    } catch {
        Log "ERREUR snapshot : $($_.Exception.Message)"
    }
    # Rotation : garder les 40 derniers instantanés
    Get-ChildItem $snapDir -Filter 'pm_sources_*.zip' | Sort-Object Name -Descending |
        Select-Object -Skip 40 | Remove-Item -Force -Confirm:$false
} else {
    Log "OK aucun changement (rc=$rc)"
}
