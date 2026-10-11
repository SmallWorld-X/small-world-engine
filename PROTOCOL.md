# 0.1 输入与事件格式

> 发布阅读说明：下文的本地历史报告位于忽略的 build 或 runs 目录，不随源码仓库上传。它们记录开发时的验证范围；运行对应脚本会生成你自己的新目录和报告。本轮复核见[开发版验证记录](RELEASE-CHECKS.md)。

当前协议版本 `schemaVersion: 1`，产品试验版号 `0.1`。这是两个不同用途的版本。

行为版本通过可选 `moduleVersion` 字段、构造版本通过蓝图可选 `constructors` 字段扩充协议，`schemaVersion` 仍为 1。新软件能读取缺少这些字段的旧记录；这不承诺旧软件能读取带新字段的显式版本记录。

`writeAccess`、`changeRequires` 法则与 `group.member.add` 效果仍使用协议版本1；新增状态回应使用独立行为 `message-state-response` 版本1。新软件能读取没有这些声明与效果的旧存档；旧软件不支持新增 kind/type 或行为时应明确拒绝，不承诺向后读取新内容。

## 初始世界

见 `examples/river-town.json`。必填字段为 `schemaVersion/worldId/title/places/characters/facts`；可选 `relationships/routes/entities/laws/worldTime`。旧资料缺省目录字段时按空目录处理，缺省时间时不创建默认时钟。

- 地点：`id/name`。
- 人物：`id/name/place/publicProfile/privateProfile`。无时钟旧世界仍保留原资料；有年度时钟时可选顶层 `birthYear`，年龄由共同时间推导，未知出生年输出 `age:null`。
- 事实：`id/subject/predicate/value/mode/knownBy`，可选 `observableAt`。值为 JSON 标量；subject 是逻辑对象键，允许货物等尚无专用类型的对象。
- `mode: fixed` 是不可改写的设定或历史事实；`state` 是能通过新事件改变的当前状态。
- `knownBy` 明确初始化哪些人物知道此事实；`observableAt` 明确在哪些地点可以现场观察，不从事实名字猜测可见性。
- 路线：`from/to` 是有向边。存在空 routes 表示跨地点尚无通路；缺省 routes 表示未配置空间拓扑。
- 初始关系：`from/to/value`，可选非空 `basis`。依据保留在种子和作者视图中；无时钟人物视图沿用 `to/value/revision`，年度世界再提供 `occurredAtYear` 及已有 `basis`。

### 共同年度时间

`worldTime:{unit:"year",year:100}` 声明世界纪年，`year` 为非负 int。本轮只支持明确的年度单位，不隐含月、日或365天换算。`revision` 是提交序号，`worldTime.year` 是世界年代；同年可以提交多个事件。

年度人物的 `birthYear` 为非负 int，不能晚于当前年份；没有年度时钟不能填出生年。年龄只从顶层出生年推导，年度 `publicProfile/privateProfile` 顶层不接受另一个 `age/birthYear`。人物资料的 `publicProfileAsOfYear/privateProfileAsOfYear` 由内核记录，视图给出各自 `current/historical` 状态。公开同地投影只附公共快照时间状态及年龄，不附他人私有快照或出生年。

时间推进不自动生成十年生活。已有地点、道路、位置及状态事实持续沿用，直到对应合法事件改变；人物职业、外貌与心情的描述保留当时资料，跨年显示为历史快照。当前没有资料快照更新事件。

知识另有 `learnedAtYear/asOfYear`：前者是本次获知年，后者是证据所属年。现场观察两者为当前年；转述保持来源快照的旧证据年，只更新本次获知年。关系、个人推测附 `occurredAtYear`；状态事实定义和变化附 `updatedAtYear`，表示最后更新年，不表示自动过期。

### 实体目录和法则

实体：`{id,kind,name,attributes:{},refs:[]}`。`kind` 为 `region/resource/faction/group`；标识与人物、地点全局唯一，引用只能指向已有地点、人物或实体，允许前向引用后统一检查。实体定义在0.1中固定，群体成员引用可由下述正式效果增量登记；作者视图可读取，人物与读者不会自动得到目录。

- 资源的 `attributes.quantityFactId` 可选；存在时引用有限、非负数值事实。
- 群体的 `attributes.populationFactId` 必填，引用非负整数 `state` 事实；`refs` 中的人物代表已展开成员，总人数不得少于他们。
- 人物若有 `publicProfile.groupId`，该群体必须存在且登记该人物。
- 地理说明、数量单位、势力职能等属性保留明确资料；程序不从这些说明文本推断物理法则。

法则为以下四种之一，标识唯一且引用已有事实：

```json
{"id":"iron-bound","kind":"range","factId":"iron-quantity","min":0,"max":100}
```

```json
{"id":"cargo-domain","kind":"oneOf","factId":"cargo-location","values":["wharf","warehouse","forge"]}
```

```json
{"id":"pickup-writers","kind":"writeAccess","factId":"cargo-pickup-permission","actors":["smith"]}
```

```json
{"id":"registration-requires","kind":"changeRequires","factId":"cargo-registration-status","whenEquals":"registered","conditions":[{"factId":"cargo-record-ready","equals":true}],"actorCondition":{"predicate":"registration-role","equals":"assistant"}}
```

