param(
  [string]$Action,
  [string]$Out = "",
  [int]$ParentPid = 0,
  [string]$RunId = ""
)
# Move or close a specific Ephemera dev/bench Minecraft window.
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class WinMove {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr a, int x, int y, int cx, int cy, uint f);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
}
"@
[WinMove]::SetProcessDPIAware() | Out-Null

$allProcs = Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId, Name, CommandLine
$parentMap = @{}
foreach ($proc in $allProcs) {
  $parentMap[[int]$proc.ProcessId] = [int]$proc.ParentProcessId
}

function Test-IsDescendant([int]$PidToCheck, [int]$AncestorPid) {
  if ($AncestorPid -le 0) { return $true }
  $cur = $PidToCheck
  $seen = @{}
  while ($cur -gt 0 -and -not $seen.ContainsKey($cur)) {
    if ($cur -eq $AncestorPid) { return $true }
    $seen[$cur] = $true
    if (-not $parentMap.ContainsKey($cur)) { break }
    $cur = $parentMap[$cur]
  }
  return $false
}

$runToken = if ($RunId) { "-Dephemera.bench.runId=$RunId" } else { "" }

$ids = $allProcs |
  Where-Object {
    ($_.Name -eq 'java.exe' -or $_.Name -eq 'javaw.exe') -and
    ($_.CommandLine -like '*ephemera*loom-cache*launch.cfg*') -and
    (-not $runToken -or ($_.CommandLine -like "*$runToken*")) -and
    (Test-IsDescendant ([int]$_.ProcessId) $ParentPid)
  } |
  ForEach-Object { [int]$_.ProcessId }

if (-not $ids) { Write-Output "NOWINDOW"; exit 1 }

$p = Get-Process -Id $ids -ErrorAction SilentlyContinue |
  Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero -and $_.MainWindowTitle -like 'Minecraft*' } |
  Select-Object -First 1

if (-not $p) { Write-Output "NOWINDOW"; exit 1 }
$h = $p.MainWindowHandle
if ($h -eq [IntPtr]::Zero) { Write-Output "NOWINDOW"; exit 1 }

if ($Action -eq "move") {
  Add-Type -AssemblyName System.Windows.Forms
  $scr = [System.Windows.Forms.Screen]::AllScreens | Where-Object { -not $_.Primary } | Select-Object -First 1
  $wr = New-Object WinMove+RECT
  if (-not [WinMove]::GetWindowRect($h, [ref]$wr)) {
    Write-Output "MOVE_FAILED pid=$($p.Id) reason=GetWindowRect"
    exit 2
  }
  $w = $wr.R - $wr.L; $hh = $wr.B - $wr.T
  if ($w -le 0 -or $hh -le 0) {
    Write-Output "MOVE_FAILED pid=$($p.Id) reason=EmptyRect"
    exit 2
  }
  if ($scr) {
    $b = $scr.WorkingArea
    if ($w -gt $b.Width - 20) { $hh = [int]($hh * ($b.Width - 20) / $w); $w = $b.Width - 20 }
    $X = $b.X + [int](($b.Width - $w) / 2)
    $Y = $b.Y + [int](($b.Height - $hh) / 2)
  } else {
    $X = 100; $Y = 100
  }
  $ok = [WinMove]::SetWindowPos($h, [IntPtr]::Zero, $X, $Y, $w, $hh, 0x0004 -bor 0x0010)
  if (-not $ok) {
    Write-Output "MOVE_FAILED pid=$($p.Id) reason=SetWindowPos"
    exit 2
  }
  Write-Output "MOVED pid=$($p.Id)"
} elseif ($Action -eq "close") {
  $ok = $p.CloseMainWindow()
  if (-not $ok) {
    Write-Output "CLOSE_FAILED pid=$($p.Id)"
    exit 2
  }
  Write-Output "CLOSED pid=$($p.Id)"
} else {
  Write-Output "UNKNOWN_ACTION $Action"
  exit 2
}
