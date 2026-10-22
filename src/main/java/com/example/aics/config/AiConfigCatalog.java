package com.example.aics.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI 配置的唯一目录：后台表单、接口权限、校验规则和运行时默认值都从这里读取。
 * 未登记的 key 不允许通过配置接口写入，避免拼错配置名后产生“保存成功但永不生效”的脏数据。
 */
public final class AiConfigCatalog {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> VERSIONED_PROMPT_KEYS = Set.of(
            "refusal.answer",
            "prompt.tone",
            "agent.systemPrompt",
            "prompt.ragRules",
            "prompt.moduleCharBudgets",
            "projectIntro.decisionPrompt",
            "agent.classifierPrompt",
            "profile.prompt",
            "quality.judgePrompt"
    );
    private static final Set<String> PROMPT_MODULE_IDS = Set.of(
            "identity.agent", "safety.forbidden-words", "safety.prompt-injection",
            "scenario.rag-answer", "policy.rag-answer", "safety.medical", "style.customer",
            "media.delivery-state", "memory.customer-profile", "understanding.resolved-question",
            "memory.recent-history", "knowledge.retrieved", "request.current");

    /**
     * 数值参数的调节方向说明。上下限、默认值和步进仍从 Setting 本身生成，避免展示文案与校验规则漂移。
     */
    private static final Map<String, String> NUMERIC_GUIDANCE = Map.ofEntries(
            Map.entry("safety.abuseRepeatThreshold", "数值越小，系统越早进入重复辱骂处理；数值越大，允许的连续辱骂次数越多。"),
            Map.entry("threshold.faqDirect", "这里使用的是向量距离，数值越小匹配越严格；调高会增加 FAQ 直接回复的机会，但也会提高误匹配风险。"),
            Map.entry("threshold.relevance", "这里使用的是向量距离，数值越小筛选越严格；调高会带入更多参考资料，也可能增加无关内容。"),
            Map.entry("retrieval.topK", "数值越大，模型可参考的候选资料越多，但上下文长度、耗时和噪声也会增加。"),
            Map.entry("retrieval.sourceLimit", "数值越大，调试页和客户页面可展示的来源越多；填写 0 表示不展示来源。"),
            Map.entry("retrieval.sourceDistanceWindow", "数值越小，只保留与最佳结果更接近的来源；数值越大，展示范围更宽。"),
            Map.entry("retrieval.poolMultiplier", "数值越大，初始召回池越大，可能提高召回率，但会增加检索计算量。"),
            Map.entry("retrieval.maxLexicalTerms", "数值越大，单次关键词检索可使用的词越多，但过多词可能引入噪声。"),
            Map.entry("intent.shortAspectMaxLength", "数值越大，更多较长的模糊问法会先触发项目追问；数值越小，只处理更短的问法。"),
            Map.entry("agent.intentMinConfidence", "数值越高，预约、转人工等动作越谨慎；数值越低，动作更容易执行，但误触发风险更高。"),
            Map.entry("image.requestLimit", "数值越大，客户一次索图可收到的图片越多，也会增加消息数量和流量。"),
            Map.entry("conversation.historyRounds", "数值越大，模型能看到更长的近期上下文，但输入 token、耗时和费用也会增加。"),
            Map.entry("conversation.historyTtlMinutes", "数值越大，短期对话记忆保留越久，同时会占用更多缓存空间。"),
            Map.entry("profile.extractEvery", "数值越小，客户画像更新越及时，但模型调用更频繁；数值越大则更节省调用。"),
            Map.entry("profile.historyMessages", "数值越大，画像提取可参考的历史越完整，但输入内容和调用成本也会增加。"),
            Map.entry("quality.batchLimit", "数值越大，一次可提交更多会话评测，但模型额度消耗和等待时间也会增加。"),
            Map.entry("quality.batchScoreIntervalMs", "数值越大，手动批量评测越不容易触发模型限流，但整批完成时间也会更长；0 表示不等待。"),
            Map.entry("quality.minimumMessages", "数值越大，越能过滤过短或不完整的会话，但可参与评测的会话会减少。"),
            Map.entry("quality.lowScoreThreshold", "数值越高，进入低分重点列表的会话越多，也会有更多会话触发规则低分自动 AI 复核；数值越低，只保留更严重的问题会话。"),
            Map.entry("quality.judgeTemperature", "评测需要稳定可复现；0 最稳定，只建议在模型不支持 0 时小幅调高。"),
            Map.entry("quality.judgeTimeoutSeconds", "评测超时后会立即失败，不再底层自动重试；调高可容忍慢模型，也会延长单条失败等待。"),
            Map.entry("quality.autoScoreSampleRate", "0 表示不抽样，1 表示抽取全部；数值越大，覆盖率和模型消耗都越高。"),
            Map.entry("quality.autoScoreIntervalMs", "数值越大，评测调用间隔越长、限流风险越低，但整批完成更慢；0 表示不等待。"),
            Map.entry("quality.autoScoreFailThreshold", "失败率超过此比例时记录告警；数值越小越敏感，数值越大越宽松。"),
            Map.entry("quality.autoScoreStartHour", "使用 24 小时制，只填写小时部分；需要与开始分钟及结束时间共同组成评测窗口。"),
            Map.entry("quality.autoScoreStartMinute", "只填写分钟部分；需要与开始小时及结束时间共同组成评测窗口。"),
            Map.entry("quality.autoScoreEndHour", "使用 24 小时制，只填写小时部分；结束时间必须晚于开始时间。"),
            Map.entry("quality.autoScoreEndMinute", "只填写分钟部分；结束时间必须晚于开始时间。"),
            Map.entry("quality.autoScoreLimit", "数值越大，每轮覆盖的会话越多，但运行时间和模型额度消耗也会增加。"),
            Map.entry("quality.newConvRuleEvalLookbackMinutes", "数值越大，新对话扫描覆盖的时间范围越宽，同时扫描量也会增加。"),
            Map.entry("model.temperature", "数值越低回答越稳定一致；数值越高表达更灵活，但随机性和偏离资料的风险也会增加。"),
            Map.entry("model.chatTimeoutSeconds", "数值越大，慢速模型有更多完成时间，但客户等待也可能更久。"),
            Map.entry("model.streamTimeoutSeconds", "数值越大，流式、向量和视觉调用更不容易超时，但故障时等待也会更久。"),
            Map.entry("model.circuitFailureThreshold", "数值越小，主模型故障时越快切换备用模型；数值越大，对偶发失败更宽容。"),
            Map.entry("model.circuitCooldownSeconds", "数值越大，熔断后使用备用模型的时间越久；数值越小，主模型会更早尝试恢复。"),
            Map.entry("model.responseCacheTtlSeconds", "数值越大，重复请求更容易命中缓存，但回复保持旧结果的时间也更长；0 表示关闭缓存。"),
            Map.entry("model.responseCacheMaxEntries", "数值越大，可缓存的不同请求越多，同时会占用更多服务内存。")
    );

    public record Setting(String key, String label, String group, String description,
                          String input, String defaultValue, boolean required,
                          Double min, Double max, String step, boolean superOnly,
                          String placeholder) {
        public boolean multiline() {
            return "textarea".equals(input) || "keywords".equals(input) || "json".equals(input);
        }
    }

