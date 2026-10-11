[CmdletBinding()]
param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$utf8 = [System.Text.UTF8Encoding]::new($false)
$runRoot = Join-Path $PSScriptRoot ('runs\skeleton-cli-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$results = [System.Collections.Generic.List[object]]::new()
$calls = [System.Collections.Generic.List[object]]::new()
$inputs = [System.Collections.Generic.List[object]]::new()
$sourceHashes = [System.Collections.Generic.List[object]]::new()
$failure = $null
$actualEventCount = $null
$expectedEventPlan = @(
    [pscustomobject]@{actor='smith';effect='fact.change';factId='charcoal-quantity';kind='author-declared-consumption'},
    [pscustomobject]@{actor='smith';effect='fact.change';factId='cargo-pickup-permission';kind='owner-explicit-decision'},
    [pscustomobject]@{actor='@author';effect='person.add';kind='author-member-input'},
    [pscustomobject]@{actor='new-porter';module='ask';factId='cargo-registration-state'},
    [pscustomobject]@{actor='new-porter';module='travel';to='wharf'},
    [pscustomobject]@{actor='apprentice';module='ask';factId='cargo-location'},
    [pscustomobject]@{actor='apprentice';module='travel';to='warehouse'},
    [pscustomobject]@{actor='apprentice';module='observe';factId='cargo-handoff-status'}
)
$runtime = & (Join-Path $PSScriptRoot 'runtime.ps1')
$javaTool = $runtime.javaTool
$classPath = $runtime.classPath

function Read-Json([string]$Path) { return (Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json) }
function Copy-Json([object]$Value) { return ($Value | ConvertTo-Json -Depth 90 | ConvertFrom-Json) }
function Hash-File([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
function Save-Input([string]$Name, [object]$Value, [string]$Source = '') {
    $path = Join-Path $runRoot $Name
    [System.IO.File]::WriteAllText($path, ($Value | ConvertTo-Json -Depth 90), $utf8)
    $inputs.Add([pscustomobject]@{path=$path;source=$Source;expectedRevision=$Value.expectedRevision;requestId=$Value.requestId})
    return $path
}
function Comparable-Json([object]$Value) {
    if ($null -eq $Value) { return 'null' }
    if ($Value -is [array]) { return ('[' + (@($Value | ForEach-Object { Comparable-Json $_ }) -join ',') + ']') }
    if ($Value -is [System.Collections.IDictionary]) {
        $pairs = @($Value.Keys | Sort-Object | ForEach-Object { (ConvertTo-Json -InputObject ([string]$_) -Compress) + ':' + (Comparable-Json $Value[$_]) })
        return ('{' + ($pairs -join ',') + '}')
    }
    if ($Value -is [pscustomobject]) {
        $pairs = @($Value.PSObject.Properties.Name | Sort-Object | ForEach-Object { (ConvertTo-Json -InputObject ([string]$_) -Compress) + ':' + (Comparable-Json $Value.$_) })
        return ('{' + ($pairs -join ',') + '}')
    }
    return (ConvertTo-Json -InputObject $Value -Compress)
}
function Same-Json([object]$Left, [object]$Right) { return ((Comparable-Json $Left) -ceq (Comparable-Json $Right)) }
function Assert-Skeleton([string]$Name, [bool]$Condition) {
    $results.Add([pscustomobject]@{name=$Name;passed=$Condition})
    if (!$Condition) { throw "验证失败：$Name" }
}
function Invoke-Skeleton([string[]]$Arguments, [string]$Name, [string[]]$InputPaths = @(), [bool]$ExpectFailure = $false) {
    $stdout = Join-Path $runRoot ($Name + '.stdout.json'); $stderr = Join-Path $runRoot ($Name + '.stderr.txt')
    $previousPreference = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $lines = & $javaTool '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $classPath creativeworld.WorldCli @Arguments 2> $stderr
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    $text = $lines -join [Environment]::NewLine
    [System.IO.File]::WriteAllText($stdout, $text, $utf8)
    $calls.Add([pscustomobject]@{name=$Name;command='WorldCli';arguments=$Arguments;inputPaths=$InputPaths;stdout=$stdout;stderr=$stderr;exitCode=$code;expectedFailure=$ExpectFailure})
    if ($ExpectFailure) {
        Assert-Skeleton "$Name 独立进程以非零退出码拒绝" ($code -ne 0)
        return [pscustomobject]@{exitCode=$code;stderr=$stderr}
    }
    if ($code -ne 0) { throw "独立进程失败：$Name；退出码 $code；错误文件 $stderr" }
    return ($text | ConvertFrom-Json)
}
function Find-Row([object[]]$Rows, [string]$Field, [string]$Value) { return @($Rows | Where-Object { $_.$Field -ceq $Value })[0] }
function Fact([object]$View, [string]$Id) { return (Find-Row @($View.facts) 'id' $Id) }
function Known([object]$View, [string]$Id) { return (Find-Row @($View.knowledge) 'factId' $Id) }
function Stage([object]$Record, [string]$Id) { return @($Record.stages | Where-Object { $_.stage -ceq $Id -and $_.status -ceq 'completed' })[-1] }
function Requirement([object]$Report, [string]$Id) { return (Find-Row @($Report.requirements) 'id' $Id) }
function Assert-Privacy([object]$View, [object]$Author, [string]$Label) {
    $text = $View | ConvertTo-Json -Depth 90 -Compress
    $okay = !$View.PSObject.Properties['facts'] -and !$View.PSObject.Properties['characters'] -and !$View.PSObject.Properties['entities'] -and !$View.PSObject.Properties['laws']
    foreach ($nearby in @($View.nearby)) { $okay = $okay -and !$nearby.PSObject.Properties['privateProfile'] }
    foreach ($person in @($Author.characters | Where-Object { $_.id -ne $View.actor })) {
        foreach ($field in @('goal', 'concern')) { $secret = [string]$person.privateProfile.$field; if ($secret) { $okay = $okay -and !$text.Contains($secret) } }
    }
    $secret = Fact $Author 'registry-copy-origin'; if ($secret) { $okay = $okay -and !$text.Contains([string]$secret.value) }
    Assert-Skeleton "$Label 不包含其他人物私密资料、全量世界或幕后登记原因" $okay
}
function Read-Preparation([string]$WorldFile, [string]$RequestFile, [string]$Name) {
    $before = Hash-File $WorldFile; $inputBefore = Hash-File $RequestFile
    $report = Invoke-Skeleton @('prepare', $WorldFile, $RequestFile) $Name @($WorldFile, $RequestFile)
    Assert-Skeleton "$Name 只读且明确只检查声明条件" ((Hash-File $WorldFile) -ceq $before -and (Hash-File $RequestFile) -ceq $inputBefore -and $report.authorReport.checksScope -ceq 'declared-requirements-only' -and $report.actorReport.checksScope -ceq 'declared-requirements-only' -and !(($report.actorReport | ConvertTo-Json -Depth 90 -Compress).Contains('"worldEvidence"')))
    return $report
}

function Bound-Input([string]$Name, [object]$Source, [int]$Revision, [string]$Origin = '') {
    $copy = Copy-Json $Source; $copy.expectedRevision = $Revision
    return (Save-Input $Name $copy $Origin)
}
function Reject-Unchanged([string]$WorldFile, [object]$Input, [string]$Name) {
    $before = Hash-File $WorldFile; $inputFile = Save-Input ($Name + '.json') $Input
    $null = Invoke-Skeleton @('apply', $WorldFile, $inputFile) $Name @($WorldFile, $inputFile) $true
    Assert-Skeleton "$Name 整包拒绝后正式世界字节保持" ((Hash-File $WorldFile) -ceq $before)
}

try {
    if (!$SkipBuild) {
        $buildOut = Join-Path $runRoot 'build.stdout.txt'; $buildErr = Join-Path $runRoot 'build.stderr.txt'
        $previousPreference = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        try { & (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly > $buildOut 2> $buildErr; $buildCode = $LASTEXITCODE }
        finally { $ErrorActionPreference = $previousPreference }
        $calls.Add([pscustomobject]@{name='build';command='run.ps1';arguments=@('-BuildOnly');inputPaths=@();stdout=$buildOut;stderr=$buildErr;exitCode=$buildCode;expectedFailure=$false})
        Assert-Skeleton '本次 Java 21 编译成功' ($buildCode -eq 0)
    }
    Assert-Skeleton '独立进程引擎入口存在' ((Test-Path -LiteralPath $javaTool) -and (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'build\classes\creativeworld\WorldCli.class')))
    $exampleRoot = Join-Path $PSScriptRoot 'examples\skeleton'; $source = @{}
    foreach ($name in @('genesis.json','consume-charcoal.json','grant-permission.json','add-worker.json','worker-ask.json','worker-travel.json','apprentice-goal.json','preparation.json')) {
        $path = Join-Path $exampleRoot $name; $source[$name] = Read-Json $path
        $sourceHashes.Add([pscustomobject]@{path=$path;before=Hash-File $path;after=$null})
    }
    $blueprintFile = Save-Input 'blueprint.json' $source['genesis.json'] (Join-Path $exampleRoot 'genesis.json')
    $preparationFile = Save-Input 'preparation.json' $source['preparation.json'] (Join-Path $exampleRoot 'preparation.json')
    $draftFile = Join-Path $runRoot 'draft.json'; $worldFile = Join-Path $runRoot 'world.json'
    $null = Invoke-Skeleton @('construct', $blueprintFile, $draftFile) 'construct-full-world' @($blueprintFile)
    $draftBefore = Hash-File $draftFile
    $draftReport = Invoke-Skeleton @('prepare-draft', $draftFile, $preparationFile) 'prepare-draft' @($draftFile,$preparationFile)
    Assert-Skeleton '草案准备只读且未提前生成正式世界' (!(Test-Path -LiteralPath $worldFile) -and (Hash-File $draftFile) -ceq $draftBefore -and $draftReport.authorReport.status -ceq 'needsInput')
    $initial = Invoke-Skeleton @('confirm', $draftFile, $worldFile) 'confirm-full-world' @($draftFile)
    $initialAuthor = Invoke-Skeleton @('view', $worldFile, '@author') 'initial-author' @($worldFile)
    $initialApprentice = Invoke-Skeleton @('view', $worldFile, 'apprentice') 'initial-apprentice' @($worldFile)
    $initialKeeper = Invoke-Skeleton @('view', $worldFile, 'keeper') 'initial-keeper' @($worldFile)
    Assert-Skeleton '完整世界初始化保留六地点六人物两势力两群体三资源与年度时间' ($initial.revision -eq 0 -and @($initialAuthor.places).Count -eq 6 -and @($initialAuthor.characters).Count -eq 6 -and @($initialAuthor.entities | Where-Object kind -eq faction).Count -eq 2 -and @($initialAuthor.entities | Where-Object kind -eq group).Count -eq 2 -and @($initialAuthor.entities | Where-Object kind -eq resource).Count -eq 3 -and $initialAuthor.worldTime.year -eq 100)
    $boundariesOkay = $true
    foreach ($fact in @($initialAuthor.facts | Where-Object mode -eq state)) { $rows = @($initialAuthor.laws | Where-Object { $_.kind -ceq 'writeAccess' -and $_.factId -ceq $fact.id }); $boundariesOkay = $boundariesOkay -and $rows.Count -eq 1 }
    $permissionLaw = @($initialAuthor.laws | Where-Object { $_.kind -ceq 'writeAccess' -and $_.factId -ceq 'cargo-pickup-permission' })[0]
    Assert-Skeleton '每个状态都有作者声明的执行边界，收货人决定许可而非保管员代批' ($boundariesOkay -and @($permissionLaw.actors).Count -eq 1 -and $permissionLaw.actors[0] -ceq 'smith' -and (Fact $initialAuthor 'cargo-owner').value -ceq 'smith')
    $initialPreparation = Read-Preparation $worldFile $preparationFile 'initial-preparation'
    Assert-Skeleton '初始清单如实区分false许可、人物未知和未定义运输量' ((Requirement $initialPreparation.authorReport 'world-permission').status -ceq 'conditionUnmet' -and (Requirement $initialPreparation.actorReport 'actor-permission').status -ceq 'unknown' -and (Requirement $initialPreparation.authorReport 'transport-quantity').status -ceq 'worldMissing')
    foreach ($actor in @('apprentice','keeper')) {
        $bad = Copy-Json $source['grant-permission.json']; $bad.actor=$actor; $bad.requestId='reject-self-approval-' + $actor; $bad.expectedRevision=$initial.revision; $bad.PSObject.Properties.Remove('requiresKnown')
        Reject-Unchanged $worldFile $bad ('reject-self-approval-' + $actor)
    }
    $bad = Copy-Json $source['consume-charcoal.json']; $bad.requestId='reject-resource-with-unauthorized-registration'; $bad.expectedRevision=$initial.revision
    $bad.effects += [pscustomobject]@{type='fact.change';factId='cargo-registration-state';expectedValue='pending';value='registered'}
    Reject-Unchanged $worldFile $bad 'reject-resource-with-unauthorized-registration'
    $consumeFile = Bound-Input 'consume-charcoal.json' $source['consume-charcoal.json'] $initial.revision (Join-Path $exampleRoot 'consume-charcoal.json')
    $consumed = Invoke-Skeleton @('apply', $worldFile, $consumeFile) 'smith-consumes-and-counts' @($worldFile,$consumeFile)
    $afterConsume = Invoke-Skeleton @('view', $worldFile, '@author') 'after-consume-author' @($worldFile)
    $smith = Invoke-Skeleton @('view', $worldFile, 'smith') 'after-consume-smith' @($worldFile)
    $apprentice = Invoke-Skeleton @('view', $worldFile, 'apprentice') 'after-consume-apprentice' @($worldFile)
    Assert-Skeleton '作者明确输入消耗实际木炭8到7，林桂现场清点而学徒旧认识不被刷新' ((Fact $afterConsume 'charcoal-quantity').value -eq 7 -and (Known $smith 'charcoal-quantity').value -eq 7 -and (Known $smith 'charcoal-quantity').source -ceq '@world' -and (Known $smith 'charcoal-quantity').revision -eq $consumed.revision -and (Same-Json (Known $apprentice 'charcoal-quantity') (Known $initialApprentice 'charcoal-quantity')))
    $old = Invoke-Skeleton @('view-at', $worldFile, '@author', '0') 'before-consume-history-view' @($worldFile)
    Assert-Skeleton '过去资源8和原身份地点仍可重放，不重写历史' ((Fact $old 'charcoal-quantity').value -eq 8 -and (Same-Json $old $initialAuthor))
    $approvalFile = Bound-Input 'grant-permission.json' $source['grant-permission.json'] $consumed.revision (Join-Path $exampleRoot 'grant-permission.json')
    $approved = Invoke-Skeleton @('apply', $worldFile, $approvalFile) 'owner-explicit-approval' @($worldFile,$approvalFile)
    $afterApprove = Invoke-Skeleton @('view', $worldFile, '@author') 'after-approval-author' @($worldFile)
    $keeper = Invoke-Skeleton @('view', $worldFile, 'keeper') 'after-approval-keeper' @($worldFile)
    Assert-Skeleton '货主明确授予单项许可，不自动传播、不登记、不运走' ((Fact $afterApprove 'cargo-pickup-permission').value -is [bool] -and (Fact $afterApprove 'cargo-pickup-permission').value -and (Same-Json (Known $keeper 'cargo-pickup-permission') (Known $initialKeeper 'cargo-pickup-permission')) -and (Fact $afterApprove 'cargo-registration-state').value -ceq 'pending' -and (Fact $afterApprove 'cargo-location').value -ceq 'warehouse')
    $bad = Copy-Json $source['add-worker.json']; $bad.requestId='reject-member-over-population'; $bad.expectedRevision=$approved.revision
    $bad.effects += [pscustomobject]@{type='fact.change';factId='dock-population';expectedValue=12;value=1}
    Reject-Unchanged $worldFile $bad 'reject-member-over-population'
    $workerFile = Bound-Input 'add-worker.json' $source['add-worker.json'] $approved.revision (Join-Path $exampleRoot 'add-worker.json')
    $added = Invoke-Skeleton @('apply', $worldFile, $workerFile) 'author-adds-member' @($worldFile,$workerFile)
    $afterAdd = Invoke-Skeleton @('view', $worldFile, '@author') 'after-add-author' @($worldFile)
    $worker = Invoke-Skeleton @('view', $worldFile, 'new-porter') 'after-add-worker' @($worldFile)
    $group = Find-Row @($afterAdd.entities) 'id' 'dock-workers'
    Assert-Skeleton '作者新增人物与群体成员同包登记，展开人数符合原12人总数' (@($afterAdd.characters).Count -eq 7 -and @($group.refs | Where-Object { $_ -ceq 'new-porter' }).Count -eq 1 -and (Fact $afterAdd 'dock-population').value -eq 12 -and $worker.self.publicProfile.groupId -ceq 'dock-workers' -and $worker.self.place -ceq 'warehouse')
    $bad = [pscustomobject]@{schemaVersion=1;worldId=$initial.worldId;requestId='reject-duplicate-member';expectedRevision=$added.revision;actor='@author';reason='重复登记反例';effects=@([pscustomobject]@{type='group.member.add';groupId='dock-workers';personId='new-porter'})}
    Reject-Unchanged $worldFile $bad 'reject-duplicate-member'
    $askFile = Bound-Input 'worker-ask.json' $source['worker-ask.json'] $added.revision (Join-Path $exampleRoot 'worker-ask.json')
    $asked = Invoke-Skeleton @('step', $worldFile, 'ask', $askFile) 'new-worker-asks' @($worldFile,$askFile)
    $workerMessage = Invoke-Skeleton @('view', $worldFile, 'new-porter') 'new-worker-message' @($worldFile)
    Assert-Skeleton '新人物与保管员同地交谈生成其独立认识，消息不是自动登记' ((Known $workerMessage 'cargo-registration-state').source -ceq 'keeper' -and (Known $workerMessage 'cargo-registration-state').value -ceq 'pending' -and (Known $workerMessage 'cargo-registration-state').revision -eq $asked.revision)
    $travelFile = Bound-Input 'worker-travel.json' $source['worker-travel.json'] $asked.revision (Join-Path $exampleRoot 'worker-travel.json')
    $moved = Invoke-Skeleton @('step', $worldFile, 'travel', $travelFile) 'new-worker-travels' @($worldFile,$travelFile)
    $workerAfter = Invoke-Skeleton @('view', $worldFile, 'new-porter') 'reopened-worker-after-travel' @($worldFile)
    Assert-Skeleton '新成员按明确路线到码头，保存恢复仍保留之前取得的消息' ($workerAfter.self.place -ceq 'wharf' -and (Known $workerAfter 'new-porter-route-warehouse-wharf').value -is [bool] -and (Known $workerAfter 'new-porter-route-warehouse-wharf').value -and (Same-Json (Known $workerAfter 'cargo-registration-state') (Known $workerMessage 'cargo-registration-state')))
    $goalFile = Bound-Input 'apprentice-goal.json' $source['apprentice-goal.json'] $moved.revision (Join-Path $exampleRoot 'apprentice-goal.json')
    $journeyDirectory = Join-Path $runRoot 'apprentice-journey'
    $journey = Invoke-Skeleton @('pursue', $worldFile, $goalFile, $journeyDirectory, '8') 'default-host-apprentice-journey' @($worldFile,$goalFile)
    $history = Invoke-Skeleton @('history', $worldFile) 'reopened-history' @($worldFile); $actualEventCount=@($history.events).Count
    $finalAuthor = Invoke-Skeleton @('view', $worldFile, '@author') 'reopened-author' @($worldFile)
    $finalApprentice = Invoke-Skeleton @('view', $worldFile, 'apprentice') 'reopened-apprentice' @($worldFile)
    $reader = Invoke-Skeleton @('view', $worldFile, '@reader') 'reopened-reader' @($worldFile)
    $traceOkay = $actualEventCount -eq $expectedEventPlan.Count
    for ($i=0; $i -lt $expectedEventPlan.Count; $i++) {
        $event=$history.events[$i]; $expected=$expectedEventPlan[$i]
        $traceOkay=$traceOkay -and $event.proposal.actor -ceq $expected.actor -and $event.proposal.expectedRevision -eq ($event.revision-1) -and $event.worldTime.year -eq 100
        if ($expected.module) { $traceOkay=$traceOkay -and $event.submission.module -ceq $expected.module; if ($expected.factId) { $traceOkay=$traceOkay -and $event.submission.input.factId -ceq $expected.factId }; if ($expected.to) { $traceOkay=$traceOkay -and $event.submission.input.to -ceq $expected.to } }
        else { $traceOkay=$traceOkay -and !$event.PSObject.Properties['submission'] -and $event.proposal.effects[0].type -ceq $expected.effect; if ($expected.factId) { $traceOkay=$traceOkay -and $event.proposal.effects[0].factId -ceq $expected.factId } }
    }
    Assert-Skeleton '正式历史对应资源变化、货主决定、成员输入与五次人物行动，逐项核对而非只看数量' $traceOkay
    Assert-Skeleton '默认共同宿主仍依据学徒本人消息与路线完成ask/travel/observe' ($journey.status -ceq 'satisfied' -and $finalApprentice.self.place -ceq 'warehouse' -and (Known $finalApprentice 'cargo-location').source -ceq 'boatman' -and (Known $finalApprentice 'cargo-handoff-status').source -ceq '@world' -and (Known $finalApprentice 'cargo-handoff-status').revision -eq $history.revision)
    $identitiesOkay = (Same-Json $initialAuthor.places $finalAuthor.places) -and (Same-Json $initialAuthor.routes $finalAuthor.routes)
    foreach ($person in @($initialAuthor.characters)) { $current=Find-Row @($finalAuthor.characters) 'id' $person.id; $identitiesOkay=$identitiesOkay -and $person.name -ceq $current.name -and (Same-Json $person.privateProfile $current.privateProfile) }
    foreach ($fact in @($initialAuthor.facts | Where-Object mode -eq fixed)) { $identitiesOkay=$identitiesOkay -and (Same-Json (Fact $finalAuthor $fact.id) $fact) }
    Assert-Skeleton '既有姓名固定事实地理和私密经历保持，货物仍在货栈且登记未完成' ($identitiesOkay -and (Fact $finalAuthor 'cargo-location').value -ceq 'warehouse' -and (Fact $finalAuthor 'cargo-registration-state').value -ceq 'pending' -and (Fact $finalAuthor 'iron-quantity').value -eq 40)
    Assert-Privacy $finalApprentice $finalAuthor '学徒资料'; Assert-Privacy $workerAfter $finalAuthor '新成员资料'
    Assert-Skeleton '读者资料没有自动变成人物或作者全量资料' (!$reader.PSObject.Properties['facts'] -and !$reader.PSObject.Properties['characters'] -and @($reader.readerKnowledge).Count -eq 0)
    $bytesBefore=Hash-File $worldFile
    $againConsume=Invoke-Skeleton @('apply',$worldFile,$consumeFile) 'repeat-consumption' @($worldFile,$consumeFile)
    $againAdd=Invoke-Skeleton @('apply',$worldFile,$workerFile) 'repeat-member-add' @($worldFile,$workerFile)
    $againAsk=Invoke-Skeleton @('step',$worldFile,'ask',$askFile) 'repeat-worker-ask' @($worldFile,$askFile)
    $againTravel=Invoke-Skeleton @('step',$worldFile,'travel',$travelFile) 'repeat-worker-travel' @($worldFile,$travelFile)
    $againJourney=Invoke-Skeleton @('pursue',$worldFile,$goalFile,$journeyDirectory,'8') 'repeat-default-journey' @($worldFile,$goalFile)
    Assert-Skeleton '每个进程重新打开世界后恢复原回执与过程，重复不增事件也不改字节' ((Same-Json $againConsume $consumed) -and (Same-Json $againAdd $added) -and (Same-Json $againAsk $asked) -and (Same-Json $againTravel $moved) -and $againJourney.status -ceq 'satisfied' -and (Hash-File $worldFile) -ceq $bytesBefore)
    $preparation = Read-Preparation $worldFile $preparationFile 'final-preparation'
    Assert-Skeleton '世界单项许可成立不刷新人物消息，登记与运送量缺口仍阻止完整交接' ((Requirement $preparation.authorReport 'world-permission').status -ceq 'ready' -and (Requirement $preparation.actorReport 'actor-permission').status -ceq 'unknown' -and (Requirement $preparation.actorReport 'onsite-handoff').status -ceq 'ready' -and (Requirement $preparation.authorReport 'registration').status -ceq 'conditionUnmet' -and (Requirement $preparation.authorReport 'transport-quantity').status -ceq 'worldMissing' -and $preparation.authorReport.status -ceq 'needsInput' -and $preparation.actorReport.status -ceq 'needsInput')
    foreach ($sourceHash in $sourceHashes) { $sourceHash.after=Hash-File $sourceHash.path }
    Assert-Skeleton '八份源输入字节不变，实际版本绑定只写运行副本' (@($sourceHashes | Where-Object { $_.before -cne $_.after }).Count -eq 0)
} catch {
    $failure=$_.Exception.Message
    if (@($results | Where-Object { !$_.passed }).Count -eq 0) { $results.Add([pscustomobject]@{name='独立进程完整骨架闭环';passed=$false;error=$failure}) }
} finally {
    foreach ($sourceHash in $sourceHashes) { if (Test-Path -LiteralPath $sourceHash.path) { $sourceHash.after=Hash-File $sourceHash.path } }
    $report=[pscustomobject]@{schemaVersion=1;kind='skeleton-cli-check';checks=$results.Count;passed=@($results | Where-Object passed).Count;failed=@($results | Where-Object { !$_.passed }).Count;runDirectory=$runRoot;results=$results;calls=$calls;inputs=$inputs;sourceInputs=$sourceHashes;expectedEventPlan=$expectedEventPlan;expectedCommittedEvents=$expectedEventPlan.Count;actualCommittedEvents=$actualEventCount;buildSkipped=[bool]$SkipBuild;declaredExecutionBoundaries=$true;autonomousPsychologyEvaluated=$false;socialPowerInferenceImplemented=$false;automaticProductionEvaluated=$false;actualModelCalled=$false;proseQualityEvaluated=$false;cargoDelivered=$false;componentEvidence='CLI默认版本1装配；扩展v2跨阶段连续过程由独立SkeletonCheck验收';checksScope='declared-state-writes-real-resource-change-member-extension-and-default-host-recovery';error=$failure}
    $reportFile=Join-Path $runRoot 'skeleton-cli-check.json'
    [System.IO.File]::WriteAllText($reportFile,($report | ConvertTo-Json -Depth 90),$utf8)
    [pscustomobject]@{passed=$report.passed;failed=$report.failed;actualCommittedEvents=$actualEventCount;runDirectory=$runRoot;reportFile=$reportFile;error=$failure} | ConvertTo-Json -Depth 5
}
if ($failure) { exit 1 }
exit 0

