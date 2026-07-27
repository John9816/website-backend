param(
  [Parameter(Mandatory=$true)][string]$HostName,
  [string]$User = "root",
  [string]$KeyPath = "",
  [int]$Port = 22,
  [switch]$SkipBuild
)
$ErrorActionPreference = "Stop"
$BackendRoot = "D:\Projects\IDEAproject\website"
$FrontendRoot = "D:\Projects\VSCode\website"
$Out = Join-Path $BackendRoot "target\ecs-deploy"
if (!$SkipBuild) {
  Push-Location $BackendRoot
  & "$BackendRoot\.codex-compile\tools\apache-maven-3.9.6\bin\mvn.cmd" -q -DskipTests package
  Pop-Location
  Push-Location $FrontendRoot
  & npm.cmd run build
  Pop-Location
}
Remove-Item -Recurse -Force $Out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Out | Out-Null
Copy-Item "$BackendRoot\target\website-0.0.1-SNAPSHOT.jar" "$Out\website.jar" -Force
Copy-Item "$BackendRoot\deploy\ecs\website-api.service" "$Out\website-api.service" -Force
Copy-Item "$BackendRoot\deploy\ecs\install.sh" "$Out\install.sh" -Force
Copy-Item "$FrontendRoot\dist" "$Out\dist" -Recurse -Force
$Archive = Join-Path $BackendRoot "target\website-ecs-deploy.tar.gz"
Remove-Item $Archive -Force -ErrorAction SilentlyContinue
Push-Location $Out
& tar.exe -czf $Archive .
Pop-Location
$sshArgs = @()
if ($KeyPath) { $sshArgs += @("-i", $KeyPath) }
$sshArgs += @("-p", "$Port")
$target = "$User@$HostName"
# scp uses uppercase -P for the port; ssh uses lowercase -p.
$scpArgs = @()
if ($KeyPath) { $scpArgs += @("-i", $KeyPath) }
$scpArgs += @("-P", "$Port")
& scp.exe @scpArgs $Archive "${target}:/tmp/website-ecs-deploy.tar.gz"
if ($LASTEXITCODE -ne 0) { throw "SCP upload failed with exit code $LASTEXITCODE" }
& ssh.exe @sshArgs $target "mkdir -p /tmp/website-ecs-deploy && tar -xzf /tmp/website-ecs-deploy.tar.gz -C /tmp/website-ecs-deploy && cd /tmp/website-ecs-deploy && sudo bash install.sh"
if ($LASTEXITCODE -ne 0) { throw "Remote deployment failed with exit code $LASTEXITCODE" }
Write-Host "Deployed. Check: ssh $target 'systemctl status website-api --no-pager'"