    private static final String DEFAULT_TONE = """
            5. 客户多为50岁以上的叔叔阿姨：语气要温和、有耐心、像真人聊天，不要像机器人念稿；
            6. 用词简单通俗，句子要短，一次只说一件事；专业术语必须用大白话解释；
            7. 循序渐进引导，一次最多问客户一个问题，不要一口气问一堆；
            8. 适当用“您”，可以带一点语气词，让客户感觉亲切；
            9. 先直接回答客户问的问题，客户没问的信息不要主动追加；确需补充的，最多一句。""";

    private static final String DEFAULT_ABUSE_KEYWORDS = loadBundledLexicon(
            "/lexicon/sensitive-lexicon-abuse.txt", "傻逼\n撒比\n煞笔\n沙比\n妈的\n操你妈\n垃圾客服\n废物\n滚蛋");

    private static final String DEFAULT_THREAT_KEYWORDS = loadBundledLexicon(
            "/lexicon/sensitive-lexicon-threat.txt", "杀了你\n弄死你\n砍死你\n打死你\n堵你们门\n人肉你");

    private static final String DEFAULT_PROJECTS = """
            {
              "抗衰": ["面部提升", "提拉紧致", "紧致提升", "抗衰", "抗老"],
              "淡斑": ["黄褐斑", "雀斑", "色斑", "祛斑", "淡斑"],
              "眼袋": ["去眼袋", "眼袋"],
              "胡子": ["脱胡须", "脱胡子", "胡须", "胡子"]
            }""";

    private static final String DEFAULT_ASPECT_RULES = """
            {
              "价格": ["多少钱", "价格", "价钱", "收费", "费用", "活动价", "优惠", "订金", "定金", "尾款", "到店还要付", "还要付多少", "补多少钱"],
              "疗程": ["几次", "多少次", "疗程", "周期", "多久见效", "做多久"],
              "效果": ["效果", "有效吗", "有用吗", "能淡", "能去掉", "会复发", "维持多久"],
              "恢复": ["恢复", "上班", "红肿", "疼吗", "痛吗", "休息几天", "恢复期"],
              "安全": ["适合", "能做吗", "可以做吗", "禁忌", "风险", "副作用", "安全吗", "注意事项", "注意什么"],
              "流程": ["怎么做", "怎么弄", "流程", "步骤", "原理", "操作", "仪器", "设备", "打针", "手术", "开刀", "无创"],
              "案例": ["案例", "照片", "图片", "对比图", "效果图"],
              "项目介绍": ["怎么样", "是什么", "介绍一下", "了解一下"]
            }""";

    private static final String DEFAULT_ASPECT_QUESTIONS = """
            {
              "价格": "您想问哪个项目的价格呢？告诉我项目名称，我再帮您查准确资料。",
              "疗程": "您想问哪个项目需要做几次呢？告诉我项目名称，我再帮您查准确资料。",
              "效果": "您想了解哪个项目的效果呢？告诉我项目名称，我再帮您查准确资料。",
              "恢复": "您想问哪个项目的恢复情况呢？告诉我项目名称，我再帮您查准确资料。",
              "安全": "您想问哪个项目是否适合做呢？告诉我项目名称，我再帮您查准确资料。",
              "流程": "您想问哪个项目使用什么仪器、怎么操作呢？告诉我项目名称，我再帮您查准确资料。"
            }""";

    private static final String DEFAULT_HOME_QUICK = """
            {
              "price": "优惠活动与项目价格",
              "recovery": "恢复时间与注意事项",
              "store": "查询附近门店"
            }""";

    /** 坐席快捷回复默认话术（与 agent.html 原内置一致）。 */
    private static final String DEFAULT_QUICK_REPLIES = String.join("\n",
            "您好，我是昇媛的客服小助手，请问有什么可以帮您？",
            "我们门店地址：云南省昆明市五华区XX路XX号，欢迎到店咨询～",
            "目前抗衰项目有优惠活动，具体价格我帮您查询一下，您方便留个电话吗？",
            "您可以先在聊天里登记预约，我们会安排专人联系您～",
            "本次服务结束，感谢您的咨询，再见～");

    private static final String DEFAULT_AGENT_PROMPT = """
            你是客服系统的意图分类器。只输出 JSON：
            {"action":"book|store|handoff|negotiate|chat", "secondaryAction":"book|store|handoff|negotiate|chat", "confidence":0.0, "secondaryConfidence":0.0, "name":"", "phone":"", "project":"", "city":""}

            action 规则：
            - book：客户明确表达想预约、报名、登记、挂号或留联系方式；仅询问订金、价格、规则属于 chat。
            - store：客户问门店位置、地址或附近有没有店。
            - handoff：客户明确要求人工，或投诉、强烈不满、退款、威胁差评/曝光。
            - negotiate：客户嫌贵、要求降价或明显犹豫；仅询问价格属于 chat。
            - chat：普通咨询。
            confidence 是 action 的置信度，必须输出 0 到 1 的数字；不确定时降低置信度，不要猜测。
            同一句有第二个明确意图时填写 secondaryAction 和 secondaryConfidence，否则 secondaryAction 为 chat、secondaryConfidence 为 0。
            字段没有信息时输出空字符串；手机号会由本地程序提取，不要猜测。""";

    private static final String DEFAULT_PROJECT_INTRO_PROMPT = """
            你是医美客服系统的“首次项目活动介绍”决策器。只输出 JSON，不要输出 Markdown：
            {"introduce":true或false,"answer":"暂不介绍时对客户说的一句自然追问"}

            判断规则：
            - 如果客服上一句正在询问“想了解哪个项目”，客户回复“抗衰”“淡斑”“胡子”“眼袋”等项目名，说明客户已经表达首次了解意向，introduce=true。
            - 客户说“想了解淡斑”“介绍一下眼袋”“先看看抗衰”等首次了解项目的表达，通常 introduce=true；不要再机械追问“想了解哪方面”。
            - 客户明确要求详细介绍项目、活动、优惠方案或完整内容，例如“详细介绍一下淡斑活动”“淡斑现在有什么活动”，introduce=true。
            - 只有项目指代不清、无法确认客户是在选择项目，或上下文表明客户并非要了解项目时，introduce=false，并自然询问客户最关心的一件事。
            - 不要因为系统提供了标准活动话术或图片就自动判定 true，必须以客户当前表达和历史上下文为准。
            - 明确价格、预约、门店、安全、疗程、效果等具体问题应由专用流程回答；如果这类问题意外进入本判断，introduce=false。
            - introduce=true 时 answer 留空，系统会原样发送经过审核的标准活动话术，禁止自行改写价格、活动条件或效果承诺。
            - introduce=false 时 answer 必须简短、亲切、口语化，不得包含价格、促销内容，也不得声称已经发送图片。""";

    private static final String DEFAULT_PROFILE_PROMPT = """
            你是客服系统的用户画像分析员。请阅读聊天记录，提取这位客户的画像要点，每条一行：
            - 所在城市/区县（如提到）
            - 意向项目
            - 主要顾虑（价格/疼痛/效果/安全等）
            - 个人信息（年龄段、性别等，如提到）
            - 沟通偏好或特殊要求
            只输出画像要点本身，不要解释；记录里没有的信息不要写。""";

