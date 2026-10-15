param([string]$Out, [int]$Port = 3000, [int]$Seconds = 1500)
$ErrorActionPreference = 'SilentlyContinue'
$procId = (Get-NetTCPConnection -LocalPort $Port -State Listen | Select-Object -First 1).OwningProcess
$cores = [Environment]::ProcessorCount
"time,pid,cpu_pct_of_machine,ws_mb,threads,sys_cpu_pct" | Out-File $Out -Encoding utf8
$prev = (Get-Process -Id $procId).TotalProcessorTime.TotalMilliseconds
$prevT = Get-Date
for ($i = 0; $i -lt $Seconds; $i++) {
  Start-Sleep -Milliseconds 1000
  $p = Get-Process -Id $procId
  if (-not $p) { break }
  $now = Get-Date
  $cpu = $p.TotalProcessorTime.TotalMilliseconds
  $pct = [math]::Round((($cpu - $prev) / (($now - $prevT).TotalMilliseconds)) / $cores * 100, 1)
  $sys = [math]::Round((Get-Counter '\Processor(_Total)\% Processor Time' -MaxSamples 1).CounterSamples[0].CookedValue, 1)
  "{0},{1},{2},{3},{4},{5}" -f $now.ToString('HH:mm:ss'), $procId, $pct, [math]::Round($p.WorkingSet64/1MB,0), $p.Threads.Count, $sys | Out-File $Out -Append -Encoding utf8
  $prev = $cpu; $prevT = $now
}
