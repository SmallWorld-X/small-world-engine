[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
& (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly
if ($LASTEXITCODE -ne 0) { throw '演绎编译未通过。' }
$runtime = & (Join-Path $PSScriptRoot 'runtime.ps1')
$javaTool = $runtime.javaTool
$classPath = $runtime.classPath
$runRoot = Join-Path $PSScriptRoot ('runs\experience-cli-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)
$results = [System.Collections.Generic.List[object]]::new()
function Invoke-Experience([string[]]$Arguments, [string]$OutputName, [bool]$ExpectFailure = $false) {
    $ErrorActionPreference = 'Continue'
    $errorFile = Join-Path $runRoot ($OutputName + '.stderr.txt')
    $lines = & $javaTool '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $classPath creativeworld.WorldCli @Arguments 2> $errorFile
    $code = $LASTEXITCODE
    if ($ExpectFailure) {
        if ($code -eq 0) { throw '预期拒绝的演绎竟然成功。' }
        return [pscustomobject]@{exitCode=$code;error=(Get-Content -LiteralPath $errorFile -Raw -Encoding UTF8)}
    }
    if ($code -ne 0) { throw (Get-Content -LiteralPath $errorFile -Raw -Encoding UTF8) }
    $text = $lines -join [Environment]::NewLine
    [System.IO.File]::WriteAllText((Join-Path $runRoot $OutputName), $text, $utf8)
    return ($text | ConvertFrom-Json)
}
function Assert-Experience([string]$Name, [bool]$Condition) {
    if (!$Condition) { throw "验证失败：$Name" }
    $results.Add([pscustomobject]@{name=$Name;passed=$true})
}
function Save-Experience([string]$Name, [object]$Value) {
    $path = Join-Path $runRoot $Name
    [System.IO.File]::WriteAllText($path, ($Value | ConvertTo-Json -Depth 80), $utf8)
    return $path
}
$draftFile = Join-Path $runRoot 'draft.json'
$worldFile = Join-Path $runRoot 'world.json'
$recordFile = Join-Path $runRoot 'ask-record.json'
$missingFile = Join-Path $runRoot 'missing-record.json'
$askFile = Join-Path $PSScriptRoot 'examples\experience-ask.json'
$null = Invoke-Experience @('construct', (Join-Path $PSScriptRoot 'examples\genesis-river-town.json'), $draftFile) 'construct-result.json'
$initial = Invoke-Experience @('confirm', $draftFile, $worldFile) 'initial.json'
$initialView = Invoke-Experience @('view', $worldFile, 'apprentice') 'initial-view.json'
Assert-Experience '八类构造器建立正式起点，人物尚不知道货箱下落' ($initial.revision -eq 0 -and @($initialView.knowledge | Where-Object factId -eq 'cargo-location').Count -eq 0)
$originalHash = (Get-FileHash -LiteralPath $worldFile).Hash
$missing = Invoke-Experience @('experience', $worldFile, (Join-Path $PSScriptRoot 'examples\experience-missing.json'), $missingFile) 'missing-result.json'
Assert-Experience '缺货主许可时准备阶段停住并保留具体缺口' ($missing.status -eq 'needsInput' -and $missing.stages.Count -eq 1 -and @($missing.stages[0].output.gaps | Where-Object path -eq '/self/privateProfile/cargoPermission').Count -eq 1)
Assert-Experience '缺资料不写入正式事件，世界文件字节不变' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $originalHash -and !$missing.PSObject.Properties['receipt'])
$asked = Invoke-Experience @('experience', $worldFile, $askFile, $recordFile) 'ask-result.json'
Assert-Experience '五个实际阶段依次完成，收到真实提交回执' ($asked.status -eq 'completed' -and $asked.stages.Count -eq 5 -and ($asked.stages.stage -join ',') -eq 'prepare,candidates,select,resolve,feedback' -and $asked.receipt.revision -eq 1)
$after = Invoke-Experience @('view', $worldFile, 'apprentice') 'after-ask.json'
$known = @($after.knowledge | Where-Object factId -eq 'cargo-location')[0]
Assert-Experience '阿禾获得陈舟转述的位置，自己仍在码头' ($known.value -eq 'warehouse' -and $known.source -eq 'boatman' -and $after.self.name -eq '阿禾' -and $after.self.place -eq 'wharf')
Assert-Experience '一次询问后仍保留观察、交接与运输缺口' ($asked.stages[4].output.remainingGaps.Count -eq 2 -and $asked.finalView.revision -eq 1)
$history = Invoke-Experience @('history', $worldFile) 'history-after-ask.json'
Assert-Experience '五个阶段只产生一个正式行动事件' ($history.events.Count -eq 1 -and $history.events[0].submission.module -eq 'ask')
$askedHash = (Get-FileHash -LiteralPath $worldFile).Hash
$again = Invoke-Experience @('experience', $worldFile, $askFile, $recordFile) 'ask-repeated.json'
Assert-Experience '同一完整尝试重跑复用结果，不再次落账' ($again.status -eq 'completed' -and $again.stages.Count -eq 5 -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $askedHash)
$author = Invoke-Experience @('view', $worldFile, '@author') 'author-after-ask.json'
$quantity = @($author.facts | Where-Object id -eq 'iron-quantity')[0]
$change = @{schemaVersion=1;worldId=$author.worldId;requestId='later-author-change';expectedRevision=1;actor='@author';reason='作者在下一事件明确减少一单位铁料，此例不推导加工';effects=@(@{type='fact.change';factId='iron-quantity';expectedValue=$quantity.value;value=($quantity.value-1)})}
$changePath = Save-Experience 'later-change.json' $change
$later = Invoke-Experience @('apply', $worldFile, $changePath) 'later-receipt.json'
$laterHash = (Get-FileHash -LiteralPath $worldFile).Hash
$old = Invoke-Experience @('experience', $worldFile, $askFile, $recordFile) 'old-result-after-later-event.json'
Assert-Experience '世界继续发展后，旧反馈仍绑定原事件版本' ($later.revision -eq 2 -and $old.receipt.revision -eq 1 -and $old.finalView.revision -eq 1 -and (Get-FileHash -LiteralPath $worldFile).Hash -eq $laterHash)
$changedInput = Get-Content -LiteralPath $askFile -Raw -Encoding UTF8 | ConvertFrom-Json
$changedInput.intent = '改写原有尝试'
$changedInputPath = Save-Experience 'different-input.json' $changedInput
$null = Invoke-Experience @('experience', $worldFile, $changedInputPath, $recordFile) 'different-input' $true
Assert-Experience '修改输入不能沿用旧尝试记录' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $laterHash)
$null = Invoke-Experience @('experience', $worldFile, $askFile, $worldFile) 'record-overwrite' $true
Assert-Experience '记录路径不能覆盖正式世界' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $laterHash)
$finalHistory = Invoke-Experience @('history', $worldFile) 'final-history.json'
Assert-Experience '重新开进程后两个真实事件都可恢复' ($finalHistory.revision -eq 2 -and $finalHistory.events.Count -eq 2)
$report = [pscustomobject]@{checks=$results.Count;passed=$results.Count;failed=0;runDirectory=$runRoot;results=$results;actualModelCalled=$false;proseQualityEvaluated=$false}
$null = Save-Experience 'experience-cli-check.json' $report
$report | ConvertTo-Json -Depth 8