以上是字段示意，所引用事实须在起点实际声明，不能仅凭示例名称认定其存在或条件满足。

`range` 至少有一个有限数字边界，下界不高于上界且包含边界。`oneOf` 非空，取值为不重复的规范化 JSON 标量。这些约束在初始世界、整笔事务全部效果之后及历史重放中检查；违反时拒绝整笔提交。数量约束不等于已实现加工守恒或运输过程。

`writeAccess` 严格为 `id/kind/factId/actors`，必须引用 `state` 事实；`actors` 为不重复的既有普通人物标识，不能填 `@author` 等保留身份。同一事实最多声明一条写权限法则，可同时受数值或枚举法则约束。空 `actors` 合法，此时仅作者能改写该状态。人物执行 `fact.change` 时须在名单中；作者以 `actor:"@author"` 保留明确操作权限，仍须通过旧值、版本与其他法则检查。法则当前只能在起点声明，不能运行期修改。

未声明 `writeAccess` 的事实维持原兼容语义，不能据此声称具备业务写权限保障；新世界的关键状态应明确声明。写权限只控制状态写入，不从职务或势力文本推导货物所有权、委托、命令有效性、资源分配或对方是否执行。

`changeRequires` 严格为必填 `id/kind/factId/conditions`，可选 `whenEquals/actorCondition`，不接受其他字段。目标须为既有 `state` 事实。`conditions` 为非空数组，每项严格为 `factId/equals`，引用已有事实，同一法则内条件事实不能重复。`whenEquals` 存在时只检查新值与它规范化相等的 `fact.change`；省略时检查该目标的每次变化。同一目标可有多条前提法则，命中的法则须全部满足。

`actorCondition` 严格为 `predicate/equals`，从整笔提交开始的事实中查找 `subject` 等于行动者且 `predicate` 相同的唯一事实；缺失或值不符则拒绝。`equals/whenEquals` 须为非 null、非空白的 JSON 标量，false 与0有效；未知事实值不能匹配。检查使用权威事实，不把人物认识、职业文本或 `reason` 当作真值。作者也须通过前提；它与 `writeAccess`、旧值及版本检查共同适用。

全部命中的变化前提先按整笔提案开始的快照检查，再执行效果。不能在同笔提案先授予身份、先定义依据或先修改条件，再靠效果顺序取得资格。它是变更前提，不是状态改变后永久成立的不变量；初始化只检查法则结构和引用，不执行授予过程。起点已有身份是作者确认的背景，不能称为已验证取得过程；后补受控身份使用预先声明的状态槽与当下事件。法则固定于起点，没有运行期追加或修改效果。没有这项法则的旧存档保留原语义。

人物和地点标识全局唯一；事实标识另行唯一。同一 subject/predicate 不可重复定义，避免用另一个标识绕开已确认事实。保留以 `@` 开头的标识。

## 行动提案

```json
{
  "schemaVersion": 1,
  "worldId": "river-town",
  "requestId": "one-forward-event",
  "expectedRevision": 0,
  "actor": "apprentice",
  "reason": "当前行动依据",
  "requiresKnown": ["missing-at-dawn"],
  "effects": []
}
```

`effects` 必须非空。`requiresKnown` 可选，人物须已有对应消息。`@author` 是作者操作身份，非人物。版本过期拒绝；所有效果先一起验证，成功后才追加一个事件。

| type | 参数 | 约束 |
|---|---|---|
| fact.define | fact | 作者新增事实，不能复用身份或已有 subject/predicate。 |
| fact.change | factId/expectedValue/value | 只更新 state；执行声明的 writeAccess 及提交前 changeRequires，旧值必须一致；不自动修改人物认识。 |
| place.add | place | 作者追加地点，不能更名已有地点。 |
| person.add | person | 作者追加人物，不能更名已有人物。 |
| group.member.add | groupId/personId | 仅作者；群体与人物须存在，人物 publicProfile.groupId 须与目标一致，已有成员拒绝。 |
| route.add | from/to | 作者追加有向通路，端点存在、非自环、非重复。 |
| person.move | personId/expectedPlace/to | 人物只能移动自己；校验旧位置与已配置通路。 |
| knowledge.learn | personId/factId/sourcePersonId | 人物只能更新本人认识；他人来源须同地且有消息，复制其已知快照。来源 @world 须在 observableAt。 |
| knowledge.share | from/to/factId | 人物只能以本人身份主动转述给同地的另一人物；发送者必须已知。复制发送者认识值，接收者来源记为发送者，不从世界真值补齐或刷新原证据年份。 |
| belief.add | personId/text | 保存个人推测，不进入世界事实。 |
| relationship.change | from/to/expectedValue/value | 人物只能改变本人方向的关系记录；旧值一致。未建立关系的 expectedValue 为 null。 |
| reader.reveal | factId | 作者揭示当前值给读者；不更新人物认识。 |
| time.advance | expectedYear/toYear | 仅作者且世界已声明年度时钟；旧年一致、严格向前，必须首效果，一笔至多一次。后续效果使用新年；任一效果失败则包括时钟在内全不提交。 |

`relationship.change` 可附非空 `basis`；年度世界必须提供。发生年由内核确定，不允许输入伪造 `occurredAtYear` 或倒写旧关系。旧值保留在种子与事件历史中。

