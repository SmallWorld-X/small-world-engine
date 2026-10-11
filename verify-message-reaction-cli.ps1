[CmdletBinding()]
param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$utf8 = [System.Text.UTF8Encoding]::new($false)
$runRoot = Join-Path $PSScriptRoot ('runs\message-reaction-cli-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$results = [System.Collections.Generic.List[object]]::new()
$calls = [System.Collections.Generic.List[object]]::new()
$inputs = [System.Collections.Generic.List[object]]::new()
$sourceHashes = [System.Collections.Generic.List[object]]::new()
$failure = $null
$actualEventCount = $null
$expectedEventPlan = @(
    [pscustomobject]@{actor='apprentice';module='ask';factId='cargo-location';source='boatman'},
    [pscustomobject]@{actor='apprentice';module='travel';to='warehouse'},
    [pscustomobject]@{actor='apprentice';module='observe';factId='cargo-handoff-status';source='@world'},
    [pscustomobject]@{actor='keeper';module='ask';factId='cargo-handoff-status';source='apprentice'},
    [pscustomobject]@{actor='keeper';module='message-response';moduleVersion='1'}
)
$javaRoot = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $env:JAVA_HOME } else { 'D:\JavaJDK\jdk-21.0.5' }
$javaTool = Join-Path $javaRoot 'bin\java.exe'
$jarRoot = Join-Path $env:USERPROFILE '.m2\repository\com\fasterxml\jackson\core'
$dependencies = @('jackson-databind', 'jackson-core', 'jackson-annotations') | ForEach-Object { Join-Path $jarRoot "$_\2.19.2\$_-2.19.2.jar" }
$classPath = (@((Join-Path $PSScriptRoot 'build\classes')) + $dependencies) -join ';'

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
function Assert-Reaction([string]$Name, [bool]$Condition) {
    $results.Add([pscustomobject]@{name=$Name;passed=$Condition})
    if (!$Condition) { throw "验证失败：$Name" }
}
function Invoke-Reaction([string[]]$Arguments, [string]$Name, [string[]]$InputPaths = @(), [bool]$ExpectFailure = $false) {
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
        Assert-Reaction "$Name 独立进程以非零退出码拒绝" ($code -ne 0)
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
    Assert-Reaction "$Label 不包含其他人物私密资料、全量世界或幕后登记原因" $okay
}
function Read-Preparation([string]$WorldFile, [string]$RequestFile, [string]$Name) {
    $before = Hash-File $WorldFile; $inputBefore = Hash-File $RequestFile
    $report = Invoke-Reaction @('prepare', $WorldFile, $RequestFile) $Name @($WorldFile, $RequestFile)
    Assert-Reaction "$Name 只读且明确只检查声明条件" ((Hash-File $WorldFile) -ceq $before -and (Hash-File $RequestFile) -ceq $inputBefore -and $report.authorReport.checksScope -ceq 'declared-requirements-only' -and $report.actorReport.checksScope -ceq 'declared-requirements-only' -and !(($report.actorReport | ConvertTo-Json -Depth 90 -Compress).Contains('"worldEvidence"')))
    return $report
}

