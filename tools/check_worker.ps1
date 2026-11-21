param(
  [Parameter(Mandatory=$true)][string]$Worker,
  [string[]]$CoreDirs = @(),
  [string[]]$MainDirs = @(),
  [string[]]$ClientDirs = @()
)
$ErrorActionPreference = "Stop"
function Expand-Dirs([string[]]$dirs) {
  $res = @()
  foreach ($d in $dirs) {
    if ($d) {
      foreach ($part in ($d -split ',')) {
        $p = $part.Trim()
        if ($p) { $res += $p }
      }
    }
  }
  return $res
}
$CoreDirs = Expand-Dirs $CoreDirs
$MainDirs = Expand-Dirs $MainDirs
$ClientDirs = Expand-Dirs $ClientDirs

$outCore = "build\check-$Worker\core"
$outMain = "build\check-$Worker\main"
$outClient = "build\check-$Worker\client"
Remove-Item -Recurse -Force "build\check-$Worker" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $outCore,$outMain,$outClient | Out-Null

$coreCp = (Get-Content "build\core-cp.txt" -Raw).Trim()
$clientCp = (Get-Content "build\client-cp.txt" -Raw).Trim()
$totalCompiled = 0

if ($CoreDirs.Count -gt 0) {
  $files = $CoreDirs | ForEach-Object { if (Test-Path $_) { (Get-ChildItem $_ -Recurse -Filter "*.java").FullName } } | Where-Object { $_ }
  if ($files) {
    $totalCompiled += @($files).Count
    Write-Output "Compiling $(@($files).Count) core Java files..."
    javac --release 25 -encoding UTF-8 -cp "$coreCp" -d $outCore $files
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  }
}

$extraCp = "$outCore;$clientCp"
if ($MainDirs.Count -gt 0) {
  $files = $MainDirs | ForEach-Object { if (Test-Path $_) { (Get-ChildItem $_ -Recurse -Filter "*.java").FullName } } | Where-Object { $_ }
  if ($files) {
    $totalCompiled += @($files).Count
    Write-Output "Compiling $(@($files).Count) main Java files..."
    javac --release 25 -encoding UTF-8 -cp "$extraCp" -d $outMain $files
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  }
}

$extraCp2 = "$outCore;$outMain;$clientCp"
if ($ClientDirs.Count -gt 0) {
  $files = $ClientDirs | ForEach-Object { if (Test-Path $_) { (Get-ChildItem $_ -Recurse -Filter "*.java").FullName } } | Where-Object { $_ }
  if ($files) {
    $totalCompiled += @($files).Count
    Write-Output "Compiling $(@($files).Count) client Java files..."
    javac --release 25 -encoding UTF-8 -cp "$extraCp2" -d $outClient $files
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  }
}
if ($totalCompiled -eq 0) {
  Write-Error "No Java files matched for $Worker!"
  exit 1
}
Write-Output "CHECK OK ($Worker) - compiled $totalCompiled files"