新增群体人物在同一笔作者提案中先 `person.add`，再 `group.member.add`。整笔效果完成后统一检查归属和总人数；单独新增带 `groupId` 却未登记的人物会拒绝，总人数不足也拒绝整笔事务。展开成员不自动增加总人数，若确需增加，另以同笔合法 `fact.change` 明确更新人数事实。登记通过新事件增加该群体的 `refs`，不改写原 seed；复制、恢复及 `view-at` 按对应历史版本重建。当前没有群体转移、成员移除、新目录实体或运行期法则变更效果。

这些操作验证结构、身份、引用、声明条件和状态一致性。条件值及依据来自作者明确资料，非空 `reason` 不证明自然语言内容真实、动机合理或身份取得过程成立。当前不验证实际搬运能力、资源守恒或完整身份模板。作者新增设定和当前状态输入属于明确的创作操作，不能冒充模型自主推导出的结果。

`knowledge.share` 是主动通知，`knowledge.learn` 仍是本人取得消息；两者不互相替换。作者可明确操作分享，但事件仍记录 `actor:"@author"`。分享本身不表示消息正确、接收者认同，或接收者已现场核实；接收者原认识被新认识覆盖时，旧快照仍可从先前历史版本读取。

## 内置行为输入

`step` 共同字段：`schemaVersion/worldId/requestId/expectedRevision/actor`。

- `ask` 加 `target/factId`，输出学习提案。
- `travel` 加 `to`，输出移动提案。
- `observe` 加 `factId`，输出本人现场观察的学习提案。

模块获得指定行动者的视图及本次输入：人物身份获取人物视图，自定义模块以 `@author` 调用时获取作者全量视图，以 `@reader` 调用时获取读者视图。模块不能改变本次请求身份或把人物身份升级为作者；内置 `ask/travel/observe` 仅支持人物身份。完整相同的已提交输入直接返回原 receipt，不再调用模块；同请求不同输入、模块、实现版本或提交渠道拒绝。

### 行为实现版本

`ActionModule` 声明 `id()`、`version()` 和 `propose(actorView,input)`；`version()` 默认返回文本 `"1"`。`WorldStore.register` 按 `id + version` 注册，重复组合拒绝，同标识不同版本可并存。版本必须是非空单行文本，拒绝 CR/LF，不解释为数字或自动排序。注册捕获身份，推导前后检查实现报告的标识和版本；这不能证明实现内容不可变，同版本对应源码仍须由维护纪律保留。

Java 入口保持旧方法并新增显式版本重载：

```java
step(moduleId, input)                 // 固定版本 "1"
step(moduleId, moduleVersion, input)  // 指定版本
propose(moduleId, input)
propose(moduleId, moduleVersion, input)
receiptFor(moduleId, input)
receiptFor(moduleId, moduleVersion, input)
```

旧方法始终使用版本 `"1"`，不选择最新实现。尚未生成或保存准备提案、也没有正式提交时，缺少指定实现明确停止，不回退到其他版本。`receiptFor` 只查正式结果；已有正式事件或已保存准备提案的恢复不需要重新执行原行为实现。

旧调用保留 `submission:{kind:"step",module,input}`；显式调用增加 `moduleVersion`。缺版本按 `"1"` 比较，旧调用和显式版本 1 的完整相同输入可返回同一原回执；原历史不补写字段。版本 2 不能借版本 1 的请求标识取得原结果。进程内同请求协调也检查完整输入与规范化版本，跨进程只保证正式提交去重，不保证回调恰好执行一次。

### 共同 Java 宿主

`WorldEngine` 装配已有能力，不新增一份世界状态：

- `register(WorldConstructor)`、`intercept(CreationInterceptor)` 使用本宿主的构造管线；`register(ExperienceComponent)` 使用本宿主的五阶段注册；`register(ActionModule)` 捕获并检查行为标识与版本。
- `genesis()/preparation()/experience()` 返回本宿主持有的实例。
- `openWorld(path)` 新建 `WorldStore`，装配本宿主已注册的行为；它不创建或修改正式文件，初始化仍须调用 `initialize`。
- `pursue(world, request, directory, maxAttempts)` 将本宿主的演绎实例传给连续推进器。调用方须传入 `engine.openWorld(...)` 返回的 store；它不会向任意外部 store 自动补装行为。

`GoalJourney.run(world, engine, request, directory, maxAttempts)` 使用显式传入的引擎，各步骤不重新创建默认引擎。原四参数重载继续创建默认引擎，保留旧调用语义。行动版本依旧由候选及已选行动固定，需要新推导却缺少指定实现时明确停止，宿主不挑选最新或回退版本；已有 prepared 或正式提交仍按原恢复合同处理。

CLI 已统一使用这一宿主；每次启动仍只有内置注册，自定义版本须由 Java 调用方注册。当前没有插件加载配置、注册持久化或运行中热替换。原构造、准备、提交、演绎和过程记录各自保留原权威与恢复边界。

## 存档、回执与视图

存档：`{schemaVersion:1,seed:{...},events:[...]}`。事件为 `{revision,proposal}`；行为事件另有 `submission:{kind:"step",module,input,moduleVersion?}`，缺版本表示 `"1"`。年度事件必须另有提交后的 `worldTime`，恢复逐笔重放并核对；无时钟世界的事件不接受时间字段。恢复不再执行行为模块。没有回退或改写历史命令。