    private static final String DEFAULT_QUALITY_JUDGE_PROMPT = """
            你是客服对话质量复核员。只评价 AI 客服（assistant）的回答，不评价人工坐席（agent）。
            必须基于完整上下文评分，不得因为一句话包含“保证”就误判“不能保证固定效果”等否定表达。
            门店营业时间是 {storeHours}，人工客服在线时间是 {agentHours}，两者不可混淆。
            逐轮检查客户提出的每一个问题和限制条件；漏答任一子问题、答非所问、错误理解指代、只说“没有资料/转人工”而未解决问题，都必须在准确性、上下文或解决程度中如实扣分。
            不要因为措辞礼貌、没有违规词或没有可靠反证就默认给满分。无法核实某个事实时不要武断判错，但“没有回答明确问题”本身就是可扣分证据。
            总分锚点：95-100=所有问题均准确完整解决且自然合规；80-94=整体有效但有轻微遗漏；60-79=只解决部分问题、内容空泛或明显依赖兜底；0-59=答非所问、主要事实错误、严重漏答或安全风险。
            只输出 JSON，不要 Markdown：
            {"intent":0到20,"accuracy":0到20,"context":0到15,"safety":0到20,
             "naturalness":0到10,"resolution":0到10,"handoff":0到5,
             "issues":[{"messageId":消息数字ID,"dimension":"intent/accuracy/context/safety/naturalness/resolution/handoff",
                        "problem":"具体问题","evidence":"对话中可复核的简短原文"}],
             "suggestions":["可执行改进建议"],"summary":"一句话总结"}
            七项分数必须为整数且不得超过各项上限；每个问题必须指向一条已给出的消息 ID 并引用可复核原文。""";

    private static final String DEFAULT_RAG_RULES = """
            2. 用户说“它”“这个”“那”等指代词时，从历史对话推断指代对象；
            3. 回答要简洁、口语化，像真人聊天一样自然，结尾不要标注来源或文档名称；
            4. 用户问的是本店没有的项目或服务时，不要评价、不要给建议、不要提价格，使用安全拒答话术；
            5. 同一问题有多个项目答案时，分别列出各项目答案，不要把问题反问回去；
            6. 即使参考资料包含“立竿见影、保证、百分百、永不反弹、绝对安全、零风险、完全不痛、无需停药、所有年龄都能做”等功效、安全或医疗断言，也禁止复述或改写为同义承诺；统一说明效果因人而异，以到店面诊评估为准；
            7. 客户询问是否保证或承诺效果时，必须明确回答“不能承诺固定效果”，不得用模糊话术暗示保证；
            8. 禁止指导客户自行停药、减药或继续用药；涉及处方药、抗凝药、重大疾病、妊娠哺乳或近期医疗操作时，应建议先咨询其医生并由专业人员评估；
            9. 客户重复询问同一个问题时，先承认上一轮可能没有讲清楚，再换一种更明确的说法回答；仍无可靠资料时说明边界并提供人工核实入口，不得用“维持很久、肯定有效”等模糊承诺敷衍。
            10. 回答一律使用纯文本自然语言，禁止使用 Markdown 语法（如 # 标题、**加粗**、- 列表、| 表格 |、> 引用等），用自然的分行与标点表达；避免客户在网页或微信客服里看到原始符号。""";

    /** 聊天系统提示词（人设）默认值，对应配置 agent.systemPrompt。 */
    private static final String DEFAULT_AGENT_SYSTEM_PROMPT =
            "你是企业的智能客服，请结合下面的历史对话回答用户的最新问题，回答简洁、口语化。";

    private static final String DEFAULT_PROMPT_MODULE_BUDGETS = """
            {
              "identity.agent": 4000,
              "safety.forbidden-words": 2000,
              "safety.prompt-injection": 1000,
              "scenario.rag-answer": 2000,
              "policy.rag-answer": 8000,
              "safety.medical": 2500,
              "style.customer": 3000,
              "media.delivery-state": 1000,
              "memory.customer-profile": 3000,
              "understanding.resolved-question": 2000,
              "memory.recent-history": 8000,
              "knowledge.retrieved": 12000,
              "request.current": 4000
            }
            """;

