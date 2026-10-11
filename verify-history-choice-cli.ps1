[CmdletBinding()]
param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$utf8 = [System.Text.UTF8Encoding]::new($false)
$runRoot = Join-Path $PSScriptRoot ('runs\history-choice-cli-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot | Out-Null
$checks = [System.Collections.Generic.List[object]]::new()
$calls = [System.Collections.Generic.List[object]]::new()
$branches = [System.Collections.Generic.List[object]]::new()
$failure = $null
function Check([string]$Name, [bool]$Passed) {
    $checks.Add([pscustomobject]@{name=$Name;passed=$Passed})
    if (!$Passed) { throw "检查失败：$Name" }
}
function Json([object]$Value) { $Value | ConvertTo-Json -Depth 100 -Compress }
function Fact([object]$View, [string]$Id) { @($View.facts | Where-Object id -EQ $Id)[0] }
function Known([object]$View, [string]$Id) { @($View.knowledge | Where-Object factId -EQ $Id)[0] }
function Save([string]$Name, [object]$Value) {
    $path = Join-Path $runRoot $Name
    [System.IO.File]::WriteAllText($path, ($Value | ConvertTo-Json -Depth 100), $utf8)
    return $path
}
function Invoke-Engine([string]$Name, [string[]]$Arguments) {
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $javaTool; $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = $utf8; $start.StandardErrorEncoding = $utf8
    foreach ($argument in @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', $classPath, 'creativeworld.WorldCli') + $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [System.Diagnostics.Process]::Start($start)
    $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit(); $out = $stdout.GetAwaiter().GetResult(); $err = $stderr.GetAwaiter().GetResult()
    $code = $process.ExitCode; $process.Dispose()
    $outPath = Join-Path $runRoot "$Name.stdout.json"; $errPath = Join-Path $runRoot "$Name.stderr.txt"
    [System.IO.File]::WriteAllText($outPath, $out, $utf8); [System.IO.File]::WriteAllText($errPath, $err, $utf8)
    $calls.Add([pscustomobject]@{name=$Name;arguments=$Arguments;exitCode=$code;stdout=$outPath;stderr=$errPath})
    if ($code -ne 0) { throw "命令失败：$Name；诊断已保存" }
    return ($out | ConvertFrom-Json)
}
try {
    $runtime = & (Join-Path $PSScriptRoot 'runtime.ps1')
    $javaTool = $runtime.javaTool; $classPath = $runtime.classPath
    if (!$SkipBuild) {
        $output = & (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly
        [System.IO.File]::WriteAllText((Join-Path $runRoot 'compile.stdout.txt'), ($output -join [Environment]::NewLine), $utf8)
        Check 'Java独立编译完成' ($LASTEXITCODE -eq 0)
    }
    $source = Join-Path $PSScriptRoot 'examples\continuation'
    foreach ($scene in @('river', 'workshop')) {
        $river = $scene -eq 'river'
        $who = if ($river) { 'apprentice' } else { 'resident' }
        $sender = if ($river) { 'keeper' } else { 'steward' }
        $idle = if ($river) { 'smith' } else { 'volunteer' }
        $place = if ($river) { 'registry' } else { 'service-desk' }
        $location = if ($river) { 'cargo-location' } else { 'equipment-location' }
        $state = if ($river) { 'handoff-state' } else { 'equipment-available' }
        $doneValue = if ($river) { 'registered' } else { $true }
        $base = Get-Content -LiteralPath (Join-Path $PSScriptRoot "examples\history-choice\$scene-request.json") -Raw -Encoding UTF8 | ConvertFrom-Json
        $sameScenario = Json $base.scenario
        $views = @{}
        foreach ($phase in @('initial', 'before', 'after')) {
            $prefix = "$scene-$phase"
            $budget = if ($phase -eq 'before') { 1 } elseif ($phase -eq 'after') { 3 } else { 0 }
            $world = Join-Path $runRoot "$prefix-world.json"
            $record = Join-Path $runRoot "$prefix-experience.json"
            $null = Invoke-Engine "$prefix-init" @('init', (Join-Path $source "$scene-seed.json"), $world)
            if ($budget -gt 0) {
                $paused = Invoke-Engine "$prefix-pause" @('continue', $world, (Join-Path $source "$scene-request.json"), (Join-Path $runRoot "$prefix-episode"), "$budget")
                Check "$prefix 从实际经历暂停" ($paused.status -eq 'paused' -and $paused.revision -eq $budget -and $paused.attempts.Count -eq $budget)
            }
            $view = Invoke-Engine "$prefix-view" @('view', $world, $who)
            $views[$phase] = $view
            $author = Invoke-Engine "$prefix-author" @('view', $world, '@author')
            $oldHistory = Invoke-Engine "$prefix-history-before" @('history', $world)
            Check "$prefix 起点和同地人物正确" ($view.revision -eq $budget -and $view.self.place -eq $place -and $view.nearby[0].id -eq $sender -and $oldHistory.events.Count -eq $budget)
            $input = $base | ConvertTo-Json -Depth 100 | ConvertFrom-Json
            $input.requestId = "$prefix-history-choice"; $input.expectedRevision = $budget
            Check "$prefix 候选和策略声明保持相同" ((Json $input.scenario) -eq $sameScenario -and (Json $input.components) -eq (Json $base.components))
            $inputPath = Save "$prefix-input.json" $input
            $hash = (Get-FileHash -LiteralPath $world -Algorithm SHA256).Hash
            $result = Invoke-Engine "$prefix-experience" @('experience', $world, $inputPath, $record)
            $available = @($result.stages | Where-Object stage -EQ 'candidates')[0].output
            $selection = @($result.stages | Where-Object stage -EQ 'select')
            if ($budget -eq 0) {
                Check "$prefix 作者知道地点不代替本人经历" ((Fact $author $location).value -ne $null -and !(Known $view $location) -and !(Known $view $state))
                Check "$prefix 缺经历明确停止" ($result.status -eq 'needsInput' -and $result.stages.Count -eq 2 -and $available.status -eq 'needsInput' -and $available.candidates.Count -eq 0 -and $available.gaps.Count -gt 0 -and !$result.PSObject.Properties['receipt'] -and !$result.PSObject.Properties['prepared'])
                Check "$prefix 未提交世界字节保持" ($hash -eq (Get-FileHash -LiteralPath $world -Algorithm SHA256).Hash)
            } else {
                $known = Known $view $location
                Check "$prefix 地点依据来自第1笔本人消息" ($known.source -eq $sender -and $known.revision -eq 1 -and $oldHistory.events[0].submission.module -eq 'ask')
                if ($budget -eq 1) {
                    Check "$prefix 缺办理回应只能选择当前状态" (!(Known $view $state) -and $available.candidates.Count -eq 1 -and $available.candidates[0].id -eq 'ask-current-result' -and $selection[0].output.candidateId -eq 'ask-current-result' -and @($available.gaps | Where-Object candidateId -EQ 'ask-next-permission').Count -gt 0)
                    $expectedFact = $state
                } else {
                    $known = Known $view $state
                    Check "$prefix 办理依据来自第3笔真实回应" ($known.value -eq $doneValue -and $known.source -eq $sender -and $known.revision -eq 3 -and $oldHistory.events[2].submission.module -eq 'message-state-response')
                    Check "$prefix 同组候选改选下一步许可" ($available.candidates.Count -eq 2 -and $available.gaps.Count -eq 0 -and $selection[0].output.candidateId -eq 'ask-next-permission')
                    $expectedFact = 'further-permission'
                }
                $known = Known $result.finalView $expectedFact
                Check "$prefix 选择执行反馈一致" ($result.status -eq 'completed' -and $result.stages.Count -eq 5 -and $result.receipt.revision -eq ($budget + 1) -and $result.prepared.submission.module -eq 'ask' -and $known.source -eq $sender -and $known.revision -eq ($budget + 1) -and $result.stages[4].output.remainingGaps.Count -eq 2)
                if ($budget -eq 3) { Check "$prefix 询问获得否定许可消息" ($known.value -eq $false) }
            }
            $history = Invoke-Engine "$prefix-history-after" @('history', $world)
            $after = Invoke-Engine "$prefix-author-after" @('view', $world, '@author')
            $unaware = Invoke-Engine "$prefix-idle" @('view', $world, $idle)
            $old = Invoke-Engine "$prefix-view-at" @('view-at', $world, $who, "$budget")
            $added = if ($budget -gt 0) { 1 } else { 0 }
            Check "$prefix 只追加本次询问并保留旧历史" ($history.events.Count -eq ($budget + $added) -and (Json @($history.events | Select-Object -First $budget)) -eq (Json @($oldHistory.events)) -and (Json $old) -eq (Json $view))
            if ($budget -gt 0) {
                Check "$prefix 正式事件确为所选询问" ($history.events[-1].submission.module -eq 'ask' -and $history.events[-1].proposal.actor -eq $who -and $history.events[-1].submission.input.factId -eq $expectedFact)
            }
            $finalView = if ($budget -gt 0) { $result.finalView } else { $result.initialView }
            Check "$prefix 询问不改变真相许可资源时间或位置" ((Json $after.facts) -eq (Json $author.facts) -and (Fact $after 'further-permission').value -eq $false -and (Fact $after 'resource-quantity').value -eq $(if ($river) { 40 } else { 4 }) -and $after.worldTime.year -eq 100 -and @($unaware.knowledge).Count -eq 0 -and $finalView.self.place -eq $place)
            $hash = (Get-FileHash -LiteralPath $world -Algorithm SHA256).Hash
            $again = Invoke-Engine "$prefix-reopen" @('experience', $world, $inputPath, $record)
            Check "$prefix 独立进程回读同一结果" ((Json $again) -eq (Json $result))
            Check "$prefix 恢复不重复事件且世界字节保持" ($hash -eq (Get-FileHash -LiteralPath $world -Algorithm SHA256).Hash)
            $branches.Add([pscustomobject]@{scene=$scene;phase=$phase;startingRevision=$budget;status=$result.status;candidates=@($available.candidates | ForEach-Object id);selected=$(if ($selection.Count) { $selection[0].output.candidateId } else { $null });finalRevision=$history.revision;events=$history.events.Count})
        }
        Check "$scene 前后地点和本人策略相同" ($views.before.self.place -eq $views.after.self.place -and (Json $views.before.self.privateProfile) -eq (Json $views.after.self.privateProfile) -and (Json (Known $views.before $location)) -eq (Json (Known $views.after $location)))
    }
} catch { $failure = $_.Exception.Message }
$failed = @($checks | Where-Object { !$_.passed }).Count
if ($failure -and $failed -eq 0) { $failed = 1 }
$report = [ordered]@{passed=@($checks | Where-Object passed).Count;failed=$failed;runDirectory=$runRoot;error=$failure;checks=@($checks);branches=@($branches);calls=@($calls);actualModelCalled=$false;psychologyInferred=$false}
[System.IO.File]::WriteAllText((Join-Path $runRoot 'verification.json'), ($report | ConvertTo-Json -Depth 100), $utf8)
$report | ConvertTo-Json -Depth 100
if ($failed -gt 0) { exit 1 }