回执：`{schemaVersion,worldId,requestId,revision,status:"committed"}`。

- 作者视图：完整地点、人物、事实、认识、推测、关系、目录、法则和已揭示记录。
- 人物视图：本人完整资料、同地他人公开资料、本人认识、推测和关系。
- 读者视图：已明确揭示的值快照。

年度视图顶层均使用同一份 `worldTime`。命令 `view-at <worldFile> <actor> <revision>` 读取已经存在的历史版本，重建当时年龄、关系、知识和时钟；它不倒退当前正式世界。

保存历史供引擎恢复不等于防止外部程序修改文件；0.1没有存档签名。未识别协议版本、效果类型和固定协议结构字段会报错，不静默忽略。人物资料、实体属性及未知项对象允许自由JSON内容，程序不验证其中业务字段的含义；自定义行为输入的额外参数由对应模块校验。

## 世界构造蓝图与草案

正常示例为 `examples/genesis-river-town.json`；冲突示例为 `examples/genesis-conflict.json`。必填字段：`schemaVersion/worldId/title/idea/sections`，可选 `unresolved/constructors`。`idea` 是保留的创作方向，不代表程序已经解释了自然语言。

| sections 字段 | 输入与实际产物 |
|---|---|
| world | 必填对象：`places`，可选 `routes/facts/entities/worldTime`。此处实体仅接受地区或资源。 |
| people | 人物数组，使用初始人物格式。 |
| factions | `{id,name,attributes?,refs?}` 数组，补成势力实体。 |
| groups | `{id,name,populationFactId,attributes?,refs?}` 数组，补成群体实体与数量引用。 |
| extras | 初始人物格式加 `groupId`；创建人物，登记群体归属与成员引用，检查人数。 |
| relationships | 初始关系数组，可保留 `basis`。 |
| emotions | `{personId,feeling,trigger}` 数组，补入本人 `privateProfile.initialEmotions`。不得覆盖已有同名资料。 |
| laws | 上述法则数组。 |

除 `world` 外各 section 可省略，不提供的部分不会凭空补造。`unresolved` 是作者明确提供的文本或对象数组，保留在草案中，不会被构造成功自动消除。

`constructors:[{id,version},...]` 显式选择本次管线，一条管线同标识只能选一个版本。资料名称支持和冲突只按选中集合检查；依赖按构造器标识解析，所依赖的标识也必须在本次集合中。精准版本未注册、依赖未选、依赖循环或资料名称冲突，均在任何构造或拦截回调之前拒绝。`sections.world` 仍必填，选中集合须有 `sectionName:"world"` 的处理器，其标识可以不是 `world`。

旧蓝图省略 `constructors` 时，从宿主每个已注册标识固定选择版本 `"1"`；只有其他版本而没有1时拒绝，不选择最新。旧蓝图与草案保持原形状，不补写选择列表或版本元数据。新增注册标识可能改变旧缺省管线，确认时完整比对失败就明确停止；显式列表不让无关注册参与本次构造。

`construct <blueprint> [newDraftFile]` 按构造依赖执行，最终使用正式内核验证种子，返回或首次保存：

```text
{schemaVersion:1,kind:"world-draft",blueprint,seed,
 construction:{moduleOrder,modules,evidence,unresolved,notes}}
```

贡献记录说明各模块增加了什么，以及来自哪个结构化输入部分。它不是外部资料核实或模型推导的证明。此操作不初始化正式世界。

显式选择的草案在 `construction.modules` 各项原有 `id/outputs/notes` 之外增加 `version/section/dependencies`，依赖数组按标识排序；`evidence` 各项增加 `moduleVersion`。`moduleOrder` 仍是标识数组。元数据均使用注册时捕获的合同，缺省旧管线不增加这些字段。

`WorldGenesis.confirmSeed(draft)` 严格检查 `world-draft` 顶层、kind及协议版本，按草案的原蓝图重新构造，完整规范化内容相等才返回 seed 深拷贝。它不写文件、不迁移或补写旧草案；任何派生资料或贡献元数据不一致都会拒绝。

`confirm <draft> <newWorldFile>` 先调用该确认入口，再以返回的 seed 调用正式初始化，建立零事件的起点。正式世界只保存 seed，不复制草案的 blueprint/construction 元数据或预演历史；完整草案另留作构造依据。已有草案和正式世界均不覆盖；调整候选时重新构造并使用新的输出文件。当前 CLI 只注册八个内置构造器的版本1，指定其他版本明确拒绝；自定义版本须在 Java 宿主注册。

### Java 扩展与拦截

