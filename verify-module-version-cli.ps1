[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
& (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly
if ($LASTEXITCODE -ne 0) { throw '版本验收编译未通过。' }
$javaRoot = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $env:JAVA_HOME } else { 'D:\JavaJDK\jdk-21.0.5' }
$javaTool = Join-Path $javaRoot 'bin\java.exe'
$jarRoot = Join-Path $env:USERPROFILE '.m2\repository\com\fasterxml\jackson\core'
$dependencies = @('jackson-databind', 'jackson-core', 'jackson-annotations') | ForEach-Object { Join-Path $jarRoot "$_\2.19.2\$_-2.19.2.jar" }
$classPath = (@((Join-Path $PSScriptRoot 'build\classes')) + $dependencies) -join ';'
$runRoot = Join-Path $PSScriptRoot ('runs\module-version-cli-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)
$results = [System.Collections.Generic.List[object]]::new()
function Invoke-Version([string[]]$Arguments, [string]$OutputName, [bool]$ExpectFailure = $false) {
    $ErrorActionPreference = 'Continue'
    $errorFile = Join-Path $runRoot ($OutputName + '.stderr.txt')
    $lines = & $javaTool '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $classPath creativeworld.WorldCli @Arguments 2> $errorFile
    $code = $LASTEXITCODE
    if ($ExpectFailure) {
        if ($code -eq 0) { throw '预期拒绝的版本输入竟然成功。' }
        return [pscustomobject]@{exitCode=$code;error=(Get-Content -LiteralPath $errorFile -Raw -Encoding UTF8)}
    }
    if ($code -ne 0) { throw (Get-Content -LiteralPath $errorFile -Raw -Encoding UTF8) }
    $text = $lines -join [Environment]::NewLine
    [System.IO.File]::WriteAllText((Join-Path $runRoot $OutputName), $text, $utf8)
    return ($text | ConvertFrom-Json)
}
function Assert-Version([string]$Name, [bool]$Condition) {
    if (!$Condition) { throw "验证失败：$Name" }
    $results.Add([pscustomobject]@{name=$Name;passed=$true})
}
function Save-Version([string]$Name, [object]$Value) {
    $path = Join-Path $runRoot $Name
    [System.IO.File]::WriteAllText($path, ($Value | ConvertTo-Json -Depth 80), $utf8)
    return $path
}
$draftFile = Join-Path $runRoot 'draft.json'
$worldFile = Join-Path $runRoot 'world.json'
$null = Invoke-Version @('construct', (Join-Path $PSScriptRoot 'examples\genesis-river-town.json'), $draftFile) 'construct.json'
$null = Invoke-Version @('confirm', $draftFile, $worldFile) 'initial.json'
$askFile = Join-Path $PSScriptRoot 'examples\genesis-ask.json'
$first = Invoke-Version @('step', $worldFile, 'ask', $askFile) 'old-step.json'
$hash = (Get-FileHash -LiteralPath $worldFile).Hash
$again = Invoke-Version @('step', $worldFile, 'ask', $askFile, '1') 'explicit-repeated.json'
Assert-Version '旧入口与显式版本1返回同一正式回执，文件字节不变' ($first.revision -eq 1 -and $again.requestId -eq $first.requestId -and $again.revision -eq 1 -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $hash)
$history = Invoke-Version @('history', $worldFile) 'old-history.json'
Assert-Version '旧事件不补写版本字段' (!$history.events[0].submission.PSObject.Properties['moduleVersion'])
$newAsk = Get-Content -LiteralPath $askFile -Raw -Encoding UTF8 | ConvertFrom-Json
$newAsk.requestId = 'explicit-version-one'
$newAsk.expectedRevision = 1
$newAskFile = Save-Version 'new-ask.json' $newAsk
$explicit = Invoke-Version @('step', $worldFile, 'ask', $newAskFile, '1') 'explicit-step.json'
$history = Invoke-Version @('history', $worldFile) 'explicit-history.json'
Assert-Version '新显式请求保存行为版本1，旧历史形状保持不变' ($explicit.revision -eq 2 -and $history.events[1].submission.moduleVersion -eq '1' -and !$history.events[0].submission.PSObject.Properties['moduleVersion'])
$hash = (Get-FileHash -LiteralPath $worldFile).Hash
$implicit = Invoke-Version @('step', $worldFile, 'ask', $newAskFile) 'implicit-repeated.json'
Assert-Version '显式版本1提交后旧入口也复用原回执' ($implicit.revision -eq 2 -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $hash)
$null = Invoke-Version @('step', $worldFile, 'ask', $newAskFile, '2') 'cross-version' $true
Assert-Version '版本2不能冒领版本1的请求' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $hash)
$newAsk.requestId = 'missing-version-two'
$newAsk.expectedRevision = 2
$missingFile = Save-Version 'missing-version.json' $newAsk
$missing = Invoke-Version @('step', $worldFile, 'ask', $missingFile, '2') 'missing-version' $true
Assert-Version '未注册版本2明确失败，不自动使用内置版本1' ($missing.error -match '未注册' -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $hash)
foreach ($badVersion in @('', ' ', "1`n2")) {
    $null = Invoke-Version @('step', $worldFile, 'ask', $missingFile, $badVersion) ('invalid-version-' + $results.Count) $true
}
Assert-Version '空值、空白和换行版本拒绝且不改世界' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $hash)
$null = Invoke-Version @('step', $worldFile, 'ask', $missingFile, '1', 'extra') 'extra-argument' $true
Assert-Version '多余命令行参数被拒绝' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $hash)
$experienceWorld = Join-Path $runRoot 'experience-world.json'
$null = Invoke-Version @('confirm', $draftFile, $experienceWorld) 'experience-initial.json'
$legacyRecord = Join-Path $runRoot 'legacy-record.json'
$legacyFile = Join-Path $PSScriptRoot 'examples\experience-ask.json'
$legacy = Invoke-Version @('experience', $experienceWorld, $legacyFile, $legacyRecord) 'legacy-experience.json'
$versioned = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'examples\experience-ask-versioned.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$versioned.expectedRevision = 1
$versionedFile = Save-Version 'versioned-experience-input.json' $versioned
$versionedRecord = Join-Path $runRoot 'versioned-record.json'
$done = Invoke-Version @('experience', $experienceWorld, $versionedFile, $versionedRecord) 'versioned-experience.json'
Assert-Version '候选、行动和准备记录都保留显式行为版本1' ($done.status -eq 'completed' -and $done.stages[1].output.candidates[0].moduleVersion -eq '1' -and $done.stages[3].output.action.moduleVersion -eq '1' -and $done.prepared.submission.moduleVersion -eq '1' -and $done.receipt.revision -eq 2)
$hash = (Get-FileHash -LiteralPath $experienceWorld).Hash
$recovered = Invoke-Version @('experience', $experienceWorld, $legacyFile, $legacyRecord) 'old-experience-recovered.json'
Assert-Version '新版本元数据提交后，旧演绎恢复仍绑定原回执及原人物视图' ($recovered.receipt.revision -eq 1 -and $recovered.finalView.revision -eq 1 -and (Get-FileHash -LiteralPath $experienceWorld).Hash -eq $hash)
$repeated = Invoke-Version @('experience', $experienceWorld, $versionedFile, $versionedRecord) 'explicit-experience-repeated.json'
Assert-Version '显式版本演绎重跑不增加正式事件' ($repeated.receipt.revision -eq 2 -and (Get-FileHash -LiteralPath $experienceWorld).Hash -eq $hash)
$tampered = Get-Content -LiteralPath $versionedRecord -Raw -Encoding UTF8 | ConvertFrom-Json
$tampered.prepared.submission.moduleVersion = '2'
$tamperedFile = Save-Version 'tampered-record.json' $tampered
$null = Invoke-Version @('experience', $experienceWorld, $versionedFile, $tamperedFile) 'tampered-recovery' $true
Assert-Version '恢复记录偷换行为版本先被挡住，不改正式世界' ((Get-FileHash -LiteralPath $experienceWorld).Hash -eq $hash)
$rejectedWorld = Join-Path $runRoot 'rejected-world.json'
$null = Invoke-Version @('confirm', $draftFile, $rejectedWorld) 'rejected-initial.json'
$hash = (Get-FileHash -LiteralPath $rejectedWorld).Hash
$versioned.expectedRevision = 0
$versioned.requestId = 'experience-missing-two'
$versioned.scenario.candidates[0].moduleVersion = '2'
$twoFile = Save-Version 'experience-two.json' $versioned
$twoRecord = Join-Path $runRoot 'two-record.json'
$null = Invoke-Version @('experience', $rejectedWorld, $twoFile, $twoRecord) 'experience-two' $true
$failed = Get-Content -LiteralPath $twoRecord -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-Version '演绎缺行为版本时保存失败和四个已完成阶段，世界不提交' ($failed.status -eq 'failed' -and @($failed.stages | Where-Object status -eq 'completed').Count -eq 4 -and !$failed.PSObject.Properties['receipt'] -and (Get-FileHash -LiteralPath $rejectedWorld).Hash -eq $hash)
foreach ($badVersion in @($null, 1)) {
    $versioned.requestId = 'bad-candidate-' + $results.Count + '-' + [guid]::NewGuid().ToString('N')
    $versioned.scenario.candidates[0].moduleVersion = $badVersion
    $badFile = Save-Version ($versioned.requestId + '.json') $versioned
    $null = Invoke-Version @('experience', $rejectedWorld, $badFile, (Join-Path $runRoot ($versioned.requestId + '-record.json'))) $versioned.requestId $true
}
Assert-Version '候选中的显式null或数字版号不能被当作默认1' ((Get-FileHash -LiteralPath $rejectedWorld).Hash -eq $hash)
$finalHistory = Invoke-Version @('history', $experienceWorld) 'final-history.json'
Assert-Version '新进程恢复两个正式事件，旧无版号和新显式版号各自保留' ($finalHistory.events.Count -eq 2 -and !$finalHistory.events[0].submission.PSObject.Properties['moduleVersion'] -and $finalHistory.events[1].submission.moduleVersion -eq '1')
$report = [pscustomobject]@{checks=$results.Count;passed=$results.Count;failed=0;runDirectory=$runRoot;results=$results;actualModelCalled=$false;proseQualityEvaluated=$false;evidenceLimit='只测内置版本1的实际命令行、缺失版本拒绝和历史恢复；同名v1/v2实现替换另见独立Java专项。'}
$null = Save-Version 'module-version-cli-check.json' $report
$report | ConvertTo-Json -Depth 8
exit 0
