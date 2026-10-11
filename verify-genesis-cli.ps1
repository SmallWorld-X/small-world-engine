[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
& (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly
if ($LASTEXITCODE -ne 0) { throw '构造器编译未通过。' }
$javaRoot = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $env:JAVA_HOME } else { 'D:\JavaJDK\jdk-21.0.5' }
$javaTool = Join-Path $javaRoot 'bin\java.exe'
$jarRoot = Join-Path $env:USERPROFILE '.m2\repository\com\fasterxml\jackson\core'
$dependencies = @('jackson-databind', 'jackson-core', 'jackson-annotations') | ForEach-Object { Join-Path $jarRoot "$_\2.19.2\$_-2.19.2.jar" }
$classPath = (@((Join-Path $PSScriptRoot 'build\classes')) + $dependencies) -join ';'
$runRoot = Join-Path $PSScriptRoot ('runs\genesis-cli-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$results = [System.Collections.Generic.List[object]]::new()
$utf8 = [System.Text.UTF8Encoding]::new($false)
function Invoke-Genesis([string[]]$Arguments, [bool]$ExpectFailure = $false) {
    $ErrorActionPreference = 'Continue'
    $errorFile = Join-Path $runRoot ('stderr-' + [guid]::NewGuid().ToString('N') + '.txt')
    $lines = & $javaTool '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $classPath creativeworld.WorldCli @Arguments 2> $errorFile
    $code = $LASTEXITCODE
    if ($ExpectFailure) {
        if ($code -eq 0) { throw '预期拒绝的构造或提交竟然成功。' }
        return [pscustomobject]@{ ExitCode = $code; Error = (Get-Content -LiteralPath $errorFile -Raw -Encoding UTF8) }
    }
    if ($code -ne 0) { throw (Get-Content -LiteralPath $errorFile -Raw -Encoding UTF8) }
    return (($lines -join [Environment]::NewLine) | ConvertFrom-Json)
}
function Assert-Genesis([string]$Name, [bool]$Condition) {
    if (!$Condition) { throw "验证失败：$Name" }
    $results.Add([pscustomobject]@{ name = $Name; passed = $true })
}
function Save-GenesisCase([string]$Name, [object]$Value) {
    $path = Join-Path $runRoot $Name
    [System.IO.File]::WriteAllText($path, ($Value | ConvertTo-Json -Depth 70), $utf8)
    return $path
}
function Copy-GenesisValue([object]$Value) { return ($Value | ConvertTo-Json -Depth 70 | ConvertFrom-Json) }
$blueprintFile = Join-Path $PSScriptRoot 'examples\genesis-river-town.json'
$draftFile = Join-Path $runRoot 'draft.json'
$worldFile = Join-Path $runRoot 'world.json'
$created = Invoke-Genesis @('construct', $blueprintFile, $draftFile)
$draft = Get-Content -LiteralPath $draftFile -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-Genesis '构造八个模块的草案，不提前建立正式世界' ($created.status -eq 'draft-created' -and $draft.kind -eq 'world-draft' -and $draft.construction.moduleOrder.Count -eq 8 -and !(Test-Path -LiteralPath $worldFile))
Assert-Genesis '构造地区资源势力群体及三名核心人物和一名龙套' ($draft.seed.characters.Count -eq 4 -and @($draft.seed.entities | Where-Object kind -eq 'region').Count -ge 1 -and @($draft.seed.entities | Where-Object kind -eq 'resource').Count -ge 1 -and @($draft.seed.entities | Where-Object kind -eq 'faction').Count -eq 1 -and @($draft.seed.entities | Where-Object kind -eq 'group').Count -eq 1)
$draftHash = (Get-FileHash -LiteralPath $draftFile).Hash
$null = Invoke-Genesis @('construct', $blueprintFile, $draftFile) $true
Assert-Genesis '重复构造不覆盖已保存的候选草案' ((Get-FileHash -LiteralPath $draftFile).Hash -eq $draftHash)
$initial = Invoke-Genesis @('confirm', $draftFile, $worldFile)
Assert-Genesis '确认时重新构造并建立零事件的正式起点' ($initial.revision -eq 0 -and $initial.counts.characters -eq 4 -and $initial.counts.laws -ge 2 -and (Invoke-Genesis @('history', $worldFile)).events.Count -eq 0)
$author = Invoke-Genesis @('view', $worldFile, '@author')
$actor = Invoke-Genesis @('view', $worldFile, 'apprentice')
$reader = Invoke-Genesis @('view', $worldFile, '@reader')
$null = Save-GenesisCase 'author-view.json' $author
$null = Save-GenesisCase 'actor-view.json' $actor
Assert-Genesis '正式世界保留人物关系的初始依据' ($author.relationships[0].basis -eq $draft.blueprint.sections.relationships[0].basis -and ![string]::IsNullOrWhiteSpace($author.relationships[0].basis))
Assert-Genesis '情感构造资料由本人可见，目录不会自动进入人物或读者视图' ($actor.self.privateProfile.initialEmotions.Count -ge 1 -and !$actor.PSObject.Properties['entities'] -and !$reader.PSObject.Properties['entities'] -and $author.entities.Count -eq $draft.seed.entities.Count)
$actorText = $actor | ConvertTo-Json -Depth 70
$others = @($draft.seed.characters | Where-Object id -ne 'apprentice')
$leaked = $false
foreach ($other in $others) {
    foreach ($emotion in @($other.privateProfile.initialEmotions)) {
        if ($emotion -and $actorText.Contains($emotion.trigger)) { $leaked = $true }
    }
}
Assert-Genesis '人物不能读取他人的私密情感触发依据' (!$leaked)
$badDraft = Copy-GenesisValue $draft
$badDraft.seed.characters[0].name = '擅自修改派生人物'
$badDraftFile = Save-GenesisCase 'tampered-draft.json' $badDraft
$blockedWorld = Join-Path $runRoot 'blocked-world.json'
$null = Invoke-Genesis @('confirm', $badDraftFile, $blockedWorld) $true
Assert-Genesis '篡改派生结果的草案不能直接成为正式世界' (!(Test-Path -LiteralPath $blockedWorld))
$badOutput = Join-Path $runRoot 'invalid-draft.json'
$null = Invoke-Genesis @('construct', (Join-Path $PSScriptRoot 'examples\genesis-conflict.json'), $badOutput) $true
Assert-Genesis '构造资料冲突时不保存残缺草案' (!(Test-Path -LiteralPath $badOutput))
$worldHash = (Get-FileHash -LiteralPath $worldFile).Hash
$null = Invoke-Genesis @('confirm', $draftFile, $worldFile) $true
Assert-Genesis '重新确认不能重置已经存在的世界' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $worldHash)
$populationFactId = ($author.entities | Where-Object kind -eq 'group')[0].attributes.populationFactId
$quantityFactId = ($author.entities | Where-Object kind -eq 'resource' | Where-Object { $_.attributes.quantityFactId })[0].attributes.quantityFactId
$populationValue = ($author.facts | Where-Object id -eq $populationFactId).value
$quantityValue = ($author.facts | Where-Object id -eq $quantityFactId).value
$rangeProposal = @{schemaVersion=1;worldId=$author.worldId;requestId='bad-resource';expectedRevision=0;actor='@author';reason='验收非法资源变化';effects=@(@{type='fact.change';factId=$quantityFactId;expectedValue=$quantityValue;value=-1})}
$null = Invoke-Genesis @('apply', $worldFile, (Save-GenesisCase 'bad-resource.json' $rangeProposal)) $true
Assert-Genesis '资源范围法则约束后续事件，拒绝后存档不变' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $worldHash)
$populationProposal = @{schemaVersion=1;worldId=$author.worldId;requestId='bad-population';expectedRevision=0;actor='@author';reason='验收已展开成员约束';effects=@(@{type='fact.change';factId=$populationFactId;expectedValue=$populationValue;value=0})}
$null = Invoke-Genesis @('apply', $worldFile, (Save-GenesisCase 'bad-population.json' $populationProposal)) $true
Assert-Genesis '群体人数不能降到已展开成员以下' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $worldHash)
$enumeration = @($author.laws | Where-Object kind -eq 'oneOf')[0]
$enumCurrent = ($author.facts | Where-Object id -eq $enumeration.factId).value
$enumProposal = @{schemaVersion=1;worldId=$author.worldId;requestId='bad-domain';expectedRevision=0;actor='@author';reason='验收世界允许值';effects=@(@{type='fact.change';factId=$enumeration.factId;expectedValue=$enumCurrent;value='不存在的允许值'})}
$null = Invoke-Genesis @('apply', $worldFile, (Save-GenesisCase 'bad-domain.json' $enumProposal)) $true
Assert-Genesis '世界法则不会因作者提交而被绕过' ((Get-FileHash -LiteralPath $worldFile).Hash -eq $worldHash)
$validProposal = @{schemaVersion=1;worldId=$author.worldId;requestId='consume-one';expectedRevision=0;actor='@author';reason='作者明确记录消耗一单位铁料，此例不推导加工过程';effects=@(@{type='fact.change';factId=$quantityFactId;expectedValue=$quantityValue;value=($quantityValue-1)})}
$validReceipt = Invoke-Genesis @('apply', $worldFile, (Save-GenesisCase 'valid-change.json' $validProposal))
$restored = Invoke-Genesis @('view', $worldFile, '@author')
Assert-Genesis '合法状态变化可继续演绎，重开后目录和法则保持一致' ($validReceipt.revision -eq 1 -and ($restored.facts | Where-Object id -eq $quantityFactId).value -eq ($quantityValue-1) -and $restored.entities.Count -eq $author.entities.Count -and $restored.laws.Count -eq $author.laws.Count)
$blueprintHash = (Get-FileHash -LiteralPath $blueprintFile).Hash
$null = Invoke-Genesis @('construct', $blueprintFile, $blueprintFile) $true
$null = Invoke-Genesis @('confirm', $draftFile, $draftFile) $true
Assert-Genesis '输入文件不能被草案或正式存档覆盖' ((Get-FileHash -LiteralPath $blueprintFile).Hash -eq $blueprintHash -and (Get-FileHash -LiteralPath $draftFile).Hash -eq $draftHash)
$report = [pscustomobject]@{checks=$results.Count;passed=$results.Count;failed=0;runDirectory=$runRoot;results=$results}
$null = Save-GenesisCase 'genesis-cli-check.json' $report
$report | ConvertTo-Json -Depth 8