- `WorldGenesis.register(WorldConstructor)` 按 `id + version` 注册，重复组合拒绝，同标识不同版本可并存；`version()` 默认 `"1"`，版本须为非空单行文本。
- `WorldConstructor` 声明 `id/version/dependencies/sectionName`；注册捕获这四项，依赖集合保存为不可变副本。`build` 冻结输入与本次选中注册合同，`construct(Context)` 接收蓝图、已组装种子与当前 section 的独立快照。
- 构造回调及每个拦截回调前后检查全部已选构造器仍符合捕获身份、版本、资料名称与依赖；不能靠回调漂移换实现或改变后续管线。版本号不能证明同版源码不可变，仍须保留源码、维护纪律与回归，不构成签名。
- 贡献可以追加种子数组、补充未有的人物私有字段、登记实体引用，或提供说明与未知项；`worldTime` 只能首次贡献，即使同值第二次贡献也拒绝。时间格式由同一 `WorldTime` 校验，不能覆盖世界身份或绕过最终内核校验。
- `WorldGenesis.intercept(CreationInterceptor)` 运行构造前后的检查；抛异常可拒绝。修改回调快照不会改变构造输入或贡献。
- Java 扩展是可信同进程代码，不是恶意插件沙箱；默认 CLI 使用内置构造器。自定义模块需要在 Java 宿主里注册，0.1没有动态插件加载配置。

构造版本与完整确认已由本轮 Java 专项和独立命令行核对：精准版本选择、选定依赖预检、注册合同漂移、完整草案篡改拒绝、旧形状兼容及确认后行动均有正反例。受影响十四套原始报告与独立退出码见 本轮核对汇总（本地历史文件：`build/constructor-version-acceptance.json`），具体范围见 [构造版本案例](CONSTRUCTOR-VERSION-DEMO.md) 和 [验收记录](ACCEPTANCE.md)；这些结果不证明源码签名、任意扩展隔离或小说质量。

## 五阶段演绎请求与记录

命令：`experience <worldFile> <requestFile> <recordFile>`。运行示例见 `examples/experience-ask.json`；缺口例见 `examples/experience-missing.json`。顶层八个字段均必填：

```text
{schemaVersion:1, worldId, requestId, expectedRevision, actor, intent,
 components:[{stage,id,version}, ...],
 scenario:{requiredEvidence:[], candidates:[], remainingGaps:[]}}
```

`actor` 必须是普通人物；`intent` 是作者保留的方向说明，目前不会被自然语言解析器解释。`scenario` 是作者声明的检查与选项，不会被当作人物认识。只在对应人物的视图中检查证据，不能用作者目录或他人的私密状态作已知依据。

### 默认组件的配置

五个阶段各配置一次，按固定阶段顺序执行，标识和版本须已经注册。

| stage | id | version | 结果重点 |
| --- | --- | --- | --- |
| prepare | declared-preparation | 1 | 读取 `requiredEvidence`，返回 `evidence/gaps`。 |
| candidates | declared-candidates | 1 | 检查每个候选的 `evidence`，保留有依据候选。 |
| select | priority-selection | 1 | 选择最高整数优先级，同分保留数组顺序，输出 `candidateId/reason`。 |
| resolve | selected-action | 1 | 输出与已选候选相同的 `action:{module,arguments,moduleVersion?}`。 |
| feedback | actual-feedback | 1 | 输出真实 `receipt/after/remainingGaps`。 |

一条证据配置：`{path:"/nearby/0/id",label:"询问对象在同地",equals:"boatman"}`。`path` 为指向人物视图的非空 JSON 指针，`label` 为非空说明，`equals` 可选。路径缺失、null、空文本、空对象或空数组均视为缺口；有 `equals` 时还须按规范化 JSON 相等。记录同时保存实际值或缺口原因。这是存在性和相等校验，不是语义因果证明。

一条候选：`{id,module,arguments:{},description,priority,evidence:[],moduleVersion?}`。缺少行为实现版本按 `"1"` 处理；显式版本须符合上述单行文本规则。`priority` 是默认选择器支持的整数，`arguments` 仅放模块参数（例如 `target/factId`）；不得声明五个提交身份字段。转换组件不能换成其他模块、行为版本或参数；编排器从原请求补齐 `schemaVersion/worldId/requestId/expectedRevision/actor`。

### Java 组件与提交合同

- `ExperienceEngine.register(ExperienceComponent)` 注册 `id + version`，相同组合不能覆盖；不同版本可并存。
- 注册时捕获阶段实现的 `id/version/stage`。未完成阶段在回调前后核对动态身份，成功或失败记录均保存捕获值；反馈阶段在准备及正式提交前先核对身份，不能让已漂移实现触发提交。
- `ExperienceComponent` 声明 `id/version/stage`，`run(Context)` 返回阶段结果。Context 的请求、人物视图、前序输出与回执 getter 均为独立副本。
- 结果状态为 `ready/needsInput/blocked/satisfied`；`satisfied` 只允许准备或候选阶段返回，并须有非空 `reason`，代表目标正常满足而不提交新行动。`ready` 的候选、选择和行动须符合各阶段字段要求，异常另行记录。FEEDBACK 如自行返回 `after/receipt`，必须与内核实际结果相同；编排器也会附上权威值。
- 前四阶段使用本次固定的起始人物投影，反馈使用原提交版本投影。候选推导本身不改变世界。
- `WorldStore.propose(module,input)` 固定版本 1，显式重载 `propose(module,moduleVersion,input)` 精准选择实现。在入口冻结完整输入，返回 `prepared` 包或已经正式提交的 `alreadyCommitted` 包。前者含 `submission/proposal`，后者含 `submission/receipt`；共同顶层为 `schemaVersion/status`，版本保存在 submission 中。
- `WorldStore.commitPrepared(prepared)` 在正式文件锁内重新检查身份、期望世界版本和事务。编排器另外核对准备包的行为模块、实现版本及完整输入与已选行动一致。已保存 prepared 不要求宿主仍注册原实现，但不能免除内核校验。已有完整相同提交则返回存档里的原回执；不同提交或实现版本复用标识拒绝。准备包没有签名，不是模型输出证明。
- `WorldStore.receiptFor(module,input)` 固定版本 1，另有 `receiptFor(module,moduleVersion,input)`；只查询原回执，未提交返回 null，不运行模块、不提交。`viewAt(actor,revision)` 验证全历史后生成该已存在版本的视图；未来版本拒绝。