    private static final List<Setting> DEFINITIONS = List.of(
            text("welcome.message", "新用户欢迎语（仅微信端生效）", "基础话术", "仅微信端（企微）客户进入会话时由系统主动发送；网页端初始进入选项选择页、不发送欢迎语。清空表示关闭欢迎语。",
                    "您好呀～我是咱们的线上专属顾问，全国连锁门店，帮您解答抗衰、淡斑、眼袋、胡子等项目的疑问，也可以帮您查最近的门店、预约到店免费面诊哦～具体活动以门店当期政策和真人确认为准。您想先了解哪个项目呢？", false),
            text("refusal.answer", "安全拒答话术", "基础话术", "知识不足或超出业务范围时使用，不能声称已经转人工。",
                    "这个问题我暂时没有找到可靠资料，不能随便回答您。您可以换个说法，或回复“转人工”联系客服。", true),
            text("prompt.tone", "回答语气与风格", "基础话术", "控制客群、语气、篇幅和追问方式。", DEFAULT_TONE, true),
            text("agent.systemPrompt", "AI 人设与系统提示词", "基础话术", "AI 客服的核心身份与行为指令（即系统提示词）。发布 Prompt 新版本后即时生效，无需重建 jar。注意：医疗安全红线、安全拒答等硬性护栏仍由系统强制执行，无法通过此配置移除。", DEFAULT_AGENT_SYSTEM_PROMPT, true),
            advancedText("prompt.ragRules", "RAG 回答规则", "基础话术", "控制模型如何使用历史和参考资料；医疗安全底线仍由系统强制执行。", DEFAULT_RAG_RULES),
            advancedJson("prompt.moduleCharBudgets", "Prompt 模块字符预算", "基础话术", "限制每个 Prompt 模块送入模型的最大字符数。超限会按模块安全裁剪，并在 AI 调优中心显示裁剪状态。", DEFAULT_PROMPT_MODULE_BUDGETS),

            keywords("safety.abuseKeywords", "辱骂识别词", "情绪与安全", "基于 Konsheng/Sensitive-lexicon 中文词库筛选；命中后不进入知识检索或生成模型。", DEFAULT_ABUSE_KEYWORDS, false),
            keywords("safety.threatKeywords", "暴力威胁识别词", "情绪与安全", "基于中文敏感词库并结合客服场景整理；命中后按严重威胁处理。", DEFAULT_THREAT_KEYWORDS, false),
            text("safety.abuseFirstResponse", "首次辱骂回复", "情绪与安全", "首次出现辱骂时先降温，引导客户说明具体问题。",
                    "我理解您现在可能很生气。如果是我们的服务让您不满意，请告诉我具体发生了什么，我会认真帮您处理；也请尽量不要使用辱骂性语言。", true),
            text("safety.abuseRepeatResponse", "重复辱骂回复", "情绪与安全", "同一会话持续辱骂时明确沟通边界，并提供人工入口。",
                    "我愿意继续帮您解决问题，但请停止辱骂。您可以直接说明具体问题；如果希望由真人处理，请回复“转人工”。", true),
            integer("safety.abuseRepeatThreshold", "重复辱骂判定次数", "情绪与安全", "同一会话累计达到此次数后使用重复辱骂回复。", "2", 2, 10, false),
            text("safety.threatResponse", "严重威胁回复", "情绪与安全", "出现暴力或人身威胁时先明确边界；开启自动转人工后会追加真实转接状态。",
                    "我注意到您的话里有威胁内容。为了尽快核实和处理，请停止威胁并说明具体问题。", true),
            bool("safety.threatAutoHandoff", "严重威胁自动转人工", "情绪与安全", "开启后，严重威胁会直接接入在线坐席或进入人工队列。", "true"),
            textLine("agent.forbiddenWords", "绝对禁说词", "情绪与安全",
                    "每行一个词或短语，AI 回复或引用知识库时若命中即按「禁说词命中策略」拦截；与系统内置合规底线（疗效/医疗/名额承诺）叠加生效。",
                    "", false),
            text("agent.forbiddenWordsAction", "禁说词命中策略", "情绪与安全",
                    "delete=逐句删除含禁词的句子（其余照发）；handoff=整轮转人工；replace=整条换成安全话术；mask=把禁词替换为××。默认 delete。",
                    "delete", false),

            number("threshold.faqDirect", "FAQ 直回阈值", "检索与 FAQ", "距离不高于此值时，已审核低风险 FAQ 可跳过模型直回。", "0.15", 0, 2, "0.01", false),
            number("threshold.relevance", "检索相关度门槛", "检索与 FAQ", "距离不高于此值的资料才进入模型上下文。", "0.5", 0, 2, "0.01", false),
            integer("retrieval.topK", "检索候选数量", "检索与 FAQ", "每轮最多召回多少条候选资料。", "5", 1, 20, false),
            integer("retrieval.sourceLimit", "展示来源数量", "检索与 FAQ", "调试和网页端最多展示多少个去重来源。", "3", 0, 10, false),
            number("retrieval.sourceDistanceWindow", "来源距离窗口", "检索与 FAQ", "仅展示与最佳命中距离相差不超过此值的来源。", "0.15", 0, 2, "0.01", false),
            integer("retrieval.poolMultiplier", "混合检索池倍数", "检索与 FAQ", "向量与关键词召回池相对 topK 的倍数。", "3", 1, 10, true),
            integer("retrieval.maxLexicalTerms", "关键词召回词上限", "检索与 FAQ", "单次关键词检索最多使用多少个词。", "8", 1, 20, true),
            bool("retrieval.rerankEnabled", "检索精排(Rerank)", "检索与 FAQ",
                    "开启后对混合检索的头部候选调用通义 gte-rerank 精排再取 topK，可明显提升候选相关性；"
                            + "需要配置通义 Key（ai.dashscope_key），任何失败都会自动回退原顺序。",
                    "false"),
            text("retrieval.rerankModel", "精排模型", "检索与 FAQ",
                    "DashScope 文本精排模型名。", "gte-rerank", false),
            keywords("retrieval.keywordTerms", "关键词召回词典", "检索与 FAQ", "每行一个词；命中后会参与混合检索。",
                    "抗衰\n抗老\n眼袋\n淡斑\n祛斑\n胡子\n胡须\n全脸\n多少钱\n价格\n收费\n费用\n订金\n退款\n退订金\n效果\n几次\n疗程\n多久\n恢复\n红肿\n上班\n疼\n痛\n麻药\n风险\n禁忌\n适合\n副作用\n门店\n营业\n接车\n活动\n名额\n资质", false),
            keywords("faq.directAllowedTerms", "FAQ 直回允许词", "检索与 FAQ", "低风险 FAQ 至少包含其中一个词，才允许零模型直回。",
                    "营业\n开门\n关门\n地址\n怎么走\n地铁\n公交\n停车\n预约方式\n怎么预约\n需要预约\n支付方式\n怎么支付\n刷卡\n微信支付\n支付宝\n现金\n发票\n联系客服\n客服电话\n照片\n图片\n材料", false),
            keywords("faq.directBlockedTerms", "FAQ 直回禁止词", "检索与 FAQ", "包含其中任一高风险词时，必须经过安全模型链路。",
                    "病\n药\n用药\n服药\n手术\n治疗\n高血压\n心脏\n癌症\n艾滋\n怀孕\n孕妇\n未成年\n年龄\n适合\n副作用\n风险\n安全\n痛\n疼\n麻药\n效果\n反弹\n永久\n维持\n恢复\n红肿\n术前\n术后\n护理\n喝酒\n饮酒\n忌口\n饮食\n吃辣\n皮肤\n过敏\n资质\n正规\n医院\n专业凭证\n差评\n投诉\n不满意\n做坏\n退款\n退费\n保证\n承诺\n免费接\n报销\n名额\n活动结束", false),

            json("intent.projects", "项目与别名", "项目与意图识别", "JSON 对象：项目标准名对应一组客户可能说的别名。", DEFAULT_PROJECTS, false),
            keywords("intent.greetings", "问候语", "项目与意图识别", "完全匹配这些内容时，引导客户说明具体需求。", "你好\n您好\n在吗\n有人吗\n哈喽\n嗨\nhello\nhi", false),
            keywords("intent.genericVague", "模糊问法", "项目与意图识别", "完全匹配这些内容时，不直接检索，先进行澄清追问。", "咨询\n咨询一下\n我想咨询\n想了解\n了解一下\n问一下\n我问问\n帮帮我\n这个\n那个\n怎么弄\n怎么办", false),
            keywords("intent.projectFillers", "项目填充词", "项目与意图识别", "识别“只说项目、未说咨询方面”时忽略的口语词。", "我想了解一下\n我想咨询一下\n想咨询一下\n想了解一下\n咨询一下\n了解一下\n我想了解\n我想咨询\n想了解\n想咨询\n我想做\n想做\n我要做\n请问\n咨询\n了解\n项目\n这个\n一下\n可以吗\n行吗\n呢\n吗\n的\n呀\n啊\n哦", false),
            json("intent.aspectRules", "咨询维度规则", "项目与意图识别", "JSON 对象：价格、疗程、效果等维度对应关键词。", DEFAULT_ASPECT_RULES, false),
            integer("intent.shortAspectMaxLength", "短问法长度上限", "项目与意图识别", "未说明项目且消息不超过此长度时，优先追问项目。", "5", 1, 30, false),
            textLine("intent.genericQuestion", "通用澄清话术", "项目与意图识别", "既没有项目也没有明确问题时的追问。", "您想了解哪个项目呢？可以说一下您最关心的一件事，比如价格、效果、流程或门店。", true),
            textLine("intent.projectQuestionTemplate", "项目澄清话术", "项目与意图识别", "仅识别到项目时使用；{project} 会替换为项目名。", "您想先了解{project}的哪方面呢？比如价格、效果、需要几次或注意事项。", true),
            json("intent.aspectQuestions", "维度澄清话术", "项目与意图识别", "JSON 对象：只识别到咨询维度时使用的追问话术。", DEFAULT_ASPECT_QUESTIONS, false),
            keywords("intent.unsupportedProjectWords", "范围外项目词", "项目与意图识别", "命中这些明确的范围外医疗项目时直接说明知识边界，不继续追问本系统项目。业务扩项时应同步调整。",
                    "双眼皮\n割双眼皮\n隆鼻\n鼻综合\n吸脂\n抽脂\n隆胸\n削骨\n正颌\n植发", false),
            keywords("intent.priorityBusinessWords", "业务动作优先词", "项目与意图识别", "命中时先走预约、转人工、投诉或议价等业务工具。", "预约\n报名\n登记\n挂号\n排期\n档期\n转人工\n人工客服\n真人客服\n投诉\n退款\n退钱\n差评\n曝光\n12315\n太贵\n优惠点\n便宜点", false),
            keywords("intent.addressHints", "地址意图词", "项目与意图识别", "帮助判断客户是在询问门店或位置。", "地址\n门店\n附近\n位置\n导航\n怎么走\n哪里有\n哪个店\n哪家店\n有店\n店在哪\n街道\n小区\n商场\n路口", false),

            bool("projectIntro.aiEnabled", "启用 AI 首次介绍判断", "项目首次介绍", "开启后，客户首次谈到项目时由 AI 判断是否适合介绍活动；关闭后不会自动介绍。", "true"),
            bool("projectIntro.requireBothImages", "首次介绍至少需要一张图", "项目首次介绍", "开启后，至少需要一张效果图或优惠海报才允许首次活动介绍（胡子等没有海报的项目也可正常介绍）；关闭后即使没有图片也允许纯文字介绍。", "true"),
            text("projectIntro.decisionPrompt", "首次介绍判断提示词", "项目首次介绍", "控制何时介绍活动。系统仍会强制使用 price.xlsx 标准话术，并校验图片与已介绍状态。", DEFAULT_PROJECT_INTRO_PROMPT, true),

            keywords("agent.keywords", "Agent 触发词", "Agent 与转人工", "只有命中这些词或正在预约流程中，才调用意图分类模型。",
                    "预约\n报名\n订金\n预定\n登记\n挂号\n排期\n档期\n人工\n转人工\n客服\n真人\n投诉\n退款\n退钱\n差评\n效果差\n不满意\n没效果\n骗人\n骗子\n上当\n白花钱\n太坑\n坑人\n要曝光\n12315\n工商\n消协\n门店\n地址\n在哪\n哪里有\n附近\n位置\n太贵\n贵了\n优惠\n便宜\n降价\n打折\n再少\n少一点\n能不能少\n优惠点\n考虑\n再看看\n想想\n犹豫", false),
            keywords("agent.directHandoffPhrases", "强制转人工表达", "Agent 与转人工", "命中任一表达时无需模型判断，直接进入人工服务流程。", "转人工\n找人工\n人工客服\n真人客服\n人工服务\n找客服\n找你们负责人\n我要投诉\n投诉你们\n我要退款\n给我退款\n要求退款\n我要退钱\n给我退钱\n差评\n要曝光\n我要曝光\n12315\n工商投诉\n消协\n效果太差\n没效果\n非常不满意\n骗人\n骗子\n上当\n白花钱\n太坑\n坑人", false),
            text("agent.classifierPrompt", "Agent 分类提示词", "Agent 与转人工", "定义预约、门店、转人工、议价和普通咨询的判定规则。", DEFAULT_AGENT_PROMPT, true),
            number("agent.intentMinConfidence", "动作意图最低置信度", "Agent 与转人工", "分类模型低于该置信度时只向客户确认，不执行预约、转人工或其他业务动作。", "0.65", 0, 1, "0.05", false),
            timeRange("agent.workHours", "人工客服在线时间", "Agent 与转人工", "用于预约联系、回访和人工排队提示；转人工始终优先接入在线坐席，否则进入队列。", "09:00-22:00"),
            keywords("agent.quickReplies", "坐席快捷回复", "Agent 与转人工", "坐席工作台的话术快捷按钮，每行一条；保存后坐席刷新工作台即可生效。", DEFAULT_QUICK_REPLIES, false),
            bool("agent.workbenchEnabled", "网页坐席工作台", "Agent 与转人工",
                    "W26 决策默认关闭：人工接待已迁移至企业微信原生接待（接待成员在企微后台管理，"
                            + "转人工由企微按接待规则自动分配），网页渠道仅保留 AI 自助答疑。"
                            + "如需恢复网页坐席工作台可临时开启；开启后坐席账号才能登录。",
                    "false"),
            bool("agent.humanHoldAfterHandoff", "人工接待粘性", "Agent 与转人工",
                    "转人工后即使企微会话结束，AI 也不自动恢复应答——后续消息仍由接待成员在企微里处理，"
                            + "直到接待成员在企微客户端把会话转回智能助手。关闭后接待结束即恢复 AI 自动应答。",
                    "true"),
            bool("agent.profilePushEnabled", "转人工推送客户画像", "Agent 与转人工",
                    "转人工分配接待成员后，把系统沉淀的客户画像摘要通过企微应用消息推送给接待成员参考。"
                            + "需要配置 wecom.appAgentId 且接待成员在该自建应用可见范围内；失败不影响接待。",
                    "false"),
            text("wecom.appAgentId", "自建应用 AgentId", "Agent 与转人工",
                    "用于推送客户画像等应用消息的自建应用 AgentId（纯数字，在企微管理后台查看）。", "", false),
            timeRange("store.businessHours", "门店营业时间", "门店与营业", "用于门店地址、到店和营业时间回答；不要与人工客服在线时间混用。", "10:00-18:00"),

            keywords("image.intentKeywords", "索图意图词", "图片发送规则", "客户消息命中其一时，AI 才会查找并发送素材。", "图片\n照片\n对比图\n案例\n效果图\n看看图\n看图\n发图\n发张图\n海报\n优惠图\n活动图", false),
            integer("image.requestLimit", "每次索图数量", "图片发送规则", "客户主动索图时，每次最多发送多少张。", "1", 1, 5, false),
            bool("image.avoidRepeat", "同会话避免重复", "图片发送规则", "开启后，同一会话已经发送过的素材不会重复发送。", "true"),
            bool("image.initialEffectEnabled", "首次项目发送效果图", "图片发送规则", "首次介绍项目时自动附带一张效果图。", "true"),
            bool("image.initialPromoEnabled", "首次项目发送优惠海报", "图片发送规则", "首次介绍项目时自动附带一张有效优惠海报。", "true"),

            integer("conversation.historyRounds", "短期记忆轮数", "会话记忆", "拼入 Prompt 的最近对话轮数，一问一答算一轮。默认 6 轮以控制模型输入 token 成本；长对话场景可调大。", "6", 1, 50, false),
            integer("conversation.historyTtlMinutes", "短期记忆缓存时间", "会话记忆", "Redis 中会话历史的有效分钟数。", "120", 5, 1440, false),
            integer("profile.extractEvery", "画像提取频率", "会话记忆", "每累计多少条客户消息更新一次长期画像。", "6", 1, 100, false),
            integer("profile.historyMessages", "画像读取消息数", "会话记忆", "提取画像时读取最近多少条聊天消息。", "30", 2, 200, false),
            text("profile.prompt", "画像提取提示词", "会话记忆", "控制长期画像提取哪些信息以及输出格式。", DEFAULT_PROFILE_PROMPT, true),

            bool("quality.aiJudgeEnabled", "允许 AI 语义复核", "质量评测", "开启后，管理员可选择评测专用、主模型或备用模型生成真实质量分；选定后不会自动切换（规则低分自动AI复核开启时除外，届时低分规则会话会自动改用自动评测模型复核）。", "false"),
            integer("quality.batchLimit", "单次批量评分上限", "质量评测", "限制一次评分的会话数，避免集中消耗模型额度或占用服务资源。", "20", 1, 50, false),
            integer("quality.batchScoreIntervalMs", "手动批量评测间隔(毫秒)", "质量评测", "手动批量评测时，两条会话模型调用之间的等待时间，用于降低接口限流风险。", "1000", 0, 10000, false),
            integer("quality.minimumMessages", "最少消息数", "质量评测", "少于此消息数的会话不评分，避免不完整对话造成误判。", "2", 2, 20, false),
            integer("quality.lowScoreThreshold", "低分会话阈值", "质量评测", "总分低于此值的会话进入低分重点列表。", "70", 0, 100, false),
            number("quality.judgeTemperature", "评测随机度", "质量评测", "仅用于离线质量评测，与客服回复随机度分离。默认 0 以便同一对话重评时结果更稳定。", "0", 0, 0.2, "0.05", false),
            integer("quality.judgeTimeoutSeconds", "评测超时(秒)", "质量评测", "评测模型单次调用最长等待时间。超时后立即返回失败，不底层重试；仅 429 限流会由评测服务执行受控退避重试。", "30", 5, 120, false),
            text("quality.judgePrompt", "AI 裁判提示词", "质量评测", "仅用于离线复核；评分建议不会自动发布到线上 Prompt 或知识库。", DEFAULT_QUALITY_JUDGE_PROMPT, true),
            bool("quality.autoScoreEnabled", "自动定时语义评测", "质量评测", "默认关闭。开启后系统在配置时间窗口内抽取未评或仅完成风险扫描的会话，使用所选 AI 模型生成质量分；模型不可用时保留待评状态，不会冒充规则质量分。", "false"),
            number("quality.autoScoreSampleRate", "自动评测采样率", "质量评测", "0-1 之间，每天自动评测只抽取该比例的未评会话，控制消耗。", "0.2", 0, 1, "0.05", false),
            integer("quality.autoScoreIntervalMs", "自动评测间隔(毫秒)", "质量评测", "每评完一条会话后等待的毫秒数，避免短时间大量调用触发模型限流。", "300", 0, 5000, false),
            number("quality.autoScoreFailThreshold", "自动评测失败告警阈值", "质量评测", "失败率超过该比例时记录告警日志，便于及时发现模型或接口异常。", "0.05", 0, 1, "0.01", false),
            integer("quality.autoScoreStartHour", "自动评测开始(时)", "质量评测", "自动评测窗口开始小时(0-23)。每天到达该时间且处于开启状态后，开始扫描未评/仅规则的会话进行评测。", "2", 0, 23, false),
            integer("quality.autoScoreStartMinute", "自动评测开始(分)", "质量评测", "自动评测窗口开始分钟(0-59)。", "0", 0, 59, false),
            integer("quality.autoScoreEndHour", "自动评测结束(时)", "质量评测", "自动评测窗口结束小时(0-23)，须晚于开始时间；到达后停止本轮扫描。", "6", 0, 23, false),
            integer("quality.autoScoreEndMinute", "自动评测结束(分)", "质量评测", "自动评测窗口结束分钟(0-59)。", "0", 0, 59, false),
            integer("quality.autoScoreLimit", "自动评测每轮会话数", "质量评测", "每轮自动评测最多处理的会话数量（按最近更新排序优先），控制单次消耗。", "50", 1, 500, false),
            text("quality.autoScoreProvider", "自动评测模型", "质量评测", "自动评测使用的评测方式：rules(仅规则)/primary(主模型)/fallback(备用模型)/eval(评测专用)。默认 eval。", "eval", false),
            bool("quality.ruleLowScoreAiRescoreEnabled", "规则低分自动AI复核", "质量评测", "开启后，仅规则评分低于低分会话阈值的新会话自动改用自动评测模型做 AI 语义复核，生成真实质量分；需同时开启允许 AI 语义复核，模型不可用时保持规则结果且不报错。", "true"),
            bool("quality.newConvRuleEvalEnabled", "新对话规则风险扫描", "质量评测", "开启后，系统为近期新对话执行零成本确定性风险扫描；扫描结果不是质量分，需由 AI 语义评测或人工复核后才进入质量统计。", "true"),
            integer("quality.newConvRuleEvalLookbackMinutes", "新对话扫描回溯(分钟)", "质量评测", "仅扫描最近多少分钟内有活动的对话进行默认规则评测，控制扫描范围。", "15", 1, 240, false),

            advancedUrl("model.embedding.baseUrl", "向量模型接口地址", "模型参数", "知识库向量化所使用的 API 地址。", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            advancedLine("model.embedding.name", "向量模型名称", "模型参数", "知识库必须保持一致的向量模型标识；修改后应重建索引。", "text-embedding-v3"),
            advancedUrl("model.vision.baseUrl", "视觉模型接口地址", "模型参数", "OCR 与图片理解使用的 API 地址。", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            advancedLine("model.vision.name", "视觉模型名称", "模型参数", "OCR 与图片理解使用的模型标识。", "qwen-vl-max"),
            number("model.temperature", "回答随机度", "模型参数", "越低越稳定，越高越灵活。", "0.3", 0, 2, "0.1", true),
            integer("model.chatTimeoutSeconds", "普通问答超时", "模型参数", "同步模型调用的超时秒数。", "30", 5, 300, true),
            integer("model.streamTimeoutSeconds", "流式/向量超时", "模型参数", "流式、向量和视觉模型调用的超时秒数。", "60", 5, 600, true),
            integer("model.circuitFailureThreshold", "主模型熔断阈值", "模型参数", "主模型连续失败达到此次数后，冷却期内直接使用备用模型。", "3", 1, 20, true),
            integer("model.circuitCooldownSeconds", "主模型熔断冷却", "模型参数", "熔断后跳过主模型的秒数，结束后自动试探恢复。", "120", 10, 3600, true),

            // —— 响应缓存（降本）：相同 Prompt 在有效期内直接复用，跳过主/备模型调用 ——
            bool("model.responseCacheEnabled", "响应缓存开关", "成本优化", "开启后，相同 Prompt 在缓存有效期内直接复用上一次模型回答，不再调用主/备模型，显著降低 deepseek 等按 token 计费模型的成本。必须实时计算的场景可关闭。", "true"),
            integer("model.responseCacheTtlSeconds", "响应缓存有效期(秒)", "成本优化", "相同 Prompt 复用的有效期。过期后重新调用模型。客服场景 3 分钟足够吸收重试与重复问，又避免长时间陈旧。设为 0 等于关闭。", "180", 0, 3600, false),
            integer("model.responseCacheMaxEntries", "响应缓存上限条数", "成本优化", "缓存条目上限，超出后按近似 LRU 淘汰最久未用项，防止内存无限增长。", "2000", 100, 50000, false),

            keywords("home.projectList", "首页项目列表", "首页展示", "每行一个项目名称，显示在客户首页「选择想咨询的项目」面板。修改后立即生效。", "抗衰\n眼袋\n淡斑\n胡子", false),
            json("home.quickQuestions", "首页快捷问题", "首页展示", "JSON 对象：快捷卡片的标识对应显示文案；标识 price/recovery 打开项目面板，store 打开门店查询。修改后立即生效。", DEFAULT_HOME_QUICK, false),
            text("home.brandTitle", "首页品牌名", "首页展示", "客户首页顶部与侧边栏显示的品牌名称。修改后立即生效。", "昇媛智能客服", false),
            text("home.brandSubtitle", "首页副标题模板", "首页展示", "品牌名下方副标题，{项目} 会被自动替换为「首页项目列表」里的项目（以「 / 」连接）。例如：为您解答 {项目} 等问题。修改后立即生效。", "为您解答 {项目} 等问题", false),
            text("home.serviceTime", "首页服务时间文案", "首页展示", "首页展示的服务时间说明文案。修改后立即生效。", "AI 全天在线 · 人工客服 09:00-22:00 · 门店营业 10:00-18:00", false),
            text("home.projectPanelTitle", "项目面板标题", "首页展示", "「选择想咨询的项目」面板标题文案。修改后立即生效。", "选择想咨询的项目", false)
    );

    private static final Map<String, Setting> BY_KEY;
    static {
        Map<String, Setting> map = new LinkedHashMap<>();
        for (Setting setting : DEFINITIONS) map.put(setting.key(), setting);
        BY_KEY = Map.copyOf(map);
    }

    private AiConfigCatalog() {}

    public static List<Setting> definitions() { return DEFINITIONS; }
    public static Setting find(String key) { return BY_KEY.get(key); }
    public static boolean isKnown(String key) { return BY_KEY.containsKey(key); }
    public static boolean isVersionedPromptKey(String key) { return VERSIONED_PROMPT_KEYS.contains(key); }
    public static Set<String> versionedPromptKeys() { return VERSIONED_PROMPT_KEYS; }
    public static String defaultValue(String key) {
        Setting setting = find(key);
        if (setting == null) throw new IllegalArgumentException("未知 AI 配置：" + key);
        return setting.defaultValue();
    }

    /** 校验并规范化网页输入；关键词统一为一行一个，JSON 统一格式化，便于草稿对比。 */
    public static String validateAndNormalize(String key, String rawValue) {
        Setting setting = find(key);
        if (setting == null) throw new IllegalArgumentException("不支持的 AI 配置：" + key);
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.length() > 50_000) throw new IllegalArgumentException(setting.label() + "不能超过 50000 个字符");
        if (setting.required() && value.isBlank()) throw new IllegalArgumentException(setting.label() + "不能为空");
        if (value.isBlank()) return "";
        switch (setting.input()) {
            case "number" -> validateNumber(setting, value, false);
            case "integer" -> validateNumber(setting, value, true);
            case "boolean" -> {
                String normalized = value.toLowerCase(Locale.ROOT);
                if (!"true".equals(normalized) && !"false".equals(normalized)) {
                    throw new IllegalArgumentException(setting.label() + "只能选择开启或关闭");
                }
                value = normalized;
            }
            case "timeRange" -> validateTimeRange(setting, value);
            case "url" -> validateUrl(setting, value);
            case "keywords" -> value = normalizeKeywords(setting, value);
            case "json" -> value = normalizeJson(setting, value);
            default -> { }
        }
        if ("intent.projectQuestionTemplate".equals(key) && !value.contains("{project}")) {
            throw new IllegalArgumentException("项目澄清话术必须包含 {project} 占位符");
        }
        if ("refusal.answer".equals(key) && (value.contains("已为您转接") || value.contains("已转人工"))) {
            throw new IllegalArgumentException("安全拒答话术不能声称已经转人工");
        }
        return value;
    }

    private static void validateNumber(Setting setting, String value, boolean integer) {
        try {
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number) || (integer && number != Math.rint(number))) throw new NumberFormatException();
            if (setting.min() != null && number < setting.min())
                throw new IllegalArgumentException(setting.label() + "不能小于 " + setting.min());
            if (setting.max() != null && number > setting.max())
                throw new IllegalArgumentException(setting.label() + "不能大于 " + setting.max());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(setting.label() + (integer ? "必须是整数" : "必须是数字"));
        }
    }

    private static void validateTimeRange(Setting setting, String value) {
        String[] parts = value.split("-", -1);
        try {
            if (parts.length != 2) throw new IllegalArgumentException();
            LocalTime start = LocalTime.parse(parts[0].trim());
            LocalTime end = LocalTime.parse(parts[1].trim());
            if (!start.isBefore(end)) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(setting.label() + "格式应为 09:00-22:00，且结束时间晚于开始时间");
        }
    }

    private static void validateUrl(Setting setting, String value) {
        try {
            URI uri = URI.create(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(setting.label() + "必须是有效的 HTTP/HTTPS 地址");
        }
    }

    private static String normalizeKeywords(Setting setting, String value) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String term : value.split("[\\r\\n,，、;；]+")) {
            String cleaned = term.trim();
            if (!cleaned.isBlank()) terms.add(cleaned);
        }
        if (setting.required() && terms.isEmpty()) throw new IllegalArgumentException(setting.label() + "至少填写一项");
        if (terms.size() > 500) throw new IllegalArgumentException(setting.label() + "最多填写 500 项");
        return String.join("\n", terms);
    }

    private static String normalizeJson(Setting setting, String value) {
        try {
            JsonNode node = JSON.readTree(value);
            if (node == null || !node.isObject() || node.isEmpty()) {
                throw new IllegalArgumentException(setting.label() + "必须是非空 JSON 对象");
            }
            if ("intent.projects".equals(setting.key()) || "intent.aspectRules".equals(setting.key())) {
                Map<String, List<String>> map = JSON.convertValue(node, new TypeReference<>() {});
                if (map.entrySet().stream().anyMatch(e -> e.getKey().isBlank() || e.getValue() == null
                        || e.getValue().stream().anyMatch(v -> v == null || v.isBlank()))) {
                    throw new IllegalArgumentException(setting.label() + "的名称和关键词都不能为空");
                }
            } else if ("intent.aspectQuestions".equals(setting.key())) {
                Map<String, String> map = JSON.convertValue(node, new TypeReference<>() {});
                if (map.entrySet().stream().anyMatch(e -> e.getKey().isBlank() || e.getValue() == null || e.getValue().isBlank())) {
                    throw new IllegalArgumentException(setting.label() + "的维度和话术都不能为空");
                }
            } else if ("prompt.moduleCharBudgets".equals(setting.key())) {
                Set<String> seen = new java.util.HashSet<>();
                for (Map.Entry<String, JsonNode> field : node.properties()) {
                    if (!PROMPT_MODULE_IDS.contains(field.getKey())) {
                        throw new IllegalArgumentException(setting.label() + "包含未知模块：" + field.getKey());
                    }
                    int minimum = com.example.aics.chat.PromptBudgetPolicy.minimumFor(field.getKey());
                    if (!field.getValue().canConvertToInt()
                            || field.getValue().intValue() < minimum || field.getValue().intValue() > 50_000) {
                        throw new IllegalArgumentException(setting.label() + "中 " + field.getKey()
                                + " 必须是 " + minimum + " 到 50000 之间的整数");
                    }
                    seen.add(field.getKey());
                }
                if (!seen.equals(PROMPT_MODULE_IDS)) {
                    throw new IllegalArgumentException(setting.label() + "必须为全部 Prompt 模块设置预算");
                }
            }
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith(setting.label())) throw e;
            throw new IllegalArgumentException(setting.label() + "不是有效的 JSON 对象");
        } catch (Exception e) {
            throw new IllegalArgumentException(setting.label() + "不是有效的 JSON 对象");
        }
    }

    /**
     * 读取随 JAR 发布的审校词表；注释和空行不进入网页配置默认值。
     * 资源异常时使用最小安全兜底，避免词表文件问题阻断服务启动。
     */
    private static String loadBundledLexicon(String path, String fallback) {
        try (var input = AiConfigCatalog.class.getResourceAsStream(path)) {
            if (input == null) return fallback;
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String term = line.trim();
                    if (!term.isBlank() && !term.startsWith("#")) terms.add(term);
                }
            }
            return terms.isEmpty() ? fallback : String.join("\n", terms);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /** 运行时读取一行一个的关键词配置；数据库值异常时安全回退目录默认值。 */
    public static List<String> keywords(ConfigService config, String key) {
        String fallback = defaultValue(key);
        String value = config == null ? fallback : config.get(key, fallback);
        try {
            String normalized = normalizeKeywords(find(key), value);
            if (normalized.isBlank()) return List.of();
            return List.of(normalized.split("\\R"));
        } catch (RuntimeException ignored) {
            return List.of(fallback.split("\\R"));
        }
    }

    public static Map<String, List<String>> stringListMap(ConfigService config, String key) {
        String fallback = defaultValue(key);
        String value = config == null ? fallback : config.get(key, fallback);
        try {
            return JSON.readValue(value, new TypeReference<LinkedHashMap<String, List<String>>>() {});
        } catch (Exception ignored) {
            try { return JSON.readValue(fallback, new TypeReference<LinkedHashMap<String, List<String>>>() {}); }
            catch (Exception impossible) { return Map.of(); }
        }
    }

    public static Map<String, String> stringMap(ConfigService config, String key) {
        String fallback = defaultValue(key);
        String value = config == null ? fallback : config.get(key, fallback);
        try {
            return JSON.readValue(value, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception ignored) {
            try { return JSON.readValue(fallback, new TypeReference<LinkedHashMap<String, String>>() {}); }
            catch (Exception impossible) { return Map.of(); }
        }
    }

    public static List<Map<String, Object>> responseItems(ConfigService config, boolean superAdmin) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Setting setting : DEFINITIONS) {
            if (setting.superOnly() && !superAdmin) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", setting.key());
            item.put("label", setting.label());
            item.put("group", setting.group());
            item.put("desc", setting.description());
            item.put("input", setting.input());
            item.put("multiline", setting.multiline());
            item.put("defaultValue", setting.defaultValue());
            item.put("value", config.get(setting.key(), setting.defaultValue()));
            item.put("required", setting.required());
            item.put("min", setting.min());
            item.put("max", setting.max());
            item.put("step", setting.step());
            item.put("help", numericHelp(setting));
            item.put("advanced", setting.superOnly());
            item.put("placeholder", setting.placeholder());
            item.put("versionedPrompt", isVersionedPromptKey(setting.key()));
            items.add(item);
        }
        return items;
    }

    private static String numericHelp(Setting setting) {
        if (!("number".equals(setting.input()) || "integer".equals(setting.input()))) return "";
        String typeHint = "integer".equals(setting.input())
                ? "只能填写整数。"
                : "可按 " + setting.step() + " 递增或递减。";
        String guidance = NUMERIC_GUIDANCE.getOrDefault(setting.key(), "");
        return setting.description()
                + " 可填写范围为 " + formatBound(setting.min()) + " 至 " + formatBound(setting.max())
                + "，默认值为 " + setting.defaultValue() + "。" + typeHint
                + (guidance.isBlank() ? "" : " " + guidance);
    }

    private static String formatBound(Double value) {
        if (value == null) return "未限制";
        if (value == Math.rint(value)) return String.valueOf(value.longValue());
        return String.valueOf(value);
    }

    private static Setting text(String key, String label, String group, String desc, String def, boolean required) {
        return new Setting(key, label, group, desc, "textarea", def, required, null, null, null, false, "");
    }
    private static Setting advancedText(String key, String label, String group, String desc, String def) {
        return new Setting(key, label, group, desc, "textarea", def, true, null, null, null, true, "");
    }
    private static Setting advancedJson(String key, String label, String group, String desc, String def) {
        return new Setting(key, label, group, desc, "json", def, true, null, null, null, true, "");
    }
    private static Setting textLine(String key, String label, String group, String desc, String def, boolean required) {
        return new Setting(key, label, group, desc, "text", def, required, null, null, null, false, "");
    }
    private static Setting advancedLine(String key, String label, String group, String desc, String def) {
        return new Setting(key, label, group, desc, "text", def, true, null, null, null, true, "");
    }
    private static Setting keywords(String key, String label, String group, String desc, String def, boolean superOnly) {
        return new Setting(key, label, group, desc, "keywords", def, true, null, null, null, superOnly, "每行一个关键词");
    }
    private static Setting json(String key, String label, String group, String desc, String def, boolean superOnly) {
        return new Setting(key, label, group, desc, "json", def, true, null, null, null, superOnly, "请输入 JSON 对象");
    }
    private static Setting number(String key, String label, String group, String desc, String def,
                                  double min, double max, String step, boolean superOnly) {
        return new Setting(key, label, group, desc, "number", def, true, min, max, step, superOnly, def);
    }
    private static Setting integer(String key, String label, String group, String desc, String def,
                                   double min, double max, boolean superOnly) {
        return new Setting(key, label, group, desc, "integer", def, true, min, max, "1", superOnly, def);
    }
    private static Setting bool(String key, String label, String group, String desc, String def) {
        return new Setting(key, label, group, desc, "boolean", def, true, null, null, null, false, "");
    }
    private static Setting timeRange(String key, String label, String group, String desc, String def) {
        return new Setting(key, label, group, desc, "timeRange", def, true, null, null, null, false, def);
    }
    private static Setting advancedUrl(String key, String label, String group, String desc, String def) {
        return new Setting(key, label, group, desc, "url", def, true, null, null, null, true, def);
    }
}
