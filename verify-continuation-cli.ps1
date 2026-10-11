[CmdletBinding()]
param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$utf8 = [System.Text.UTF8Encoding]::new($false)
$runRoot = Join-Path $PSScriptRoot ('runs\continuation-cli-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$checks = [System.Collections.Generic.List[object]]::new()
$calls = [System.Collections.Generic.List[object]]::new()
$failure = $null
$runtime = & (Join-Path $PSScriptRoot 'runtime.ps1')
$javaTool = $runtime.javaTool
$classPath = $runtime.classPath
function Check([string]$Name, [bool]$Passed) {
    $checks.Add([pscustomobject]@{name=$Name;passed=$Passed})
    if (!$Passed) { throw "检查失败：$Name" }
}
function Fact([object]$View, [string]$Id) { @($View.facts | Where-Object id -EQ $Id)[0] }
function Known([object]$View, [string]$Id) { @($View.knowledge | Where-Object factId -EQ $Id)[0] }
function Invoke-Engine([string]$Name, [string[]]$Arguments, [switch]$Reject) {
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $javaTool; $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = $utf8; $start.StandardErrorEncoding = $utf8
    foreach ($argument in @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', $classPath, 'creativeworld.WorldCli') + $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [System.Diagnostics.Process]::Start($start)
    $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit(); $out = $stdout.GetAwaiter().GetResult(); $err = $stderr.GetAwaiter().GetResult()
    $outPath = Join-Path $runRoot "$Name.stdout.json"; $errPath = Join-Path $runRoot "$Name.stderr.txt"
    [System.IO.File]::WriteAllText($outPath, $out, $utf8); [System.IO.File]::WriteAllText($errPath, $err, $utf8)
    $calls.Add([pscustomobject]@{name=$Name;arguments=$Arguments;exitCode=$process.ExitCode;expectedRejection=[bool]$Reject;stdout=$outPath;stderr=$errPath})
    if ($Reject) {
        Check "$Name 明确拒绝" ($process.ExitCode -ne 0 -and ($err | ConvertFrom-Json).error.message.Length -gt 0)
        return
    }
    if ($process.ExitCode -ne 0) { throw "命令失败：$Name；诊断已保存" }
    return ($out | ConvertFrom-Json)
}
try {
    if (!$SkipBuild) {
        $output = & (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly
        [System.IO.File]::WriteAllText((Join-Path $runRoot 'compile.stdout.txt'), ($output -join [Environment]::NewLine), $utf8)
        Check 'Java独立编译完成' ($LASTEXITCODE -eq 0)
    }
    $source = Join-Path $PSScriptRoot 'examples\continuation'
    foreach ($scene in @('river', 'workshop')) {
        $world = Join-Path $runRoot "$scene-world.json"; $episode = Join-Path $runRoot "$scene-episode"
        $request = Join-Path $source "$scene-request.json"
        $null = Invoke-Engine "$scene-init" @('init', (Join-Path $source "$scene-seed.json"), $world)
        $paused = Invoke-Engine "$scene-pause" @('continue', $world, $request, $episode, '1')
        Check "$scene 第一步后明确暂停" ($paused.status -eq 'paused' -and $paused.revision -eq 1 -and $paused.pending.Count -gt 0)
        $done = Invoke-Engine "$scene-continue" @('continue', $world, $request, $episode, '32')
        Check "$scene 两个人物连续衔接" ($done.status -eq 'quiescent' -and $done.revision -eq 5 -and $done.attempts.Count -eq 6)
        Check "$scene 办理者在下一次移动前回应" ($done.attempts[2].activityId -eq 'process' -and $done.attempts[2].receipt.revision -eq 3)
        Check "$scene 核实目标有实际满足结果" ($done.outcomes.inspect.status -eq 'satisfied' -and $done.pending.Count -eq 0)
        $author = Invoke-Engine "$scene-author" @('view', $world, '@author')
        $who = if ($scene -eq 'river') { 'apprentice' } else { 'resident' }
        $idle = if ($scene -eq 'river') { 'smith' } else { 'volunteer' }
        $state = if ($scene -eq 'river') { 'handoff-state' } else { 'equipment-available' }
        $value = if ($scene -eq 'river') { 'registered' } else { $true }
        $actor = Invoke-Engine "$scene-actor" @('view', $world, $who)
        $unaware = Invoke-Engine "$scene-idle" @('view', $world, $idle)
        $history = Invoke-Engine "$scene-history" @('history', $world)
        Check "$scene 历史只有五笔正式事件" ($history.events.Count -eq 5 -and $history.events[1].submission.module -eq 'message-state-response' -and $history.events[2].submission.module -eq 'message-state-response')
        Check "$scene 办理结果正确" ((Fact $author $state).value -eq $value)
        Check "$scene 本人到场核实而非仅接收转述" ((Known $actor $state).value -eq $value -and (Known $actor $state).source -eq '@world' -and (Known $actor $state).revision -eq 5)
        Check "$scene 未参与者没有自动获知" (@($unaware.knowledge).Count -eq 0)
        Check "$scene 后续许可保持未批准" ((Fact $author 'further-permission').value -eq $false)
        Check "$scene 资源没有被暗中消耗" ((Fact $author 'resource-quantity').value -eq $(if ($scene -eq 'river') { 40 } else { 4 }))
        $old = Invoke-Engine "$scene-old-author" @('view-at', $world, '@author', '0')
        Check "$scene 旧状态和共同时间保留" ((Fact $old $state).value -ne $value -and $old.worldTime.year -eq 100 -and $author.worldTime.year -eq 100)
        $hash = (Get-FileHash -LiteralPath $world -Algorithm SHA256).Hash
        $again = Invoke-Engine "$scene-repeat" @('continue', $world, $request, $episode, '1')
        Check "$scene 重开得到同一份结果" (($again | ConvertTo-Json -Depth 100 -Compress) -eq ($done | ConvertTo-Json -Depth 100 -Compress))
        Check "$scene 重复运行世界字节不变" ($hash -eq (Get-FileHash -LiteralPath $world -Algorithm SHA256).Hash)
        $changed = Get-Content -LiteralPath $request -Raw -Encoding UTF8 | ConvertFrom-Json; $changed.onKnowledge = @()
        $changedPath = Join-Path $runRoot "$scene-changed-request.json"
        [System.IO.File]::WriteAllText($changedPath, ($changed | ConvertTo-Json -Depth 100), $utf8)
        Invoke-Engine "$scene-changed-config" @('continue', $world, $changedPath, $episode, '32') -Reject
        Check "$scene 更换配置拒绝且世界保持" ($hash -eq (Get-FileHash -LiteralPath $world -Algorithm SHA256).Hash)
    }
    $seed = Get-Content -LiteralPath (Join-Path $source 'river-seed.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $seed.characters[1].privateProfile.PSObject.Properties.Remove('responsePolicy')
    $seedPath = Join-Path $runRoot 'missing-policy-seed.json'
    [System.IO.File]::WriteAllText($seedPath, ($seed | ConvertTo-Json -Depth 100), $utf8)
    $world = Join-Path $runRoot 'missing-policy-world.json'
    $null = Invoke-Engine 'missing-policy-init' @('init', $seedPath, $world)
    $report = Invoke-Engine 'missing-policy-continue' @('continue', $world, (Join-Path $source 'river-request.json'), (Join-Path $runRoot 'missing-policy-episode'), '32')
    $author = Invoke-Engine 'missing-policy-author' @('view', $world, '@author')
    Check '没有待执行活动不等于办理成功' ($report.status -eq 'quiescent' -and $report.outcomes.process.status -eq 'needsInput' -and $report.outcomes.process.gaps.Count -gt 0 -and (Fact $author 'handoff-state').value -eq 'pending')
} catch { $failure = $_.Exception.Message }
$failed = @($checks | Where-Object { !$_.passed }).Count
if ($failure -and $failed -eq 0) { $failed = 1 }
$report = [ordered]@{passed=@($checks | Where-Object passed).Count;failed=$failed;runDirectory=$runRoot;error=$failure;checks=@($checks);calls=@($calls)}
[System.IO.File]::WriteAllText((Join-Path $runRoot 'verification.json'), ($report | ConvertTo-Json -Depth 100), $utf8)
$report | ConvertTo-Json -Depth 100
if ($failed -gt 0) { exit 1 }