### 独立演绎记录及恢复

记录必有 `schemaVersion/kind:"world-experience"/worldFile/request/initialView/stages/status`；运行中可增加 `prepared/receipt/finalView/error`。阶段记录保存阶段名称、组件标识、版本、完成输出或失败原因。完成记录须有五阶段、准备提案、正式回执和原版本反馈视图，恢复时逐项核对。

准备或候选不就绪时保存缺口并停止，没有正式事件。即使总状态尚未落盘，恢复也从非就绪阶段识别停止。补充输入或换组件版本须用新请求、新记录；组件异常则可恢复同一固定尝试的未完成阶段。

提交已经成立而反馈失败时保留该事件，恢复只补反馈；没有保存回执的提交窗口通过相同提案查重恢复。已有回执却没有对应正式事件时，在提交前拒绝。完成重跑只核对原事件与反馈，不重跑组件或追加事件。

恢复继续绑定完整原 experience 请求，不能把新增或删除字段后的请求当作原尝试。行为层仅把缺省版本和显式 `"1"` 视为等价；这不放宽 experience 的完整请求比较。候选、转换结果、准备包与正式提交之间的行为版本须一致。未保存准备提案且原实现缺失时明确停止，不能改用新版重推；已有 prepared 或正式事件则复用保存结果，经对应校验恢复。

已完成阶段恢复仍须从注册表找到请求配置的 `id/version/stage` 绑定，并核对保存的身份与产物；随后复用保存结果，跳过该阶段回调与动态身份核查。缺少全部阶段实现的宿主不能据此恢复演绎记录。行为 prepared 不依赖原行为实现的合同与此分开。

此前行为与阶段版本的专项、命令行及受影响回归结果见 [验收记录](ACCEPTANCE.md) 和 核对汇总（本地历史文件：`build/module-version-acceptance.json`）。具体运行与边界见 [行为版本案例](MODULE-VERSION-DEMO.md)，这些既有结果不代替本轮构造版本验证。

记录不能覆盖世界、输入或世界锁文件。不同尝试记录可独立运行；正式提交有版本竞争。当前文件没有签名或远端调用恢复合同，尚未保存的回调可再次执行；不得据此承诺模型恰好调用一次。

## 只读准备报告

命令为 `prepare <worldFile> <requestFile>` 与 `prepare-draft <draftFile> <requestFile>`，结果写到标准输出，不自动写报告或事件。草案先经 `confirmSeed` 核对完整内容，正式世界固定一个版本读取作者和人物视图。

请求顶层严格为 `schemaVersion/worldId/actor/intent/templateId/templateVersion/requirements`。每项要求必有 `id/label/stage/factId/scope`，可有标量 `equals` 和 `freshness`；`scope` 为 `world/actor`，时效仅支持人物知识。`freshness` 默认为 `any`，另支持 `currentYear/currentRevision`。要求至少一项且标识不重复，null 相等条件拒绝。false 和0有值，null与空白表示未知。

返回 `kind:"world-preparation"`，包含分析器版本、模板版本、来源种类与正式版本，以及 `authorReport/actorReport`。每份报告有 `checksScope:"declared-requirements-only"`、要求结果、按 `stage` 汇总的状态及说明。作者检查所有要求，人物只检查 `scope:actor`，不暴露世界真值或隐藏事实是否存在。

作者可以报告 `worldMissing/actorUnknown/knowledgeConflict`；人物未知统一为 `unknown`。双方可有 `valueUnknown/needsRecheck/conditionUnmet/ready`。认识冲突仅比较有记录的值，未猜测消息年代；需要复核不等于事实已假。`currentRevision` 要求知识来源为 `@world` 且版本等于视图版本；`currentYear` 检查证据年，同年不保证未变。草案未决项原样保留于作者报告，不自动判定解决。

报告只检验声明条件，不授予内核提交权限、不生成候选、不自动补资料，也不证明领取、运输或文学效果。实测示例见 [准备分析案例](PREPARATION-DEMO.md)。

## 人物收到消息后的回应

沿用 `experience` 请求顶层，`scenario` 必有 `message`，可选 `remainingGaps`；不接受外部手写候选。触发格式为：

```json
{"message":{"factId":"cargo-handoff-status","sourcePersonId":"apprentice","receivedRevision":4},"remainingGaps":[]}
```

五个阶段均使用版本 `"1"`，标识依次为 `message-reaction-preparation/message-reaction-candidates/message-reaction-selection/message-reaction-action/message-reaction-feedback`。来源和接收版本必须与本人当前保存的该知识快照一致，来源须为同地的另一普通人物。初始 `@seed` 与直接观察 `@world` 不作为人际消息。消息被新认识覆盖或对方已离开时返回缺口；不从作者视图补出依据。

