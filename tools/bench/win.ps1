param([string]$Action, [string]$Out = "")
# Move Ephemera's dev/bench Minecraft window to the secondary (portrait) monitor.
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
$ids = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
  Where-Object { $_.CommandLine -like '*ephemera*loom-cache*launch.cfg*' } |
  ForEach-Object { $_.ProcessId }
$p = Get-Process -Id $ids -ErrorAction SilentlyContinue |
  Where-Object { $_.MainWindowTitle -like 'Minecraft*' } |
  Select-Object -First 1
if (-not $p) { Write-Output "NOWINDOW"; exit 1 }
$h = $p.MainWindowHandle
if ($Action -eq "move") {
  Add-Type -AssemblyName System.Windows.Forms
  $scr = [System.Windows.Forms.Screen]::AllScreens | Where-Object { -not $_.Primary } | Select-Object -First 1
  $wr = New-Object WinMove+RECT; [WinMove]::GetWindowRect($h, [ref]$wr) | Out-Null
  $w = $wr.R - $wr.L; $hh = $wr.B - $wr.T
  if ($scr) {
    $b = $scr.WorkingArea
    if ($w -gt $b.Width - 20) { $hh = [int]($hh * ($b.Width - 20) / $w); $w = $b.Width - 20 }
    $X = $b.X + [int](($b.Width - $w) / 2)
    $Y = $b.Y + [int](($b.Height - $hh) / 2)
  } else {
    $X = 100; $Y = 100
  }
  [WinMove]::SetWindowPos($h, [IntPtr]::Zero, $X, $Y, $w, $hh, 0x0004 -bor 0x0010) | Out-Null
  Write-Output "MOVED"
} elseif ($Action -eq "close") {
  $p.CloseMainWindow() | Out-Null
  Write-Output "CLOSED"
}
