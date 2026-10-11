[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
& (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly
if ($LASTEXITCODE -ne 0) { throw '目标推进器编译未通过。' }
$javaRoot = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $env:JAVA_HOME } else { 'D:\JavaJDK\jdk-21.0.5' }
$javaTool = Join-Path $javaRoot 'bin\java.exe'
$jarRoot = Join-Path $env:USERPROFILE '.m2\repository\com\fasterxml\jackson\core'
$dependencies = @('jackson-databind', 'jackson-core', 'jackson-annotations') | ForEach-Object { Join-Path $jarRoot "$_\2.19.2\$_-2.19.2.jar" }
$classPath = (@((Join-Path $PSScriptRoot 'build\classes')) + $dependencies) -join ';'
$runRoot = Join-Path $PSScriptRoot ('runs\goal-cli-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)
$results = [System.Collections.Generic.List[object]]::new()
function Invoke-Goal([string[]]$Arguments, [string]$OutputName, [bool]$ExpectFailure = $false) {
    $ErrorActionPreference = 'Continue'
    $errorFile = Join-Path $runRoot ($OutputName + '.stderr.txt')
    $lines = & $javaTool '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $classPath creativeworld.WorldCli @Arguments 2> $errorFile
    $code = $LASTEXITCODE
    if ($ExpectFailure) {
        if ($code -eq 0) { throw '预期拒绝的目标过程竟然成功。' }
        return [pscustomobject]@{exitCode=$code;error=(Get-Content -LiteralPath $errorFile -Raw -Encoding UTF8)}
    }
    if ($code -ne 0) { throw (Get-Content -LiteralPath $errorFile -Raw -Encoding UTF8) }
    $text = $lines -join [Environment]::NewLine
    [System.IO.File]::WriteAllText((Join-Path $runRoot $OutputName), $text, $utf8)
    return ($text | ConvertFrom-Json)
}
function Assert-Goal([string]$Name, [bool]$Condition) {
    if (!$Condition) { throw "验证失败：$Name" }
    $results.Add([pscustomobject]@{name=$Name;passed=$true})
}
function Save-Goal([string]$Name, [object]$Value) {
    $path = Join-Path $runRoot $Name
    [System.IO.File]::WriteAllText($path, ($Value | ConvertTo-Json -Depth 80), $utf8)
    return $path
}
$draftFile = Join-Path $runRoot 'draft.json'
$worldFile = Join-Path $runRoot 'world.json'
$journeyDirectory = Join-Path $runRoot 'journey'
$requestFile = Join-Path $PSScriptRoot 'examples\goal-inspect.json'
$null = Invoke-Goal @('construct', (Join-Path $PSScriptRoot 'examples\goal-river-town.json'), $draftFile) 'construct-result.json'
$null = Invoke-Goal @('confirm', $draftFile, $worldFile) 'initial.json'
$input = Get-Content -LiteralPath $requestFile -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-Goal '目标请求没有手写候选或动作序列' (!$input.scenario.PSObject.Properties['candidates'] -and $input.scenario.goal.kind -eq 'observeLocatedFact')
$paused = Invoke-Goal @('pursue', $worldFile, $requestFile, $journeyDirectory, '2') 'paused-result.json'
$afterTwo = Invoke-Goal @('view', $worldFile, 'apprentice') 'after-two.json'
$message = @($afterTwo.knowledge | Where-Object factId -eq 'cargo-location')[0]
Assert-Goal '预算两步自动询问再移动，到货栈后暂停且消息仍是转述' ($paused.status -eq 'paused' -and $afterTwo.revision -eq 2 -and $afterTwo.self.place -eq 'warehouse' -and $message.source -eq 'boatman')
$finished = Invoke-Goal @('pursue', $worldFile, $requestFile, $journeyDirectory, '8') 'finished-result.json'
$after = Invoke-Goal @('view', $worldFile, 'apprentice') 'after-observation.json'
$known = @($after.knowledge | Where-Object factId -eq 'cargo-location')[0]
Assert-Goal '原过程增加预算继续现场观察，最后正常满足目标' ($finished.status -eq 'satisfied' -and $after.revision -eq 3 -and $known.source -eq '@world' -and $known.value -eq 'warehouse')
$history = Invoke-Goal @('history', $worldFile) 'final-history.json'
Assert-Goal '正式历史正好是询问、移动、观察三个实际事件' ($history.events.Count -eq 3 -and ($history.events.submission.module -join ',') -eq 'ask,travel,observe')
$finalStep = Get-Content -LiteralPath (Join-Path $journeyDirectory 'step-004.json') -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-Goal '目标满足检查只保存停止记录，不伪造第四个行动回执' ($finalStep.status -eq 'satisfied' -and !$finalStep.PSObject.Properties['receipt'])
$worldHash = (Get-FileHash -LiteralPath $worldFile).Hash
$again = Invoke-Goal @('pursue', $worldFile, $requestFile, $journeyDirectory, '8') 'repeated-result.json'
Assert-Goal '重开进程复用整个已满足过程，不新增事件' ($again.status -eq 'satisfied' -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $worldHash)
$changed = Get-Content -LiteralPath $requestFile -Raw -Encoding UTF8 | ConvertFrom-Json
$changed.intent = '改用另一目标过程'
$changedFile = Save-Goal 'different-goal.json' $changed
$null = Invoke-Goal @('pursue', $worldFile, $changedFile, $journeyDirectory, '8') 'different-goal' $true
Assert-Goal '更改原输入不能借用已有过程，世界保持不变' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $worldHash)
$aliasDirectory = Join-Path $runRoot 'input-lock-alias'
New-Item -ItemType Directory -Path $aliasDirectory -Force | Out-Null
$aliasInput = Join-Path $aliasDirectory 'manifest.lock'
Copy-Item -LiteralPath $requestFile -Destination $aliasInput
$aliasHash = (Get-FileHash -LiteralPath $aliasInput).Hash
$null = Invoke-Goal @('pursue', $worldFile, $aliasInput, $aliasDirectory, '8') 'input-lock-alias' $true
Assert-Goal '目标输入不能兼作过程锁，拒绝后输入与世界不变' ((Get-FileHash -LiteralPath $aliasInput).Hash -eq $aliasHash -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $worldHash -and !(Test-Path -LiteralPath (Join-Path $aliasDirectory 'journey.json')))
$stepThree = Get-Content -LiteralPath (Join-Path $journeyDirectory 'step-003.json') -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-Goal '观察完成仍保留取货许可、重量和运输工具缺口' ($stepThree.stages[4].output.remainingGaps.Count -ge 1)
$unknownBlueprint = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'examples\goal-river-town.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$unknownBlueprint.worldId = 'goal-unknown-route'
$null = $unknownBlueprint.sections.people[0].privateProfile.PSObject.Properties.Remove('knownRoutes')
$unknownBlueprintFile = Save-Goal 'unknown-route-blueprint.json' $unknownBlueprint
$unknownDraft = Join-Path $runRoot 'unknown-route-draft.json'
$unknownWorld = Join-Path $runRoot 'unknown-route-world.json'
$null = Invoke-Goal @('construct', $unknownBlueprintFile, $unknownDraft) 'unknown-construct.json'
$null = Invoke-Goal @('confirm', $unknownDraft, $unknownWorld) 'unknown-initial.json'
$unknownRequest = Get-Content -LiteralPath $requestFile -Raw -Encoding UTF8 | ConvertFrom-Json
$unknownRequest.worldId = 'goal-unknown-route'
$unknownRequestFile = Save-Goal 'unknown-route-request.json' $unknownRequest
$missing = Invoke-Goal @('pursue', $unknownWorld, $unknownRequestFile, (Join-Path $runRoot 'unknown-journey'), '8') 'unknown-result.json'
$unknownHistory = Invoke-Goal @('history', $unknownWorld) 'unknown-history.json'
Assert-Goal '客观道路仍在但人物路线未确认，只询问后停缺口' ($missing.status -eq 'needsInput' -and $unknownHistory.events.Count -eq 1 -and $unknownHistory.events[0].submission.module -eq 'ask')
$unknownHash = (Get-FileHash -LiteralPath $unknownWorld).Hash
$null = Invoke-Goal @('pursue', $unknownWorld, $unknownRequestFile, (Join-Path $runRoot 'unknown-journey'), '8') 'unknown-repeated.json'
Assert-Goal '缺口过程重跑仍停止，不能借隐藏地图继续移动' ((Get-FileHash -LiteralPath $unknownWorld).Hash -eq $unknownHash)
$report = [pscustomobject]@{checks=$results.Count;passed=$results.Count;failed=0;runDirectory=$runRoot;results=$results;actualModelCalled=$false;psychologyEvaluated=$false;proseQualityEvaluated=$false}
$null = Save-Goal 'goal-cli-check.json' $report
$report | ConvertTo-Json -Depth 8