人物的 `privateProfile.responsePolicy` 结构严格为：

```json
{
  "goalId":"keep-authorized-handoff",
  "goal":"先把交接条件告知来人",
  "rules":[{
    "id":"explain-pending-permission",
    "priority":10,
    "when":{"factId":"cargo-handoff-status","equals":"held-awaiting-registration"},
    "conditions":[{"factId":"cargo-pickup-permission","equals":false}],
    "shareFactIds":["cargo-pickup-permission"],
    "relationshipValue":"愿意向来者说明具体条件",
    "description":"收到来者有关交接状态的消息后，说明本人已知条件"
  }]
}
```

规则必有 `id/priority/when/conditions/shareFactIds/description`，可有非空 `relationshipValue`。条件严格为 `factId/equals`，按本人知识的规范化标量相等检查，false 与0有效；缺失、null和空白知识没有可用依据。分享项不重复且本人明确已知；规则必须有至少一项分享或关系后果。空规则集合会停止而不编造回应。

筛选所有满足触发、条件和分享依据的规则。某条规则未知不妨碍另一条完整规则成为候选；缺口区分消息未知、消息变化、不同地、条件未知与条件不符合。候选记录本人目标、规则、消息来源/接收版本/证据年份、条件和分享的知识快照。仍使用原选择器的最高整数优先级，同分保留声明顺序。

行为 `message-response` 版本 `"1"` 的输入为五个身份字段加 `message/ruleId`。它重新核对同一规则和全部前提，只输出 `knowledge.share` 及可选本人→消息来源人的 `relationship.change`。关系 `expectedValue` 取本人投影，没有关系时为 null；`basis` 保存目标、规则说明及收到消息的来由。默认消息组件只支持人物视图，不以作者身份代替回应。

选择与转换会重算候选并与保存结果完整核对；反馈复用原实际回执及提交版本投影。此功能只处理一次明确触发的回应，不自动启动消息级联；同一请求沿用既有恢复和去重，跨不同请求标识没有全局消息消费保证。声明策略不等于自然语言意图解释、复杂心理或当前事实核实。可运行案例和本轮验收见[人物回应案例](MESSAGE-REACTION-DEMO.md)。

### 受控状态回应行为

新增独立行为 `message-state-response`，版本 `"1"`。输入严格为 `schemaVersion/worldId/requestId/expectedRevision/actor/message`，其中 `message` 仍严格为 `factId/sourcePersonId/receivedRevision`；不接受 `ruleId`、外部候选或作者视图。本人消息来源、接收版本及同地检查沿用上述合同。

该行为明确启用 `responsePolicy` 的状态回应模式，规则仍必填 `id/priority/when/conditions/shareFactIds/description`，可选 `relationshipValue/stateChanges`。`stateChanges` 是非空数组，每项严格为 `factId/expectedValue/value`；事实标识不重复，新旧值均为非 null、非空白的 JSON 标量，规范化后必须不同。各目标的旧值须与本人明确知识匹配，分享的每项也须已知。某条规则缺依据不妨碍另一条可用规则。

在满足触发、条件、旧值与分享依据且包含 `stateChanges` 的规则中，行为直接选最高整数优先级，同分选声明顺序最早的一条，不接受外部手选。只执行这一项决定的关联状态更新；不会规划多个顺序步骤。原 `message-response` 和消息五阶段版本1仍严格拒绝 `stateChanges`，不会生成新状态回应候选，也不改变原 v1 字段与效果语义。

提案先包含全部 `fact.change`，再对每个变化事实加入本人来源 `@world` 的 `knowledge.learn`，最后按 `shareFactIds` 加入本人向消息来源人的 `knowledge.share` 及可选本人方向关系变化。分享数组可以为空，状态决定本身即可构成规则后果。本人仍须处于各事实的声明观察地点；不会因做出了决定就获得隐藏世界资料。任一权限、变化前提、旧值、观察或后续效果失败，整笔候选拒绝，不保存部分决定、知识或关系。成功后分享变化目标时取得新认识值，转述来源仍记为分享人，不冒充接收者的直接观察。

`requiresKnown` 包括触发消息、变化目标、规则条件及分享事实；`reason` 记录声明目标、所选规则说明与消息接收版本。它们保存结构化来由，不证明文字真实性或复杂心理。人物可以持有与世界不符的身份认识；命中的已声明 `changeRequires` 身份与条件，以及各目标旧值，会在内核提交口检查。私有规则的 `when/conditions` 仍依据本人知识判断，不会自动升级为权威法则。正式存档仍只追加同一笔事件；同请求恢复沿用行为版本与原回执合同，跨不同请求没有全局消息消费保证。

此行为的运行、失败回滚和兼容证据由独立验收记录提供；代码合同不等于已证明自然语言解释、完整身份模板、模型推导、自动放行或实际运输。

## 目标组件、观察与有限推进

目标例为 `examples/goal-inspect.json`，使用同一演绎请求顶层，`scenario` 改为必填 `goal` 和可选 `remainingGaps`，不接受人工候选或声明依据数组：