try {
    if (!$SkipBuild) {
        $buildOut = Join-Path $runRoot 'build.stdout.txt'; $buildErr = Join-Path $runRoot 'build.stderr.txt'
        $previousPreference = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        try { & (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly > $buildOut 2> $buildErr; $buildCode = $LASTEXITCODE }
        finally { $ErrorActionPreference = $previousPreference }
        $calls.Add([pscustomobject]@{name='build';command='run.ps1';arguments=@('-BuildOnly');inputPaths=@();stdout=$buildOut;stderr=$buildErr;exitCode=$buildCode;expectedFailure=$false})
        Assert-Reaction '本次 Java 21 编译成功' ($buildCode -eq 0)
    }
    Assert-Reaction '独立进程引擎入口存在' ((Test-Path -LiteralPath $javaTool) -and (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'build\classes\creativeworld\WorldCli.class')))
    $exampleRoot = Join-Path $PSScriptRoot 'examples\message-reaction'; $source = @{}
    foreach ($name in @('genesis.json', 'apprentice-goal.json', 'keeper-ask.json', 'response.json', 'preparation.json')) {
        $path = Join-Path $exampleRoot $name; $source[$name] = Read-Json $path
        $sourceHashes.Add([pscustomobject]@{path=$path;before=Hash-File $path;after=$null})
    }
    $blueprintFile = Save-Input 'blueprint.json' $source['genesis.json'] (Join-Path $exampleRoot 'genesis.json')
    $preparationFile = Save-Input 'preparation.json' $source['preparation.json'] (Join-Path $exampleRoot 'preparation.json')
    $draftFile = Join-Path $runRoot 'draft.json'; $worldFile = Join-Path $runRoot 'world.json'
    $null = Invoke-Reaction @('construct', $blueprintFile, $draftFile) 'construct' @($blueprintFile)
    $draftHash = Hash-File $draftFile
    $draftReport = Invoke-Reaction @('prepare-draft', $draftFile, $preparationFile) 'prepare-draft' @($draftFile, $preparationFile)
    Assert-Reaction '草案准备分析不确认正式世界且不改草案' (!(Test-Path -LiteralPath $worldFile) -and $draftReport.source.kind -ceq 'draft' -and (Hash-File $draftFile) -ceq $draftHash)
    $initial = Invoke-Reaction @('confirm', $draftFile, $worldFile) 'confirm' @($draftFile)
    $initialAuthor = Invoke-Reaction @('view', $worldFile, '@author') 'initial-author' @($worldFile)
    $initialApprentice = Invoke-Reaction @('view', $worldFile, 'apprentice') 'initial-apprentice' @($worldFile)
    $initialKeeper = Invoke-Reaction @('view', $worldFile, 'keeper') 'initial-keeper' @($worldFile)
    $initialHistory = Invoke-Reaction @('history', $worldFile) 'initial-history' @($worldFile)
    $factions = @($initialAuthor.entities | Where-Object kind -eq 'faction'); $groups = @($initialAuthor.entities | Where-Object kind -eq 'group'); $resources = @($initialAuthor.entities | Where-Object kind -eq 'resource')
    Assert-Reaction '上一轮完整世界保留6地点6人物35事实2势力2群体3资源10法则，确认不虚构事件' ($initial.counts.places -eq 6 -and $initial.counts.characters -eq 6 -and @($initialAuthor.facts).Count -eq 35 -and $factions.Count -eq 2 -and $groups.Count -eq 2 -and $resources.Count -eq 3 -and @($initialAuthor.laws).Count -eq 10 -and $initial.revision -eq 0 -and @($initialHistory.events).Count -eq 0)
    $allIds = @($initialAuthor.places.id) + @($initialAuthor.characters.id) + @($initialAuthor.entities.id)
    $referencesOkay = $true
    foreach ($entity in @($initialAuthor.entities)) {
        $referencesOkay = $referencesOkay -and @($entity.refs | Where-Object { $_ -notin $allIds }).Count -eq 0
        if ($entity.kind -eq 'group') { $population = Fact $initialAuthor $entity.attributes.populationFactId; $members = @($entity.refs | Where-Object { $_ -in @($initialAuthor.characters.id) }); $referencesOkay = $referencesOkay -and $population.value -ge $members.Count }
        if ($entity.kind -eq 'resource') { $referencesOkay = $referencesOkay -and $null -ne (Fact $initialAuthor $entity.attributes.quantityFactId) -and @($initialAuthor.laws | Where-Object factId -eq $entity.attributes.quantityFactId).Count -gt 0 }
    }
    foreach ($law in @($initialAuthor.laws)) { $referencesOkay = $referencesOkay -and $null -ne (Fact $initialAuthor $law.factId) }
    Assert-Reaction '地域资源群体与法则仍关联真实目录，回应策略只是本人私有资料' ($referencesOkay -and $initialKeeper.self.privateProfile.responsePolicy.goalId -ceq 'safe-handoff-explanation' -and !$initialApprentice.self.privateProfile.PSObject.Properties['responsePolicy'])
    Assert-Privacy $initialApprentice $initialAuthor '初始学徒视图'; Assert-Privacy $initialKeeper $initialAuthor '初始保管员视图'
    $initialPreparation = Read-Preparation $worldFile $preparationFile 'initial-preparation'
    Assert-Reaction '起点学徒未收到位置和许可，未登记且没有本次运输数量' ((Requirement $initialPreparation.actorReport 'location-message').status -ceq 'unknown' -and (Requirement $initialPreparation.actorReport 'actor-permission').status -ceq 'unknown' -and (Requirement $initialPreparation.authorReport 'world-registration').status -ceq 'conditionUnmet' -and (Requirement $initialPreparation.authorReport 'transport-quantity').status -ceq 'worldMissing')

    $goal = Copy-Json $source['apprentice-goal.json']; $start = Invoke-Reaction @('inspect', $worldFile) 'goal-start' @($worldFile); $goal.expectedRevision = $start.revision
    $goalFile = Save-Input 'apprentice-goal.json' $goal (Join-Path $exampleRoot 'apprentice-goal.json'); $journeyDirectory = Join-Path $runRoot 'apprentice-journey'
    $journey = Invoke-Reaction @('pursue', $worldFile, $goalFile, $journeyDirectory, '8') 'apprentice-pursue' @($worldFile, $goalFile)
    $discoveryHistory = Invoke-Reaction @('history', $worldFile) 'discovery-history' @($worldFile)
    Assert-Reaction '学徒目标自动生成询问前往观察三次行动，不把停止记录写成事件' ($journey.status -ceq 'satisfied' -and @($discoveryHistory.events).Count -eq 3 -and ($discoveryHistory.events.submission.module -join ',') -ceq 'ask,travel,observe' -and @($discoveryHistory.events | Where-Object { $_.proposal.actor -cne 'apprentice' }).Count -eq 0 -and !$goal.scenario.PSObject.Properties['candidates'])
    $afterAsk = Invoke-Reaction @('view-at', $worldFile, 'apprentice', [string]$discoveryHistory.events[0].revision) 'after-first-ask' @($worldFile)
    $afterTravel = Invoke-Reaction @('view-at', $worldFile, 'apprentice', [string]$discoveryHistory.events[1].revision) 'after-travel' @($worldFile)
    $discovered = Invoke-Reaction @('view', $worldFile, 'apprentice') 'discovered-apprentice' @($worldFile)
    $routeProofs = @($afterAsk.self.privateProfile.knownRoutes | Where-Object { $_.from -ceq 'wharf' -and $_.to -ceq 'warehouse' } | ForEach-Object { Known $afterAsk $_.evidenceFactId } | Where-Object { $_.value -is [bool] -and $_.value })
    $observation = Known $discovered 'cargo-handoff-status'
    Assert-Reaction '实际路径和本人路线依据支持前往，取得位置消息后才获得独立现场认识' ((Known $afterAsk 'cargo-location').source -ceq 'boatman' -and !(Known $afterAsk 'cargo-handoff-status') -and $routeProofs.Count -gt 0 -and @($initialAuthor.routes | Where-Object { $_.from -ceq 'wharf' -and $_.to -ceq 'warehouse' }).Count -eq 1 -and $afterTravel.self.place -ceq 'warehouse' -and $discovered.self.place -ceq 'warehouse' -and $observation.source -ceq '@world' -and $observation.revision -eq $discoveryHistory.revision)

    $ask = Copy-Json $source['keeper-ask.json']; $askStart = Invoke-Reaction @('inspect', $worldFile) 'keeper-ask-start' @($worldFile); $ask.expectedRevision = $askStart.revision
    $askFile = Save-Input 'keeper-ask.json' $ask (Join-Path $exampleRoot 'keeper-ask.json')
    $askReceipt = Invoke-Reaction @('step', $worldFile, 'ask', $askFile) 'keeper-receives-message' @($worldFile, $askFile)
    $receiver = Invoke-Reaction @('view', $worldFile, 'keeper') 'keeper-after-message' @($worldFile); $message = Known $receiver 'cargo-handoff-status'
    Assert-Reaction '保管员询问同地学徒只取得其快照，不冒充自己现场观察或原始证据链' ($ask.actor -ceq 'keeper' -and $ask.target -ceq 'apprentice' -and $receiver.self.place -ceq $discovered.self.place -and $message.source -ceq 'apprentice' -and $message.revision -eq $askReceipt.revision -and $message.value -ceq $observation.value -and $message.asOfYear -eq $observation.asOfYear)
    $response = Copy-Json $source['response.json']; $response.expectedRevision = $receiver.revision; $response.scenario.message.receivedRevision = $message.revision
    $responseFile = Save-Input 'response.json' $response (Join-Path $exampleRoot 'response.json'); $responseRecord = Join-Path $runRoot 'keeper-response-record.json'
    $record = Invoke-Reaction @('experience', $worldFile, $responseFile, $responseRecord) 'keeper-response' @($worldFile, $responseFile)
    $candidates = @((Stage $record 'candidates').output.candidates); $selected = (Stage $record 'select').output.candidateId
    $declaredRule = Find-Row @($initialKeeper.self.privateProfile.responsePolicy.rules) 'id' $selected
    Assert-Reaction '消息管线五阶段确实执行，两个完整规则按优先级选，未知幕后条件不选' ($record.status -ceq 'completed' -and @($record.stages | Where-Object status -eq 'completed').Count -eq 5 -and $candidates.Count -eq 2 -and $selected -ceq 'explain-held-cargo' -and @($candidates | Where-Object id -eq 'hidden-note-must-not-drive-response').Count -eq 0 -and (Stage $record 'resolve').output.action.module -ceq 'message-response' -and (Stage $record 'resolve').output.action.moduleVersion -ceq '1')

    $finalAuthor = Invoke-Reaction @('view', $worldFile, '@author') 'reopened-author' @($worldFile)
    $finalApprentice = Invoke-Reaction @('view', $worldFile, 'apprentice') 'reopened-apprentice' @($worldFile)
    $finalKeeper = Invoke-Reaction @('view', $worldFile, 'keeper') 'reopened-keeper' @($worldFile)
    $finalReader = Invoke-Reaction @('view', $worldFile, '@reader') 'reopened-reader' @($worldFile)
    $history = Invoke-Reaction @('history', $worldFile) 'reopened-history' @($worldFile); $actualEventCount = @($history.events).Count
    $responseEvent = @($history.events)[-1]; $effects = @($responseEvent.proposal.effects)
    $permission = Known $finalApprentice 'cargo-pickup-permission'; $ruleKnowledge = Known $finalApprentice 'cargo-handoff-rule'
    $relation = Find-Row @($finalKeeper.relationships) 'to' 'apprentice'
    Assert-Reaction '同一回应事务分享false许可和交接规则，关系由声明规则提供且留依据' ($effects.Count -eq 3 -and $effects[0].type -ceq 'knowledge.share' -and $effects[1].type -ceq 'knowledge.share' -and $effects[2].type -ceq 'relationship.change' -and $permission.value -is [bool] -and !$permission.value -and $permission.source -ceq 'keeper' -and $permission.revision -eq $record.receipt.revision -and $ruleKnowledge.source -ceq 'keeper' -and $ruleKnowledge.revision -eq $record.receipt.revision -and $relation.value -ceq $declaredRule.relationshipValue -and ![string]::IsNullOrWhiteSpace([string]$relation.basis) -and $relation.revision -eq $record.receipt.revision)
    Assert-Reaction '转述与分享来源分别保留，反馈绑定保管员真实回执版本' ((Same-Json (Known $finalApprentice 'cargo-handoff-status') $observation) -and (Same-Json (Known $finalKeeper 'cargo-handoff-status') $message) -and $permission.asOfYear -eq (Known $initialKeeper 'cargo-pickup-permission').asOfYear -and (Same-Json $record.finalView $finalKeeper) -and (Same-Json (Stage $record 'feedback').output.receipt $record.receipt))
    $traceOkay = $actualEventCount -eq $expectedEventPlan.Count
    for ($i = 0; $i -lt $expectedEventPlan.Count; $i++) {
        $event = $history.events[$i]; $expected = $expectedEventPlan[$i]
        $traceOkay = $traceOkay -and $event.proposal.actor -ceq $expected.actor -and $event.submission.module -ceq $expected.module -and $event.proposal.expectedRevision -eq ($event.revision - 1)
        if ($expected.moduleVersion) { $traceOkay = $traceOkay -and $event.submission.moduleVersion -ceq $expected.moduleVersion }
    }
    Assert-Reaction '正式历史只有声明的五次人物行动，无作者登记事件与重复提交' $traceOkay
    $identityOkay = Same-Json $initialAuthor.places $finalAuthor.places
    foreach ($person in @($initialAuthor.characters)) { $current = Find-Row @($finalAuthor.characters) 'id' $person.id; $identityOkay = $identityOkay -and $person.name -ceq $current.name -and (Same-Json $person.privateProfile $current.privateProfile); if ($person.id -ne 'apprentice') { $identityOkay = $identityOkay -and $current.place -ceq $person.place } }
    Assert-Reaction '世界事实身份地点和共同年保持，没有登记放行搬运或自动心理改写' ($identityOkay -and (Same-Json $initialAuthor.facts $finalAuthor.facts) -and (Fact $finalAuthor 'cargo-location').value -ceq 'warehouse' -and (Fact $finalAuthor 'cargo-registration-state').value -ceq 'pending' -and !(Fact $finalAuthor 'cargo-pickup-permission').value -and (Fact $finalAuthor 'iron-quantity').value -eq 40 -and $finalAuthor.worldTime.year -eq 100 -and @($history.events | Where-Object { $_.worldTime.year -ne 100 }).Count -eq 0)
    Assert-Privacy $finalApprentice $finalAuthor '回应后学徒视图'; Assert-Privacy $finalKeeper $finalAuthor '回应后保管员视图'
    Assert-Reaction '读者不因人物沟通自动获得作者秘密或全部人物认识' (!$finalReader.PSObject.Properties['characters'] -and !$finalReader.PSObject.Properties['knowledge'] -and @($finalReader.readerKnowledge).Count -eq 0)
    $beforeRepeat = Hash-File $worldFile
    $again = Invoke-Reaction @('experience', $worldFile, $responseFile, $responseRecord) 'repeat-keeper-response' @($worldFile, $responseFile)
    $againAsk = Invoke-Reaction @('step', $worldFile, 'ask', $askFile) 'repeat-keeper-ask' @($worldFile, $askFile)
    $againJourney = Invoke-Reaction @('pursue', $worldFile, $goalFile, $journeyDirectory, '8') 'repeat-discovery' @($worldFile, $goalFile)
    Assert-Reaction '独立进程重开三条已完成过程均恢复原结果，世界字节保持' ((Same-Json $again.receipt $record.receipt) -and (Same-Json $againAsk $askReceipt) -and $againJourney.status -ceq 'satisfied' -and (Hash-File $worldFile) -ceq $beforeRepeat)
    $bad = [pscustomobject]@{schemaVersion=1;worldId=$initial.worldId;requestId='reject-replaced-speaker';expectedRevision=$history.revision;actor='apprentice';reason='隔离验收禁止替保管员发言';effects=@([pscustomobject]@{type='knowledge.share';from='keeper';to='apprentice';factId='cargo-pickup-permission'})}
    $badFile = Save-Input 'reject-replaced-speaker.json' $bad
    $null = Invoke-Reaction @('apply', $worldFile, $badFile) 'reject-replaced-speaker' @($worldFile, $badFile) $true
    Assert-Reaction '冒名分享被内核拒绝且世界文件仍保持' ((Hash-File $worldFile) -ceq $beforeRepeat)
    $finalPreparation = Read-Preparation $worldFile $preparationFile 'final-preparation'
    Assert-Reaction '回应让false许可成为明确认识，仍未登记未放行且没有运输量，不能称送达' ((Requirement $finalPreparation.actorReport 'actor-permission').status -ceq 'conditionUnmet' -and (Requirement $finalPreparation.actorReport 'actor-permission').actorEvidence.value -is [bool] -and !(Requirement $finalPreparation.actorReport 'actor-permission').actorEvidence.value -and (Requirement $finalPreparation.actorReport 'received-rule').status -ceq 'ready' -and (Requirement $finalPreparation.authorReport 'world-registration').status -ceq 'conditionUnmet' -and (Requirement $finalPreparation.authorReport 'transport-quantity').status -ceq 'worldMissing' -and !(Requirement $finalPreparation.actorReport 'transport-quantity') -and $finalPreparation.actorReport.status -ceq 'needsInput' -and $finalPreparation.authorReport.status -ceq 'needsInput')
    foreach ($sourceHash in $sourceHashes) { $sourceHash.after = Hash-File $sourceHash.path }
    Assert-Reaction '所有源蓝图目标回应与准备文件字节保持，仅运行副本绑定真实版本' (@($sourceHashes | Where-Object { $_.before -cne $_.after }).Count -eq 0)
} catch {
    $failure = $_.Exception.Message
    if (@($results | Where-Object { !$_.passed }).Count -eq 0) { $results.Add([pscustomobject]@{name='独立进程消息回应闭环';passed=$false;error=$failure}) }
} finally {
    foreach ($sourceHash in $sourceHashes) { if (Test-Path -LiteralPath $sourceHash.path) { $sourceHash.after = Hash-File $sourceHash.path } }
    $report = [pscustomobject]@{schemaVersion=1;kind='message-reaction-cli-check';checks=$results.Count;passed=@($results | Where-Object passed).Count;failed=@($results | Where-Object { !$_.passed }).Count;runDirectory=$runRoot;results=$results;calls=$calls;inputs=$inputs;sourceInputs=$sourceHashes;expectedEventPlan=$expectedEventPlan;expectedCommittedEvents=$expectedEventPlan.Count;actualCommittedEvents=$actualEventCount;buildSkipped=[bool]$SkipBuild;declaredResponsePolicy=$true;autonomousPsychologyEvaluated=$false;actualModelCalled=$false;proseQualityEvaluated=$false;cargoDelivered=$false;checksScope='actual-message-snapshots-and-declared-response-policy';error=$failure}
    $reportFile = Join-Path $runRoot 'message-reaction-cli-check.json'
    [System.IO.File]::WriteAllText($reportFile, ($report | ConvertTo-Json -Depth 90), $utf8)
    [pscustomobject]@{passed=$report.passed;failed=$report.failed;actualCommittedEvents=$actualEventCount;runDirectory=$runRoot;reportFile=$reportFile;error=$failure} | ConvertTo-Json -Depth 5
}
if ($failure) { exit 1 }
exit 0