```json
{"goal":{"id":"inspect-cargo","kind":"observeLocatedFact","factId":"cargo-location","locationFactId":"cargo-location","label":"现场核实铁料箱下落"},"remainingGaps":[]}
```

五个阶段分别指定版本 `1` 的 `knowledge-goal-preparation/knowledge-goal-candidates/knowledge-goal-selection/knowledge-goal-action/knowledge-goal-feedback`。本类型判断本人是否已有来源为 `@world` 的目标知识快照；初始 `@seed` 与旁人口述不算现场观察。已直接观察正常返回 `satisfied`，不是对当前世界永远未变化的保证。

年度世界还要求该观察的 `asOfYear` 等于当前年、知识 `revision` 等于当前视图版本。新年份或后续提交后的新核实不能直接复用旧现场证据；此规则保守地要求重新观察，即使后续提交与目标无关。判断仅使用人物视图，不能借作者真值修补人物认识。无时间的旧世界维持原“取得一次观察”合同。已经完成的旧过程按原版本回读，不能因为后来时间推进而改写其结果。

地点未知时，从本人视图的同地对象生成询问候选，不假定对方知道答案；已有本人关系影响询问优先次序。地点已知时，BFS 只在本人 `privateProfile.knownRoutes` 中寻找路径，每项为 `{from,to,evidenceFactId}`，证据须在本人知识中且值严格为 `true`。每次只提出第一跳。道路当前能否通行仍由正式内核决定；该字段尚不是通路知情事件。

新增内置行为 `observe`：输入为五个身份字段加 `factId`，只允许本人身份，复用 `knowledge.learn` 的 `@world` 来源与地点观察约束。失败不产生知识变化。无需新增效果格式或更改既有存档版本。

`pursue <worldFile> <goalRequestFile> <processDirectory> [maxAttempts]` 调用有限推进器，上限1到32，默认8，按累计步骤计。目录保存 `journey.json`、`manifest.lock`、`step-001.json` 等各步记录。清单绑定完整原请求、世界文件与固定组件版本；输入文件不能兼作记录或锁。步骤请求使用原标识加 `.step-序号`，版本取原期望版本或上一笔真实回执；不会取世界后来版本偷渡过冲突。

每步真实完成后重判目标；`satisfied/needsInput/blocked` 停止，预算耗尽为 `paused`，增加预算继续同一过程。完成重读先核对各步权威回执与历史反馈，不新增事件。异常为 `failed`，保留原步骤与原因，可恢复原失败步骤；换目标、完整输入或版本必须换过程目录。过程清单只是索引，正式世界和单步演绎仍是各自结果的依据。

## 有界连续演绎入口

`continue <worldFile> <requestFile> <processDirectory> [cumulativeAttemptBudget]` 使用现有五阶段过程。预算默认8、范围1到32，按整段累计尝试数计算。顶层严格为：

```json
{
  "schemaVersion": 1,
  "worldId": "continuation-river",
  "requestId": "continue-river",
  "expectedRevision": 0,
  "activities": [],
  "starts": [],
  "onKnowledge": []
}
```

这是字段轮廓；可运行配置须有1到16项活动和至少一个起始目标，详见 [完整请求](examples/continuation/river-request.json)。

活动严格为 `id/actor/kind/intent/scenario/components`，标识唯一，actor须为起始版本已存在的普通人物。kind为 `goal` 时scenario必有goal，可有remainingGaps；kind为 `message` 时scenario只允许可选remainingGaps，消息由实际历史派生。components必须包含prepare、candidates、select、resolve、feedback各一个固定id/version。starts不得重复，只能引用goal。onKnowledge至多64项，每项严格为activityId/factId，不重复且引用已声明活动。

状态回应组件五阶段标识为 `state-reaction-preparation/state-reaction-candidates/state-reaction-selection/state-reaction-action/state-reaction-feedback`，均版本 `"1"`；只为有stateChanges且本人依据完整的规则生成候选，调用原message-state-response。旧消息组件身份与版本保持。

新提交的观察或分享效果匹配活动人物和factId，触发消息保留factId、sourcePersonId、receivedRevision。message活动只接受另一普通人物的实际来源；goal可因本人观察唤起。每次仍从对应人物视图核对当前保存的知识，不能用作者事实替代已被覆盖的消息。

返回包含schemaVersion、worldId、requestId、revision、status、attempts、notifications、outcomes、pending。每次尝试包含序号、活动、人物、期望版本、状态、记录文件、缺口及remainingGaps；真实提交才有receipt，触发活动可有trigger。outcomes分别保留各活动最后结果。notifications是作者侧获知派发说明，不是给人物的新知识。

总体status为paused、quiescent或limitReached。quiescent不替代活动的satisfied/needsInput/blocked；调用成功不保证业务已完成。累计上限限制新的和未完成的尝试，已完成记录重读不受较小预算截断。

配置、世界文件路径与完整原请求绑定在continuation.json，逐次记录为turn-001.json等，锁为continuation.lock及各步锁。输入、记录和锁不能占用正式世界文件。待运行活动从原输入及原回执重建；未完成步骤遇到外部世界推进不自动重排。已正式提交后反馈失败先恢复原反馈。整段经历有多笔独立原子事件，不是全段回滚事务。

实际运行与限制见 [连续演绎案例](CONTINUATION-DEMO.md)。
