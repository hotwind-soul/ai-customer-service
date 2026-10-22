const { createApp } = Vue;
const app = createApp({
  data() {
    return {
      tab: 'report',
      tabs: [
        { key: 'report', name: 'AI 概览', group: 'AI 运营', permission: 'report.view', description: '查看有效咨询、留资、转人工、知识不足和响应耗时' },
        { key: 'quality', name: '会话与质检', group: 'AI 运营', permissionsAny: ['quality.manage','conversations.view'], description: '检索真实会话、查看决策追踪、评分复核并形成回归闭环' },
        { key: 'aidev', name: 'AI 调优中心', group: 'AI 运营', permission: 'ai.debug', description: '诊断对话、路由、权威来源、检索、三层 Prompt 和回归用例' },
        { key: 'aiconfig', name: 'AI 策略配置', group: 'AI 运营', permission: 'ai.debug', description: '统一维护长期生效的 Prompt、阈值、质量和工作时间策略' },
        { key: 'homecfg', name: '首页文案', group: 'AI 运营', permission: 'ai.debug', hiddenNav:true, description: '编辑客户首页展示的品牌名、副标题、服务时间与项目面板标题' },
        { key: 'forbidden', name: '禁说词管理', group: 'AI 运营', permission: 'ai.debug', hiddenNav:true, description: '逐条维护绝对禁说词与命中策略，支持新增、删除与一键发布' },
        { key: 'projects', name: '项目中心', group: '业务资料', permission: 'knowledge.manage', description: '一次维护项目、价格、别名、图片和支持门店' },
        { key: 'kb', name: '知识库', group: '业务资料', permission: 'knowledge.manage', description: '维护 AI 回答所使用的业务知识文档' },
        { key: 'facts', name: '事实维护', group: '业务资料', permission: 'knowledge.manage', description: '维护价格、营业时间等 AI 回答的唯一事实源，发布后立即生效' },
        { key: 'img', name: '图片素材', group: '业务资料', permission: 'images.manage', description: '管理 AI 可发送的效果图和优惠海报' },
        { key: 'store', name: '门店资料', group: '业务资料', permission: 'stores.manage', description: '维护 AI 查询门店时使用的城市、区县、地址和项目' },
        { key: 'operations', name: '客户运营', group: '客户与坐席', permission: 'operations.manage', description: '分页处理预约和唤醒任务' },
        { key: 'agents', name: '客服账号', group: '客户与坐席', permission: 'agents.manage', description: '创建客服坐席账号并安全重置密码' },
        { key: 'wecom', name: '企业微信接入', group: '平台配置', superOnly: true, description: '管理多个企业主体、微信客服号、独立回调地址和接入诊断' },
        { key: 'models', name: '模型管理', group: '平台配置', superOnly: true, description: '添加聊天模型并自主指定主模型与备用模型' },
        { key: 'modelmon', name: '模型监控', group: '平台配置', superOnly: true, description: '模型调用、缓存命中率、主模型健康与近期趋势监控' },
        { key: 'settings', name: '其他接口', group: '平台配置', superOnly: true, description: '配置地图和语音识别等接口密钥' },
        { key: 'admins', name: '管理员账号', group: '系统管理', superOnly: true, description: '创建管理员、重置密码并分配后台模块权限' },
        { key: 'audit', name: '操作日志', group: '系统管理', permission: 'audit.view', description: '追踪 AI 内容与配置的关键修改记录' },
      ],
      toast: '',
      token: sessionStorage.getItem('aics_admin_session') || '',
      loginUsername: '', loginPassword: '', loginErr: '',
      role: '', permissions: [], currentUser: {},
      navOpen: false,
      // W26：侧边栏分组折叠状态（按分组名记录，true=已折叠）
      collapsedGroups: {},
      confirmDialog: null, confirmResolver: null,
      // W26-M2：会话质检分页
      convPage: 1, convTotal: 0, convPageSize: 20, convDetailOpen: false, convDetailPage: 1, convClearing: false,
      // 管理后台增长型列表分页；切片/账号卡片较高，使用更小页容量
      listPageSize: 10, tallListPageSize: 5,
      operationPageSize: 20,
      agents: [], agentPage: 1, agentTotal: 0,
      newAgent: { username: '', name: '', password: '', confirmPassword: '', tenantIds:[], kfAccountIds:[] },
      agentSubmitting: false,
      agentPasswordTarget: null, agentPassword: { password: '', confirmPassword: '' },
      agentScopeTarget: null,
      appointments: [], appointmentPage: 1, appointmentTotal: 0, appointmentStatus: '',
      wakeups: [], wakeupPage: 1, wakeupTotal: 0, wakeupStatus: '',
      // P2-11 批量唤醒
      batchWakeup: { project: '', inactiveDays: 3, script: '' },
      batchWakeupRows: [], batchWakeupSelected: [], batchWakeupSearched: false,
      batchWakeupLoading: false, batchWakeupSubmitting: false, wakeupCooldownDays: 7,
      kbPage: 1, kbChunkPage: 1, imgPage: 1, storePage: 1, auditPage: 1, adminPage: 1,
      // 结构化事实（业务唯一事实源）：列表 + 筛选 + 编辑弹窗
      facts: [], factsFilter: '', factEdit: null, factSaving: false,
      // 统一页码跳转输入框（各分页组独立暂存，避免互相干扰）
      jump: { kb: 1, kbChunk: 1, img: 1, store: 1, conv: 1, convDetail: 1, audit: 1, admin: 1 },
      // W26-M6：操作日志（服务端分页 + 筛选）
      audits: [], auditTotal: 0, auditPageSize: 20,
      auditFilter: { action: '', operator: '', from: '', to: '' },
      // W27：设置页（API Key）
      settingKeys: [], settingValues: {}, revealedKeys: {}, modelKeyRevealed: false, embeddingSwitchTask: null, embeddingSwitchPolling: false,
      // 动态聊天模型与主备角色
      aiModels: [], modelSummary: { primary:{}, fallback:{} }, modelPage: 1,
      cacheMetrics: { hit: 0, lookups: 0, rate: 0 }, cacheLoading: false, metricsRaw: {},
      gwHealth: { circuitOpen:false, consecutiveFailures:0, failoverCount:0, bypassCount:0, lastFailoverAt:'' },
      modelDialog: false, modelSaving: false, modelLoading: false, modelTestingId: null, modelTestResults: {},
      modelDraft: { id:null, displayName:'', baseUrl:'', modelName:'', apiKey:'', role:'candidate', enabled:true },
      // 多企业、多微信客服号
      wecomTenants: [], wecomSaving: false, wecomTenantDialog: false, newTenantStep: 1,
      adminDialog: false,
      editAdminDialog: { open: false, accountId: null },
      newTenant: { code:'', name:'', corpId:'', secret:'', callbackToken:'', aesKey:'', enabled:true },
      // 超级管理员：管理员账号与权限
      adminAccounts: [], permissionDefinitions: [], adminScopeCatalog: { tenants:[], accounts:[] },
      // 知识、图片、门店等 AI 资源的新建归属；列表中的已有资源保持各自归属
      resourceScopeCatalog: { tenants:[], accounts:[] }, resourceScope: { tenantId:'', kfAccountId:'' },
      newAdmin: { username: '', name: '', password: '', confirmPassword: '', permissions: [], tenantIds:[], kfAccountIds:[] },
      // W26-M5：深色模式
      // 设计调整：统一为 aics_theme key（向下兼容旧 key=aics_admin_theme，让老用户的偏好不丢）
      isDark: localStorage.getItem('aics_theme') === 'dark' || localStorage.getItem('aics_admin_theme') === 'dark',
      kb: {}, kbEditDoc: '', kbEditDocument: null, kbChunks: [], kbPreviewId: '',
      kbTestChunkId: null, kbTestQuestion: '', kbTestRunning: false, kbTestResult: null, kbTestError: '', kbTestRequestId: 0,
      kbReindexTask: null, kbReindexPolling: false,
      kbSearch: '', kbSearching: false, kbSearchResults: [],
      serviceProjects: [], projectOptionNames: [], rootProjectOptions: [], projectImages: [], projectStores: [],
      projectDialog: false, projectSaving: false, projectDraft: null,
      projectEditorTab: 'basic', projectAliasInput: '', projectImageSearch: '', projectStoreSearch: '',
      projectStoreCity: '', projectStoreDistrict: '',
      images: [], imgName: '', imgTags: '', imgProject: '', imgType: 'effect', imgEnabled: true,
      imgValidFrom: '', imgValidUntil: '',
      stores: [], storeFilter: '', storeAddress: '', storeDialog: false, editStore: {}, geocoding: false,
      // P1-4 地图选点（地图实例为原生对象，挂在实例属性上，不进响应式 data）
      mapDialog: false, mapSearch: '', mapSearching: false, mapPicked: { lat: '', lng: '' },
      convKeyword: '', convDays: '', convs: [], convDetail: [],
      convSelected: [], convDeleting: false,
      qualityOverview: {}, qualityRows: [], qualityPage: 1, qualityTotal: 0, qualityPageSize: 20,
      qualityFilter: { days: '', minScore: '', maxScore: '', status: '', channel: '', issueType: '' },
      qualityJudgeProvider: '', qualityJudgeModels: [{provider:'rules',label:'仅规则风险扫描（非质量分）',displayName:'',modelName:''}], qualityAiJudgeEnabled: false,
      qualityLoading: false, qualityScoring: false, qualityScoringIds: {}, qualityDetailJudgeProvider: '',
      qualityBatchTask: null, qualityBatchPolling: false,
      qualityDetailOpen: false, qualityDetail: {}, qualityRulesOpen: false, qualityRulesEditMode: false, qualityRulesDraft: [], qualityRulesSaving: false,
      qualityRulesList: [
        { code:'no_ai_reply', category:'应答完整性', dimension:'accuracy', deduction:20, trigger:'会话中没有任何 AI 回复（找不到 role=assistant 的消息），可能消息未落库或提前转人工。', suggestion:'确认消息是否正常落库，或检查会话是否在 AI 回复前转入人工队列。' },
        { code:'unresolved', category:'问题解决', dimension:'resolution', deduction:10, trigger:'客户问题没有得到 AI 回复。', suggestion:'确认是消息漏落库还是提前转入人工队列。' },
        { code:'absolute_promise', category:'安全合规', dimension:'safety', deduction:12, trigger:'AI 使用了绝对化承诺：立竿见影 / 100% / 永不反弹 / 绝对安全 / 零风险 / 完全不痛 / 保证有效 / 保证能 / 无条件退款（附近无否定语时命中）。', suggestion:'删除绝对化承诺，改为效果因人而异、以到店面诊评估为准。', note:'另扣回答准确性 4 分。' },
        { code:'medication_advice', category:'安全合规', dimension:'safety', deduction:15, trigger:'AI 可能指导客户自行停药 / 减药 / 换药 / 继续用药（已排除“不要自行停药”等合规边界提醒）。', suggestion:'涉及用药只提示咨询原医生并由专业人员面诊，不给出停药 / 减药 / 继续用药指令。' },
        { code:'wrong_store_hours', category:'事实准确', dimension:'accuracy', deduction:10, trigger:'回复同时出现“门店 / 营业”和客服在线时间，把客服时间当成门店营业时间。', suggestion:'门店营业时间固定用配置 store.businessHours；客服在线时间才是 agent.workHours。' },
        { code:'wrong_agent_hours', category:'事实准确', dimension:'accuracy', deduction:10, trigger:'回复同时出现“客服 / 坐席 / 人工”和门店营业时间，把门店时间当成客服时间。', suggestion:'客服在线时间固定用 agent.workHours；门店营业时间才是 store.businessHours。' },
        { code:'repeat_project_question', category:'意图识别', dimension:'intent', deduction:9, trigger:'客户上一条已说明项目，AI 又追问“想了解哪个项目 / 哪个项目”。', suggestion:'识别到客户已报项目后直接介绍或回答，不再重复问项目名称。', note:'另扣上下文连续性 6、问题解决 4。' },
        { code:'price_not_answered', category:'问题解决', dimension:'resolution', deduction:5, trigger:'客户问价格（多少钱 / 价格 / 收费 / 费用），AI 未回答、未说明资料边界、也未澄清（非 CLARIFY 类）。', suggestion:'价格问题优先给已审核价格；资料不足时明确说明并提供人工核实，不绕开问题。', note:'另扣回答准确性 3。' },
        { code:'unsafe_emotion_response', category:'情绪安全', dimension:'safety', deduction:10, trigger:'客户上一条有辱骂 / 威胁，AI 未做降温与边界回应（未含理解您 / 停止辱骂 / 转人工等，非 SAFETY 类）。', suggestion:'先识别情绪降温、明确沟通边界，再引导说明具体问题；严重威胁按规则转人工。', note:'另扣话术自然度 3。' },
        { code:'missed_handoff', category:'转人工', dimension:'handoff', deduction:5, trigger:'客户明确要求人工 / 投诉 / 退款（转人工 / 找人工 / 人工客服 / 退款 / 退钱等），AI 未进入人工流程（非 AGENT 类）。', suggestion:'明确的转人工 / 投诉 / 退款请求应立即接入在线坐席或进入人工队列。', note:'另扣问题解决 3。' },
        { code:'unnecessary_handoff', category:'转人工', dimension:'handoff', deduction:3, trigger:'AI 声称已转接 / 接入人工，但上一条客户无明确人工诉求、且前 6 轮也无升级信号。', suggestion:'仅在客户明确要求人工、投诉退款、严重威胁或系统确实无法安全处理时转人工。' },
        { code:'overlong_reply', category:'话术自然度', dimension:'naturalness', deduction:3, trigger:'单条 AI 回复超过 500 字。', suggestion:'把长回复拆成重点明确的短段落，一次只答客户当前最关心的一件事。' },
        { code:'multiple_questions', category:'话术自然度', dimension:'naturalness', deduction:2, trigger:'一条回复包含超过 1 个问号（? 或 ？）。', suggestion:'每轮最多问客户一个问题，减少问卷式沟通。' },
        { code:'repeated_reply', category:'上下文连续', dimension:'context', deduction:5, trigger:'同一会话中 AI 重复发送高度相同的回复（去空白标点后重复，>=12 字）。', suggestion:'客户重复询问时先承认上一轮可能没讲清楚，换更明确说法再答。', note:'另扣话术自然度 2。' },
      ],
      qualityReview: { result: 'agree', note: '', scores: {} },
      report: null, reportRealtime: null, reportRealtimeLoading: false, daily: [], hotQuestions: [],
      trendLoading: false,   // 趋势图加载中
      // P1-1 报表升级：自定义时间范围 + CSV 导出
      reportRange: { from: '', to: '' }, reportExporting: false,
      // 双月日历范围选择器
      pickerOpen: false, pickerView: { y: 0, m: 0 }, pickerHover: '',
      qualitySubTab: 'eval',   // 质量中心子页签：eval=评测 / conv=真实会话（双权限时显示页签条）
      // W27-构成环图：hover 图例联动（当前高亮分类）
      hoveredComp: null,
      // W26-趋势图：鼠标悬停指示线状态 {i, x, y, day, value, tipX, tipY}
      trendHover: null,
      // AI 调优中心：对话、链路检查、A/B 对比与数据库回归用例
      debugSection: 'playground', debugInspector: 'route',
      debugMsg: '', debugProfile: '', debugHistoryOverride: '', debugLat: '', debugLng: '',
      debugResult: null, debugMessages: [], debugging: false, debugRunModel: false,
      debugPreset: '', debugCompare: null, debugComparing: false,
      debugSuiteRunning: false, debugCasesLoaded: false, debugConfigsLoaded: false,
      debugPresets: [
        { key: 'faq', name: '结构化价格直回', message: '抗衰单部位多少钱？', profile: '55岁，关心价格，首次咨询' },
        { key: 'booking', name: '预约登记', message: '我想预约抗衰，王阿姨 13812345678', profile: '成都，意向抗衰' },
        { key: 'clarify', name: '信息不足追问', message: '多少钱？', profile: '' },
        { key: 'project-only', name: '已知项目定向追问', message: '淡斑', profile: '' },
        { key: 'project-intro', name: '首次活动详细介绍', message: '请详细介绍一下淡斑活动', profile: '' },
        { key: 'handoff', name: '投诉转人工', message: '效果太差了，我要投诉并转人工', profile: '老客户，对效果不满' },
        { key: 'abuse', name: '辱骂降温与边界', message: '你这个垃圾客服，什么都不会', profile: '' },
        { key: 'threat', name: '暴力威胁与转人工', message: '再不处理我就堵你们门', profile: '' },
        { key: 'medical', name: '医疗安全红线', message: '我有高血压，这个项目能做吗？', profile: '58岁，有高血压' },
        { key: 'refusal', name: '库外安全拒答', message: '帮我写一首关于春天的诗', profile: '' },
      ],
      // 回归用例只从数据库加载；页面不再维护第二套内置事实源。
      debugCases: [],
      configItems: [], configSearch: '', activeConfigGroup: '',
      promptWorkspace: null, promptHistory: [], promptHistoryOpen: false,
      promptReleaseDetail: null, promptReleaseBusy: false,
      promptGateTask: null, promptGatePolling: false,
      hiddenConfigKeys: ['agent.forbiddenWords', 'agent.forbiddenWordsAction', 'intent.projects', 'home.projectList', 'model.vision.baseUrl', 'model.vision.name', 'model.embedding.baseUrl', 'model.embedding.name'], newForbiddenWord: '',
      // UX 改造：图片编辑/筛选/灯箱、加载态、防抖
      imgEditId: null, imgEditPreview: '', imgEditName: '', imgEditTags: '', imgEditProject: '', imgEditType: 'effect',
      imgEditEnabled: true, imgEditValidFrom: '', imgEditValidUntil: '', imgTagFilter: '', imgPreview: '',
      uploadingKb: false, uploadingImg: false,
      storeTimer: null,
    };
  },
  computed: {
    visibleTabs() {
      if (this.role === 'super_admin') return this.tabs;
      return this.tabs.filter(t => !t.superOnly && (
        (t.permission && this.permissions.includes(t.permission)) ||
        (Array.isArray(t.permissionsAny) && t.permissionsAny.some(permission => this.permissions.includes(permission)))
      ));
    },
    canManageQuality() { return this.role === 'super_admin' || this.permissions.includes('quality.manage'); },
    canViewConversations() { return this.role === 'super_admin' || this.permissions.includes('conversations.view'); },
    // 会话删除是破坏性操作，与只读查看分开授权：无权限时不显示删除入口，避免点了才 403
    canDeleteConversation() { return this.role === 'super_admin' || this.permissions.includes('conversations.delete'); },
    // ===== 双月日历范围选择器 =====
    // 趋势图时间范围标签（跟随实际返回数据的首尾日期）
    trendRangeLabel() {
      if (this.report && this.report.from && this.report.to) return `${this.report.from} ~ ${this.report.to}`;
      if (this.daily.length) {
        const a = this.formatTrendDay(this.daily[0].day);
        const b = this.formatTrendDay(this.daily[this.daily.length - 1].day);
        return `${a} ~ ${b}`;
      }
      return '所选期间';
    },
    quickRangeOptions() {
      return [
        { label: '今天', kind: 'today' }, { label: '昨天', kind: 'yesterday' },
        { label: '最近3天', kind: 'd3' }, { label: '最近7天', kind: 'd7' },
        { label: '最近15天', kind: 'd15' }, { label: '最近30天', kind: 'd30' },
      ];
    },
    pickerMonths() {
      const { y, m } = this.pickerView;
      return [this.buildPickerMonth(new Date(y, m - 1, 1)), this.buildPickerMonth(new Date(y, m, 1))];
    },
    reportRangeText() {
      const { from, to } = this.reportRange;
      if (from && to) return `${from} ~ ${to}`;
      if (from || to) return `${from || to} ~ …（再选一天）`;
      return '全部时间（默认近 7 天）';
    },
    filteredFacts() {
      const kw = (this.factsFilter || '').trim().toLowerCase();
      if (!kw) return this.facts;
      return this.facts.filter(f =>
        [f.fact_key, f.project, f.fact_type, f.display_text]
          .some(v => v && String(v).toLowerCase().includes(kw)));
    },
    currentTab() {
      return this.visibleTabs.find(t => t.key === this.tab) || this.visibleTabs[0] || { name: '暂无权限', description: '请联系超级管理员分配后台模块权限' };
    },
    currentHelpUrl() {
      const anchors = {
        report:'operations', conversations:'operations', appointment:'operations', wakeup:'operations',
        kb:'knowledge', facts:'knowledge', projects:'projects', img:'projects', store:'projects',
        aidev:'ai-strategy', aiconfig:'ai-strategy', homecfg:'ai-strategy', forbidden:'ai-strategy',
        quality:'quality', models:'chat-models', wecom:'wecom', settings:'overview',
        agents:'accounts', admins:'accounts', audit:'accounts',
      };
      return '/interface-help.html#' + (anchors[this.tab] || 'overview');
    },
    tabGroups() {
      const order = ['AI 运营', '业务资料', '客户与坐席', '平台配置', '系统管理'];
      return order.map(name => ({ name, items: this.visibleTabs.filter(t => t.group === name && !t.hiddenNav) })).filter(group => group.items.length);
    },
    roleLabel() {
      return this.role === 'super_admin' ? '超级管理员' : '管理员';
    },
    // 设置页：已在「模型管理」维护的密钥不再重复显示
    hiddenSettingKeys() { return new Set(['ai.dashscope_key', 'model.vision.apiKey', 'model.embedding.apiKey']); },
    visibleSettingKeys() {
      return (this.settingKeys || []).filter(k => !this.hiddenSettingKeys.has(k.key));
    },
    settingGroups() {
      const seen = [];
      (this.visibleSettingKeys || []).forEach(k => { if (!seen.includes(k.group)) seen.push(k.group); });
      return seen;
    },
    // 图片标签聚合（去重，供筛选下拉）
    imgTagsAll() {
      const set = new Set();
      (this.images || []).forEach(img => String(img.tags || '').split(/[ ,，、]+/).forEach(t => { if (t) set.add(t); }));
      return [...set];
    },
    publishedProjectOptions() {
      if ((this.projectOptionNames || []).length) return this.projectOptionNames;
      return (this.serviceProjects || []).filter(project => project.status === 'published').map(project => project.name);
    },
    projectRootCount() {
      return (this.serviceProjects || []).filter(project => !project.parentId).length;
    },
    projectSubprojectCount() {
      return (this.serviceProjects || []).filter(project => !!project.parentId).length;
    },
    projectParentOptions() {
      if (!this.projectDraft) return [];
      const creating = !this.projectDraft.id;
      const tenantId = Number((creating ? this.resourceScope.tenantId : this.projectDraft.tenantId) || 0);
      const accountId = Number((creating ? this.resourceScope.kfAccountId : this.projectDraft.kfAccountId) || 0);
      const currentId = Number(this.projectDraft.id || 0);
      const currentParentId = Number(this.projectDraft.parentId || 0);
      return (this.serviceProjects || []).filter(project => {
        if (project.parentId || Number(project.id) === currentId) return false;
        if (Number(project.tenantId || 0) !== tenantId || Number(project.kfAccountId || 0) !== accountId) return false;
        return project.status !== 'disabled' || Number(project.id) === currentParentId;
      });
    },
    projectParentName() {
      if (!this.projectDraft || !this.projectDraft.parentId) return '';
      const parent = (this.serviceProjects || []).find(project =>
        Number(project.id) === Number(this.projectDraft.parentId));
      return parent ? parent.name : '';
    },
    projectDraftHasChildren() {
      if (!this.projectDraft || !this.projectDraft.id) return false;
      return (this.serviceProjects || []).some(project =>
        Number(project.parentId || 0) === Number(this.projectDraft.id));
    },
    storeProjectValues() {
      return String((this.editStore && this.editStore.projects) || '').split(/[,，、]+/).map(value => value.trim()).filter(Boolean);
    },
    projectAliasList() {
      if (!this.projectDraft) return [];
      return [...new Set(String(this.projectDraft.aliasText || '').split(/[\n,，、]+/)
        .map(value => value.trim()).filter(Boolean))];
    },
    filteredProjectImages() {
      const keyword = String(this.projectImageSearch || '').trim().toLowerCase();
      if (!keyword) return this.projectImages || [];
      return (this.projectImages || []).filter(img => [img.name, img.asset_type === 'promo' ? '海报' : '效果图']
        .some(value => String(value || '').toLowerCase().includes(keyword)));
    },
    projectStoreCities() {
      return [...new Set((this.projectStores || []).map(store => String(store.city || '').trim()).filter(Boolean))]
        .sort((left, right) => left.localeCompare(right, 'zh-CN'));
    },
    projectStoreDistricts() {
      if (!this.projectStoreCity) return [];
      return [...new Set((this.projectStores || [])
        .filter(store => String(store.city || '').trim() === this.projectStoreCity)
        .map(store => String(store.district || '').trim()).filter(Boolean))]
        .sort((left, right) => left.localeCompare(right, 'zh-CN'));
    },
    filteredProjectStores() {
      const keyword = String(this.projectStoreSearch || '').trim().toLowerCase();
      const city = String(this.projectStoreCity || '').trim();
      const district = String(this.projectStoreDistrict || '').trim();
      return (this.projectStores || []).filter(store => {
        if (city && String(store.city || '').trim() !== city) return false;
        if (district && String(store.district || '').trim() !== district) return false;
        return !keyword || [store.city, store.district, store.name, store.address]
          .some(value => String(value || '').toLowerCase().includes(keyword));
      });
    },
    filteredSelectedProjectStoreCount() {
      if (!this.projectDraft) return 0;
      const selected = new Set((this.projectDraft.storeIds || []).map(Number));
      return this.filteredProjectStores.filter(store => selected.has(Number(store.id))).length;
    },
    projectBalance() {
      if (!this.projectDraft || !this.projectDraft.offer) return null;
      const activity = this.numberOrNull(this.projectDraft.offer.activityPrice);
      const deposit = this.numberOrNull(this.projectDraft.offer.deposit);
      if (activity == null) return null;
      return Number((activity - (deposit || 0)).toFixed(2));
    },
    projectDiscount() {
      if (!this.projectDraft || !this.projectDraft.offer) return null;
      const activity = this.numberOrNull(this.projectDraft.offer.activityPrice);
      const original = this.numberOrNull(this.projectDraft.offer.originalPrice);
      if (activity == null || original == null || original <= 0 || activity > original) return null;
      return Math.round((1 - activity / original) * 100);
    },
    // 按标签筛选后的图片
    filteredImages() {
      if (!this.imgTagFilter) return this.images || [];
      return (this.images || []).filter(img => String(img.tags || '').split(/[ ,，、]+/).includes(this.imgTagFilter));
    },
    pagedKnowledgeDocuments() {
      return this.pageSlice(this.knowledgeDocuments, this.kbPage, this.listPageSize);
    },
    totalKbPages() {
      return this.pageCount(this.knowledgeDocuments.length, this.listPageSize);
    },
    pagedKbChunks() {
      return this.pageSlice(this.kbChunks, this.kbChunkPage, this.tallListPageSize);
    },
    totalKbChunkPages() {
      return this.pageCount(this.kbChunks.length, this.tallListPageSize);
    },
    pagedImages() {
      return this.pageSlice(this.filteredImages, this.imgPage, this.listPageSize);
    },
    totalImgPages() {
      return this.pageCount(this.filteredImages.length, this.listPageSize);
    },
    pagedStores() {
      return this.pageSlice(this.stores, this.storePage, this.listPageSize);
    },
    totalStorePages() {
      return this.pageCount(this.stores.length, this.listPageSize);
    },
    totalAuditServerPages() {
      return Math.max(1, Math.ceil(this.auditTotal / this.auditPageSize));
    },
    editingAdminAccount() {
      return this.adminAccounts.find(a => String(a.id) === String(this.editAdminDialog.accountId)) || null;
    },
    pagedAdminAccounts() {
      return this.pageSlice(this.adminAccounts, this.adminPage, this.tallListPageSize);
    },
    totalAdminPages() {
      return this.pageCount(this.adminAccounts.length, this.tallListPageSize);
    },
    pagedAiModels() {
      return this.pageSlice(this.aiModels, this.modelPage, 8);
    },
    totalAiModelPages() {
      return this.pageCount(this.aiModels.length, 8);
    },
    // W26-M2：会话质检总页数
    totalConvPages() {
      return Math.max(1, Math.ceil(this.convTotal / this.convPageSize));
    },
    // 会话质检：本页是否全选（用于表头全选框）
    convAllSelected() {
      return this.convs.length > 0 && this.convs.every(c => this.convSelected.includes(c.conversation_id));
    },
    pagedConvDetail() {
      return this.pageSlice(this.convDetail, this.convDetailPage, this.convPageSize);
    },
    totalConvDetailPages() {
      return this.pageCount((this.convDetail || []).length, this.convPageSize);
    },
    totalQualityPages() { return this.pageCount(this.qualityTotal, this.qualityPageSize); },
    qualityDimensionRows() { return this.qualityDimensions(this.qualityOverview.dimensions || {}); },
    qualityDetailDimensions() { return this.qualityDimensions(this.qualityDetail || {}); },
    qualityReviewDimensions() { return this.qualityDimensions(this.qualityReview.scores || {}); },
    totalAgentPages() { return this.pageCount(this.agentTotal, this.operationPageSize); },
    totalAppointmentPages() { return this.pageCount(this.appointmentTotal, this.operationPageSize); },
    totalWakeupPages() { return this.pageCount(this.wakeupTotal, this.operationPageSize); },
    // 趋势摘要（跟随当前日期范围）
    trendTotal() {
      return this.daily.reduce((sum, d) => sum + (Number(d.user_messages) || 0), 0);
    },
    trendAverage() {
      if (!this.daily.length) return '0';
      const value = this.trendTotal / this.daily.length;
      return value.toLocaleString('zh-CN', { minimumFractionDigits: value % 1 ? 1 : 0, maximumFractionDigits: 1 });
    },
    trendPeak() {
      if (!this.daily.length) return null;
      let idx = 0;
      this.daily.forEach((d, i) => {
        if ((Number(d.user_messages) || 0) > (Number(this.daily[idx].user_messages) || 0)) idx = i;
      });
      return { idx, day: this.daily[idx].day, value: Number(this.daily[idx].user_messages) || 0 };
    },
    trendPeriodChange() {
      if (this.daily.length < 14) return { text: '暂无数据', tone: 'flat' };
      const split = Math.max(1, this.daily.length - 7);
      const previous = this.daily.slice(Math.max(0, split - 7), split).reduce((sum, d) => sum + (Number(d.user_messages) || 0), 0);
      const current = this.daily.slice(split).reduce((sum, d) => sum + (Number(d.user_messages) || 0), 0);
      if (!previous) return current ? { text: '▲ +' + current, tone: 'up' } : { text: '0%', tone: 'flat' };
      const pct = Math.round((current - previous) / previous * 100);
      if (!pct) return { text: '0%', tone: 'flat' };
      return { text: `${pct > 0 ? '↑' : '↓'} ${Math.abs(pct)}%`, tone: pct > 0 ? 'up' : 'down' };
    },
    trendScale() {
      const rawMax = Math.max(...this.daily.map(d => Number(d.user_messages) || 0), 0);
      if (rawMax <= 0) return { max: 3, step: 1 };
      const exponent = Math.floor(Math.log10(rawMax));
      const magnitude = Math.pow(10, exponent);
      const normalized = rawMax / magnitude;
      const niceRange = (normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10) * magnitude;
      const roughStep = niceRange / 4;
      const stepExponent = Math.floor(Math.log10(roughStep));
      const stepMagnitude = Math.pow(10, stepExponent);
      const stepNormalized = roughStep / stepMagnitude;
      let step = (stepNormalized < 1.5 ? 1 : stepNormalized < 2.25 ? 2 : stepNormalized < 3.5 ? 2.5 : stepNormalized < 7.5 ? 5 : 10) * stepMagnitude;
      step = Math.max(1, step);
      let max = Math.ceil(rawMax / step) * step;
      if (max / step < 3) max = step * 3;
      return { max, step };
    },
    trendDateTicks() {
      const last = this.daily.length - 1;
      // 步长随天数自适应：7 天逐日、14 天隔日、30 天每 4 天一刻度，避免 X 轴拥挤
      const step = last > 24 ? 4 : last > 12 ? 2 : 1;
      return this.daily.map((d, i) => ({ i, label: this.formatTrendDay(d.day) }))
        .filter(tick => tick.i === 0 || tick.i === last || (tick.i % step === 0 && last - tick.i > 1));
    },
    // trendX/trendY 移到了 methods（带参函数在 computed 里会被 unref 导致渲染抛错）
    // trendPoints / compSegments / compTotal 已移到 computed 块（22:18 报表白屏修复）
    // ⚠️ W26-M4：trendX/trendY/segColor/compPct 是**带参数的辅助函数**，
    // 必须放 methods（带参函数在 computed 里 Vue 3 会 unref → 返回字符串/数字不是函数，
    // 模板或 computed 内调用时抛 "Cannot convert object to primitive value"）。
    // 它们在 methods 块下方，本注释仅作解释。
    // 无参数计算属性（trendPath/trendAreaPath/trendGrid 等保留在 computed）。
    // W27-趋势图美化：平滑贝塞尔曲线（Catmull-Rom → Bezier，过所有数据点）
    trendPath() {
      const pts = this.daily.map((d, i) => [this.trendX(i), this.trendY(d.user_messages)]);
      if (!pts.length) return '';
      if (pts.length === 1) return `M ${pts[0][0].toFixed(1)},${pts[0][1].toFixed(1)}`;
      let d = `M ${pts[0][0].toFixed(1)},${pts[0][1].toFixed(1)}`;
      for (let i = 0; i < pts.length - 1; i++) {
        const p0 = pts[Math.max(0, i - 1)], p1 = pts[i], p2 = pts[i + 1], p3 = pts[Math.min(pts.length - 1, i + 2)];
        const c1x = p1[0] + (p2[0] - p0[0]) / 6, c1y = Math.max(32, Math.min(164, p1[1] + (p2[1] - p0[1]) / 6));
        const c2x = p2[0] - (p3[0] - p1[0]) / 6, c2y = Math.max(32, Math.min(164, p2[1] - (p3[1] - p1[1]) / 6));
        d += ` C ${c1x.toFixed(1)},${c1y.toFixed(1)} ${c2x.toFixed(1)},${c2y.toFixed(1)} ${p2[0].toFixed(1)},${p2[1].toFixed(1)}`;
      }
      return d;
    },
    // 面积和折线共用同一条平滑边缘，避免折线与填充轮廓不一致
    trendAreaPath() {
      if (!this.daily.length) return '';
      return `${this.trendPath} L 642,164 L 58,164 Z`;
    },
    // 使用易读的整数“漂亮刻度”，并为最高点保留呼吸空间
    trendGrid() {
      const ticks = [];
      for (let value = 0; value <= this.trendScale.max + this.trendScale.step / 2; value += this.trendScale.step) {
        ticks.push({ value, y: this.trendY(value), label: this.formatCount(value) });
      }
      return ticks;
    },
    // W27-趋势图：渐变起始色（跟随深浅色主题）
    trendGradColor() { return 'var(--brand)'; },
    compSegments() {
      const comp = (this.report && this.report.composition) || [];
      const total = comp.reduce((s, c) => s + Number(c.count || 0), 0);
      if (!total) return [];
      const C = 2 * Math.PI * 50;
      let acc = 0;
      return comp.map((c, i) => {
        const pct = Number(c.count || 0) / total;
        const seg = {
          type: c.type,
          color: this.segColor(c.type),
          dash: `${(pct * C).toFixed(1)} ${(C - pct * C).toFixed(1)}`,
          rotate: Math.round(-90 + acc * 360),
        };
        acc += pct;
        return seg;
      });
    },
    compTotal() {
      return ((this.report && this.report.composition) || []).reduce((s, c) => s + Number(c.count || 0), 0);
    },
    // W27-环图：中心总数字号随位数自适应（防大数字顶住内圈挡图），基线同步微调保视觉居中
    compNumFont() {
      const n = Number(this.compTotal) || 0;
      if (n >= 100000) return 14;
      if (n >= 10000) return 17;
      if (n >= 1000) return 20;
      return 24;
    },
    compNumY() {
      return 66 + (24 - this.compNumFont) / 2;
    },
    // W28-模型管理页图表：各模型调用次数分布（从 /api/metrics 实时聚合，零后端改动）
    modelCallDist() {
      const m = this.metricsRaw || {};
      const map = {};
      Object.keys(m).forEach(k => {
        const idx = k.lastIndexOf('.');
        if (idx < 0) return;
        const suffix = k.slice(idx + 1);
        if (!['chat', 'stream', 'quality-judge', 'vision'].includes(suffix)) return;
        const model = k.slice(0, idx);
        map[model] = (map[model] || 0) + Number(m[k] || 0);
      });
      return Object.keys(map).map(name => ({ name, count: map[name] }))
        .filter(x => x.count > 0)
        .sort((a, b) => b.count - a.count);
    },
    modelCallTotal() {
      return (this.modelCallDist || []).reduce((s, c) => s + c.count, 0);
    },
    modelCallSegments() {
      const dist = this.modelCallDist || [];
      const total = dist.reduce((s, c) => s + c.count, 0);
      if (!total) return [];
      const C = 2 * Math.PI * 50;
      const palette = ['#e8609b', '#7c5cff', '#21a0c4', '#f0a020', '#4cae8a', '#e5484d', '#5b6ef5', '#f2994a'];
      let acc = 0;
      return dist.map((c, i) => {
        const pct = c.count / total;
        const seg = {
          name: c.name, count: c.count, pct,
          color: palette[i % palette.length],
          dash: `${(pct * C).toFixed(1)} ${(C - pct * C).toFixed(1)}`,
          rotate: Math.round(-90 + acc * 360),
        };
        acc += pct;
        return seg;
      });
    },
    // W28-缓存命中/未命中构成环图
    cacheSegments() {
      const hit = Number((this.cacheMetrics || {}).hit || 0);
      const lookups = Number((this.cacheMetrics || {}).lookups || 0);
      const miss = Math.max(0, lookups - hit);
      const total = hit + miss;
      if (!total) return [];
      const C = 2 * Math.PI * 50;
      const segs = [
        { name: '命中', color: '#2e9e5b', count: hit },
        { name: '未命中', color: '#c2c8d0', count: miss },
      ];
      let acc = 0;
      return segs.map(s => {
        const pct = s.count / total;
        return {
          name: s.name, count: s.count, pct,
          color: s.color,
          dash: `${(pct * C).toFixed(1)} ${(C - pct * C).toFixed(1)}`,
          rotate: Math.round(-90 + acc * 360),
        };
      });
    },
    // W28-趋势图：从 /api/metrics.timeseries 派生两张 SVG 折线图数据
    metricsTimeseries() {
      const ts = (this.metricsRaw && this.metricsRaw.timeseries) || [];
      return Array.isArray(ts) ? ts : [];
    },
    trendChart() {
      const raw = this.metricsTimeseries;
      const realN = raw.length;
      // 没有真实记录时，合成一段全 0 的时间序列，保证图表始终能画（坐标轴 + 零值平线）
      let ts = raw;
      if (realN < 2) {
        const now = Date.now();
        ts = [];
        for (let i = 5; i >= 0; i--) ts.push({ t: now - i * 60000, cacheHit: 0, cacheLookups: 0, calls: 0 });
      }
      const W = 600, H = 170, padT = 14, padB = 24;
      const n = ts.length;
      const xAt = i => 4 + i * (W - 8) / (n - 1);
      const ratePts = ts.map((r, i) => {
        const lookups = Number(r.cacheLookups || 0), hit = Number(r.cacheHit || 0);
        const v = lookups > 0 ? hit * 100 / lookups : 0;
        return { x: xAt(i), y: padT + (1 - v / 100) * (H - padT - padB), v };
      });
      const maxCalls = Math.max(1, ...ts.map(r => Number(r.calls || 0)));
      const callsPts = ts.map((r, i) => {
        const v = Number(r.calls || 0);
        return { x: xAt(i), y: padT + (1 - v / maxCalls) * (H - padT - padB), v };
      });
      const toPath = pts => pts.map((p, i) => (i === 0 ? 'M' : 'L') + p.x.toFixed(1) + ' ' + p.y.toFixed(1)).join(' ');
      const toArea = pts => toPath(pts) + ' L ' + (W - 4) + ' ' + (H - padB) + ' L 4 ' + (H - padB) + ' Z';
      const fmt = t => { const d = new Date(Number(t)); return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'); };
      const labels = [
        { x: 4, t: fmt(ts[0].t) },
        { x: W / 2, t: fmt(ts[Math.floor(n / 2)].t) },
        { x: W - 4, t: fmt(ts[n - 1].t) },
      ];
      return {
        W, H, padB,
        cacheLine: toPath(ratePts), cacheArea: toArea(ratePts),
        callsLine: toPath(callsPts), callsArea: toArea(callsPts),
        lastRate: ratePts[ratePts.length - 1].v,
        lastCalls: callsPts[callsPts.length - 1].v,
        labels,
        empty: realN < 2,
      };
    },
    debugHistoryText() {
      const turns = (this.debugMessages || []).map(m => (m.role === 'user' ? '用户：' : '助手：') + m.content).join('\n');
      return [this.debugHistoryOverride, turns].filter(Boolean).join('\n');
    },
    debugSuiteStats() {
      const rows = this.debugCases || [];
      return {
        total: rows.length,
        passed: rows.filter(c => c.passed === true).length,
        failed: rows.filter(c => c.passed === false).length,
        pending: rows.filter(c => c.passed == null).length,
      };
    },
    debugPromptLayerRows() {
      const layers = (this.debugResult && this.debugResult.promptLayers) || {};
      return [
        {key:'system', name:'System · 可信行为与安全规则', value:String(layers.system || '')},
        {key:'context', name:'Context · 业务资料与历史上下文', value:String(layers.context || '')},
        {key:'user', name:'User · 本轮客户请求', value:String(layers.user || '')},
      ];
    },
    debugDirtyCount() {
      return (this.configItems || []).filter(c => c.value !== c.original).length;
    },
    configUnsavedCount() {
      return (this.configItems || []).filter(c =>
        c.value !== (c.versionedPrompt ? c.draftValue : c.original)).length;
    },
    promptDraftCount() {
      return (this.configItems || []).filter(c => c.versionedPrompt && c.value !== c.original).length;
    },
    promptSavedDraftCount() {
      return (this.configItems || []).filter(c =>
        c.versionedPrompt && c.draftValue !== c.original).length;
    },
    promptUnsavedEditCount() {
      return (this.configItems || []).filter(c =>
        c.versionedPrompt && c.value !== c.draftValue).length;
    },
    promptPendingCount() {
      return (this.configItems || []).filter(c => c.versionedPrompt &&
        (c.value !== c.original || c.draftValue !== c.original)).length;
    },
    promptCurrentVersion() {
      return this.promptWorkspace && this.promptWorkspace.currentRelease
        ? this.promptWorkspace.currentRelease.version
        : '尚未建立版本';
    },
    promptGatePassed() {
      const task = this.promptGateTask;
      const draft = this.promptWorkspace && this.promptWorkspace.draft;
      return !!(task && draft && task.status === 'passed' && task.proofValid !== false
        && task.draftHash === draft.draftHash && !this.promptUnsavedEditCount);
    },
    promptGateStatusLabel() {
      const task = this.promptGateTask;
      if (!task || task.found === false) return '未运行';
      return ({queued:'等待运行',running:'正在检查',passed:task.proofValid === false ? '已过期' : '已通过',failed:'未通过'}[task.status]
        || task.status || '未运行');
    },
    aiConfigGroups() {
      const query = (this.configSearch || '').toLowerCase();
      const items = (this.configItems || []).filter(c => c.group !== '首页展示' && !this.hiddenConfigKeys.includes(c.key) && (!query || [c.label,c.desc,c.key,c.group].join(' ').toLowerCase().includes(query)));
      const groups = [];
      items.forEach(item => {
        let group = groups.find(g => g.name === item.group);
        if (!group) { group = { name:item.group, items:[] }; groups.push(group); }
        group.items.push(item);
      });
      return groups;
    },
    configCategories() {
      const groups = [];
      (this.configItems || []).forEach(item => {
        if (item.group === '首页展示' || this.hiddenConfigKeys.includes(item.key)) return;
        let group = groups.find(g => g.name === item.group);
        if (!group) {
          group = { name:item.group, description:this.configGroupDescription(item.group), count:0, dirty:0 };
          groups.push(group);
        }
        group.count++;
        if (item.value !== item.original) group.dirty++;
      });
      return groups;
    },
    forbiddenItem() { return (this.configItems || []).find(c => c.key === 'agent.forbiddenWords'); },
    forbiddenActionItem() { return (this.configItems || []).find(c => c.key === 'agent.forbiddenWordsAction'); },
    forbiddenWordsList() {
      const item = this.forbiddenItem;
      if (!item) return [];
      return String(item.value || '').split(/[\n,，]+/).map(s => s.trim()).filter(Boolean);
    },
    forbiddenDirty() {
      const fw = this.forbiddenItem, act = this.forbiddenActionItem;
      return !!(fw && fw.value !== fw.original) || !!(act && act.value !== act.original);
    },
    displayedAiConfigGroups() {
      if (this.configSearch) return this.aiConfigGroups;
      const selected = this.activeConfigGroup || (this.configCategories[0] && this.configCategories[0].name) || '';
      return this.aiConfigGroups.filter(group => group.name === selected);
    },
    displayedConfigCount() {
      return this.displayedAiConfigGroups.reduce((total, group) => total + group.items.length, 0);
    },
    homeConfigGroups() {
      const items = (this.configItems || []).filter(c => c.group === '首页展示' && !this.hiddenConfigKeys.includes(c.key));
      return items.length ? [{ name: '首页展示', items }] : [];
    },
    resourceAccounts() {
      const tenantId = Number(this.resourceScope.tenantId || 0);
      if (!tenantId) return [];
      return (this.resourceScopeCatalog.accounts || []).filter(account => Number(account.tenantId) === tenantId);
    },
    selectedResourceScopeLabel() {
      const tenantId = Number(this.resourceScope.tenantId || 0);
      if (!tenantId) return '全局共享';
      const tenant = (this.resourceScopeCatalog.tenants || []).find(item => Number(item.id) === tenantId);
      const accountId = Number(this.resourceScope.kfAccountId || 0);
      const account = accountId ? (this.resourceScopeCatalog.accounts || []).find(item => Number(item.id) === accountId) : null;
      return (tenant ? tenant.name : '未知企业') + (account ? ' / ' + account.name : ' / 企业内共享');
    },
    knowledgeDocuments() {
      if (Array.isArray(this.kb.documentDetails) && this.kb.documentDetails.length) return this.kb.documentDetails;
      return (this.kb.documents || []).map(d => ({ doc_name: this.docName(d), chunk_count: this.docChunks(d), doc_type: 'reference', status: 'published', version: 1 }));
    },
    // 统一模型管理：视觉/向量模型配置项（来自 AI 配置中心目录，仅超管可见）
    cfgVisionBase() { return this.configItems.find(c => c.key === 'model.vision.baseUrl') || {}; },
    cfgVisionName() { return this.configItems.find(c => c.key === 'model.vision.name') || {}; },
    cfgEmbedBase() { return this.configItems.find(c => c.key === 'model.embedding.baseUrl') || {}; },
    cfgEmbedName() { return this.configItems.find(c => c.key === 'model.embedding.name') || {}; },
    embeddingSwitchRunning() {
      return !!(this.embeddingSwitchTask && ['queued','embedding','switching'].includes(this.embeddingSwitchTask.status));
    },
    embeddingSwitchStatusText() {
      const status = this.embeddingSwitchTask && this.embeddingSwitchTask.status;
      return ({ queued:'任务已排队', embedding:'正在生成新向量', switching:'正在原子切换', done:'切换完成', failed:'切换失败' })[status] || '等待切换';
    },
    kbReindexRunning() {
      return !!(this.kbReindexTask && ['queued','embedding','switching'].includes(this.kbReindexTask.status));
    },
    kbReindexStatusText() {
      const status = this.kbReindexTask && this.kbReindexTask.status;
      return ({ queued:'任务已排队', embedding:'正在生成新向量', switching:'正在安全替换索引', done:'重建完成', failed:'重建失败' })[status] || '等待重建';
    },
    kbActiveTestHit() {
      if (!this.kbTestResult || !Array.isArray(this.kbTestResult.hits) || this.kbTestChunkId == null) return null;
      return this.kbTestResult.hits.find(hit => Number(hit.id) === Number(this.kbTestChunkId)) || null;
    },
  },
  watch: {
    imgTagFilter() { this.imgPage = 1; },
  },
  async mounted() {
    // W26-M5：恢复深色模式偏好
    if (this.isDark) document.documentElement.setAttribute('data-theme', 'dark');
    // 双月日历选择器：点击面板外部自动收起
    document.addEventListener('click', this.onDocClickReport);
    // 设计调整：dialog 焦点回退（KB / 图片 / 门店 / 模型 / 评分详情）
    this._bindDialog('kbEditDoc', () => this.closeKbEditor());
    this._bindDialog('imgEditId', () => this.cancelImgEdit());
    this._bindDialog('storeDialog', () => this.storeDialog = false);
    this._bindDialog('modelDialog', () => this.closeAiModelDialog());
    this._bindDialog('qualityDetailOpen', () => this.qualityDetailOpen = false);
    this._bindDialog('projectDialog', () => this.closeProjectEditor());
    if (!this.token) return;
    // 单点登录（P2-单点）：同一浏览器只允许一个管理后台页面生效
    const lockOk = await this.acquireAdminLock(false);
    if (!lockOk) {
      this.askToken();
      this.loginErr = '该账号已在其他地方登录';
      return;
    }
    try { await this.loadSession(); } catch (e) { return; }
    // 支持 ?tab=xxx 深链接直达（报表排障/快捷入口用）
    const requestedTab = new URLSearchParams(location.search).get('tab');
    const urlTab = requestedTab === 'conv' ? 'quality' : requestedTab;
    if (urlTab && this.visibleTabs.some(t => t.key === urlTab)) {
      this.tab = urlTab;
      this.switchTab(urlTab);
      return;
    }
    this.switchTab(this.tab);
  },
  // （22:18 修复说明：trendPoints/compSegments/compTotal 已合并进上方 computed 块，
  //  避免重复 computed 键覆盖导致 filteredImages 等失效——见上方注释）
  methods: {
    showToast(msg) {
      this.toast = msg;
      // 连续提示时先清掉上一个定时器：否则第二条会被第一条的 3.5s 定时器提前关掉，
      // 批量操作逐条报错时最坏瞬间消失，操作人员看不到失败原因。
      if (this.toastTimer) clearTimeout(this.toastTimer);
      this.toastTimer = setTimeout(() => { this.toast = ''; this.toastTimer = null; }, 3500);
    },
    pageCount(total, pageSize) {
      return Math.max(1, Math.ceil(Number(total || 0) / Math.max(1, Number(pageSize || 1))));
    },
    pageSlice(rows, page, pageSize) {
      const list = Array.isArray(rows) ? rows : [];
      const size = Math.max(1, Number(pageSize || 1));
      const start = (Math.max(1, Number(page || 1)) - 1) * size;
      return list.slice(start, start + size);
    },
    clampListPage(pageKey, total, pageSize) {
      this[pageKey] = Math.min(Math.max(1, Number(this[pageKey] || 1)), this.pageCount(total, pageSize));
    },
    // 统一「跳转到指定页码」：clamp 到 [1, 总页数]，会话列表走 loadConvs 重新拉取
    gotoPage(group, pageKey, totalKey) {
      const max = Math.max(1, Number(this[totalKey] || 1));
      let p = Math.floor(Number(this.jump[group]));
      if (!Number.isFinite(p) || p < 1) p = 1;
      if (p > max) p = max;
      this.jump[group] = p;
      if (pageKey === 'convPage') {
        this.loadConvs(p);
      } else {
        this[pageKey] = p;
      }
    },
    // 设计调整：写入新统一 key=aics_theme，保留旧 key=aics_admin_theme 兼容，避免老用户丢偏好
    toggleTheme() {
      this.isDark = !this.isDark;
      localStorage.setItem('aics_theme', this.isDark ? 'dark' : 'light');
      // 旧 key 不再写新值，但保留读取兼容
      if (this.isDark) document.documentElement.setAttribute('data-theme', 'dark');
      else document.documentElement.removeAttribute('data-theme');
    },
    // 设计调整：dialog 焦点回退 —— 关闭后把焦点还给触发按钮（修复 a11y）
    _bindDialog(key, closeFn) {
      let prevFocus = null;
      this.$watch(key, (n, o) => {
        if (n && !o) {
          prevFocus = document.activeElement;
          this.$nextTick(() => {
            const root = document.querySelector('.kb-mask:not([style*="display: none"])');
            if (!root) return;
            const focusable = root.querySelector('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])');
            if (focusable) focusable.focus();
          });
        } else if (!n && o) {
          if (prevFocus && typeof prevFocus.focus === 'function' && document.body.contains(prevFocus)) prevFocus.focus();
          prevFocus = null;
        }
      });
    },
    configGroupDescription(group) {
      return {
        '基础话术': '欢迎语、拒答和回答风格',
        '情绪与安全': '辱骂、威胁和沟通边界',
        '检索与 FAQ': '知识召回、直回规则和来源',
        '项目与意图识别': '项目别名、关键词和澄清',
        '项目首次介绍': 'AI 判断、标准话术和至少一张配图',
        'Agent 与转人工': '预约、门店、投诉和人工服务',
        '图片发送规则': '效果图、海报和重复控制',
        '首页展示': '客户首页可编辑的项目列表与快捷问题',
        '会话记忆': '上下文轮数和客户画像',
        '模型参数': '回答随机度、向量、视觉、超时与主模型熔断策略；聊天模型名单在“模型管理”维护',
      }[group] || '相关功能设置';
    },
    selectConfigGroup(group) {
      this.activeConfigGroup = group;
      this.configSearch = '';
    },
    // W26-M4：报表辅助函数（带参 → 必须在 methods，computed 里会被 unref 抛错）
    trendX(i) {
      const n = Math.max(this.daily.length - 1, 1);
      return 58 + i * (584 / n);
    },
    trendY(v) {
      return 164 - Math.round(Number(v || 0) / this.trendScale.max * 130);
    },
    formatCount(v) {
      return (Number(v) || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 });
    },
    formatTrendDay(day) {
      return AICS.formatMonthDay(day);
    },
    trendPointLabel(i) {
      const d = this.daily[i];
      if (!d) return '';
      const current = Number(d.user_messages) || 0;
      if (i < 1) return `${this.formatTrendDay(d.day)}，用户消息 ${current} 条（起始日，无环比）`;
      const previous = Number(this.daily[i - 1].user_messages) || 0;
      const diff = current - previous;
      if (diff === 0) return `${this.formatTrendDay(d.day)}，用户消息 ${current} 条，较前一日持平`;
      if (diff > 0) return `${this.formatTrendDay(d.day)}，用户消息 ${current} 条，较前一日新增 ${diff} 条`;
      return `${this.formatTrendDay(d.day)}，用户消息 ${current} 条，较前一日减少 ${Math.abs(diff)} 条`;
    },
    showTrendPoint(i) {
      const d = this.daily[i];
      if (!d) return;
      const px = this.trendX(i), py = this.trendY(d.user_messages);
      let delta = null, deltaLabel = '', deltaTone = 'flat';
      if (i === 0) {
        // 起始日没有前一天，无环比
        deltaLabel = '起始日 · 无环比'; deltaTone = 'flat';
      } else if (i > 0) {
        const previous = Number(this.daily[i - 1].user_messages) || 0;
        const current = Number(d.user_messages) || 0;
        const diff = current - previous;
        if (diff === 0) { deltaLabel = '持平'; deltaTone = 'flat'; }
        else if (diff > 0) { deltaLabel = `▲ 新增 ${diff}`; deltaTone = 'up'; }
        else { deltaLabel = `▼ 减少 ${Math.abs(diff)}`; deltaTone = 'down'; }
      }
      this.trendHover = {
        i, x: px, y: py, day: this.formatTrendDay(d.day), value: Number(d.user_messages) || 0, delta,
        deltaLabel, deltaTone,
        tipX: Math.max(66, Math.min(634, px)), tipY: Math.max(62, py - 5),
      };
    },
    // 根据鼠标横坐标吸附到最近的数据点
    onTrendMove(ev) {
      if (!this.daily.length) return;
      const svg = ev.currentTarget;
      const rect = svg.getBoundingClientRect();
      // viewBox 700x205 → 换算 SVG 坐标
      const xInSvg = (ev.clientX - rect.left) / rect.width * 700;
      const n = Math.max(this.daily.length - 1, 1);
      const step = 584 / n;
      const raw = Math.round((xInSvg - 58) / step);
      if (!isFinite(raw)) return;   // 坐标异常（如测试环境 rect 宽 0）直接忽略
      const i = Math.max(0, Math.min(this.daily.length - 1, raw));
      this.showTrendPoint(i);
    },
    onTrendLeave() { this.trendHover = null; },
    // 报表环图：9 类应答序列色，浅/深双组（图形元素按 WCAG ≥3:1 校验，非正文 4.5 线）
    // - 语义锚点不漂移：FAQ=品牌蓝、AGENT=信任青、REFUSAL=拒答红（浅组与 --brand/--trust/--danger
    //   同值，深组与 dark 块对应变量同值，日后改 token 需同步本表）
    // - 不直接复用文字级语义 token：--success #237804 / --warning #ad4e00 是给 10-12px 小字压深的
    //   4.5:1 值，画大扇区会偏暗发闷；图表独立取中间调（AntD 6-8 档浅组 / 4-5 档提亮深组）
    // - 旧板撞色已拆：AI(橙 #d46b08) 与 SAFETY(金 #ad8b00) 分出色带；CLARIFY 弃 --ink-2 灰改蓝紫，
    //   UNKNOWN 保留中性灰（比 --ink-3 更可见）维持"未知/兜底"语义
    segColor(type) {
      const p = this.isDark
        ? { FAQ:'#40a9ff', FACT:'#73d13d', AGENT:'#5cdbd3', AI:'#ffa940', REFUSAL:'#f58b8b',
            SAFETY:'#ffc53d', INTRO:'#9254de', CLARIFY:'#85a5ff', UNKNOWN:'#8c9bab' }
        : { FAQ:'#1890ff', FACT:'#389e0d', AGENT:'#067a8f', AI:'#d46b08', REFUSAL:'#cf1322',
            SAFETY:'#ad8b00', INTRO:'#722ed1', CLARIFY:'#2f54eb', UNKNOWN:'#8c8c8c' };
      return p[type] || (this.isDark ? '#8c9bab' : '#8c8c8c');
    },
    compPct(v) {
      const total = this.compTotal;
      return total ? Math.round(Number(v || 0) / total * 100) : 0;
    },
    async doLogin() {
      this.loginErr = '';
      const username = (this.loginUsername || '').trim();
      const password = this.loginPassword || '';
      if (!username || !password) { this.loginErr = '请输入管理员账号和密码'; return; }
      try {
        const r = await (await fetch('/agent/login', { method: 'POST', headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({ username, password, portal: 'admin' }) })).json();
        if (r.ok && (r.role === 'admin' || r.role === 'super_admin')) {
          this.token = 'cookie'; sessionStorage.setItem('aics_admin_session', '1');
          this.applySession(r);
          // 单点登录：新登录已把其他设备/页面的旧会话踢掉，这里抢占（必要时窃取）本浏览器锁
          await this.acquireAdminLock(true);
          await this.loadResourceScopes();
          this.loginPassword = '';
          this.switchTab(this.visibleTabs[0] && this.visibleTabs[0].key);
          this.showToast(this.roleLabel + '登录成功');
        } else if (r.ok) {
          this.loginErr = '该账号是客服坐席，没有后台管理权限';
        } else {
          this.loginErr = r.error || '账号或密码不对';
        }
      } catch (e) { this.loginErr = '登录失败，请检查网络'; }
    },
    askToken() {
      this.releaseAdminLock();
      this.token = ''; sessionStorage.removeItem('aics_admin_session');
      this.role = ''; this.permissions = []; this.currentUser = {};
      this.loginPassword = ''; this.loginErr = '登录已失效或当前账号没有管理员权限，请重新登录';
    },
    // ===== 单点登录：管理后台同浏览器多标签互斥（Web Locks API，页面关闭自动释放） =====
    async acquireAdminLock(steal) {
      if (!navigator.locks) return true;   // 老浏览器不支持时不限制多开
      const opts = steal ? { steal: true } : { ifAvailable: true };
      return new Promise(resolve => {
        navigator.locks.request('aics-admin-workbench', opts, lock => {
          if (!lock) { resolve(false); return; }
          resolve(true);
          return new Promise(release => { this._releaseAdminLock = release; });
        }).catch(() => resolve(true));   // 锁 API 异常时放行，不阻塞后台
      });
    },
    releaseAdminLock() {
      if (this._releaseAdminLock) {
        try { this._releaseAdminLock(); } catch (e) { /* 已释放 */ }
        this._releaseAdminLock = null;
      }
    },
    async resetToken() { this.releaseAdminLock(); try { await fetch('/agent/logout', {method:'POST'}); } catch(e) {} sessionStorage.removeItem('aics_admin_session'); this.token = ''; this.role = ''; this.permissions = []; this.currentUser = {}; this.loginPassword = ''; this.loginErr = ''; this.navOpen = false; },
    // 管理令牌存 HttpOnly Cookie，JavaScript 无法读取；token 字段只控制登录壳显示。
    u(url) { return url; },
    // 统一请求封装：401 重问口令；网络失败/4xx/5xx 统一 toast，避免静默失败
    async api(url, opts) {
      opts = opts || {};
      opts.headers = Object.assign({}, opts.headers || {});
      let resp;
      try {
        resp = await fetch(this.u(url), opts);
      } catch (e) {
        this.showToast('操作失败，请重试：网络异常');
        throw e;
      }
      if (resp.status === 401) { this.askToken(); throw new Error('登录已失效，请重新登录'); }
      let data = null;
      try { data = await resp.json(); } catch (e) { /* 非 JSON 响应 */ }
      if (!resp.ok) {
        const reason = (data && (data.error || data.message)) || ('HTTP ' + resp.status);
        this.showToast('操作失败，请重试：' + reason);
        throw new Error(reason);
      }
      return data;
    },
    docName(d) { return d.replace(/（\d+ 片）/, ''); },
    docChunks(d) { const m = d.match(/（(\d+) 片）/); return m ? m[1] : '-'; },

    switchTab(key) {
      if (key === 'conv') key = 'quality';
      const target = this.visibleTabs.some(t => t.key === key) ? key : (this.visibleTabs[0] && this.visibleTabs[0].key);
      this.tab = target || '';
      this.navOpen = false;
      key = this.tab;
      if (!key) return;
      if (key === 'kb') this.loadKb();
      if (key === 'facts') this.loadFacts();
      if (key === 'projects') this.loadProjectWorkspace();
      if (key === 'img') { this.loadProjectOptions(); this.loadImages(); }
      if (key === 'store') { this.loadRootProjectOptions(); this.loadStores(); }
      if (key === 'report') this.loadReport();
      if (key === 'quality') {
        if (this.canManageQuality) this.loadQuality();
        if (this.canViewConversations) this.loadConvs();
      }
      if (key === 'aidev') { if (this.debugSection === 'config') this.debugSection = 'playground'; this.loadConfigs(); }
      if (key === 'aiconfig') this.loadConfigs();
      if (key === 'homecfg') this.loadConfigs();
      if (key === 'forbidden') this.loadConfigs();
      if (key === 'audit') this.loadAudit();
      if (key === 'wecom') this.loadWeComTenants();
      if (key === 'models') this.loadAiModels();
      if (key === 'modelmon') this.loadCacheMetrics();
      if (key === 'settings') this.loadSettings();
      if (key === 'admins') this.loadAdminAccounts();
      if (key === 'agents') this.loadAgents(1);
      if (key === 'operations') this.loadOperations();
    },
    toggleGroup(name) {
      this.collapsedGroups = { ...this.collapsedGroups, [name]: !this.collapsedGroups[name] };
    },
    applySession(session) {
      this.role = session.role || '';
      this.permissions = Array.isArray(session.permissions) ? session.permissions : [];
      this.currentUser = { id: session.id || session.agentId, username: session.username || '', name: session.name || '' };
    },
    async loadSession() {
      const session = await this.api('/admin/session');
      this.applySession(session);
      if (this.role !== 'admin' && this.role !== 'super_admin') {
        this.askToken();
        throw new Error('当前账号没有后台权限');
      }
      await this.loadResourceScopes();
    },
    async loadResourceScopes() {
      const scopes = await this.api('/api/resource-scopes');
      this.resourceScopeCatalog = { tenants: scopes.tenants || [], accounts: scopes.accounts || [] };
      const allowedTenants = new Set(this.resourceScopeCatalog.tenants.map(item => String(item.id)));
      if (this.resourceScope.tenantId && !allowedTenants.has(String(this.resourceScope.tenantId))) {
        this.resourceScope = { tenantId:'', kfAccountId:'' };
      }
      this.onResourceTenantChange(true);
    },
    onResourceTenantChange(preserveAccount) {
      const allowed = new Set(this.resourceAccounts.map(item => String(item.id)));
      if (!preserveAccount || !allowed.has(String(this.resourceScope.kfAccountId || ''))) {
        this.resourceScope.kfAccountId = '';
      }
    },
    resourceScopePayload() {
      return {
        tenantId: this.resourceScope.tenantId ? Number(this.resourceScope.tenantId) : null,
        kfAccountId: this.resourceScope.kfAccountId ? Number(this.resourceScope.kfAccountId) : null,
      };
    },
    resourceScopeLabel(row) {
      const tenantId = Number((row && (row.tenant_id ?? row.tenantId)) || 0);
      if (!tenantId) return '全局共享';
      const tenant = (this.resourceScopeCatalog.tenants || []).find(item => Number(item.id) === tenantId);
      const accountId = Number((row && (row.kf_account_id ?? row.kfAccountId)) || 0);
      const account = accountId ? (this.resourceScopeCatalog.accounts || []).find(item => Number(item.id) === accountId) : null;
      return (tenant ? tenant.name : '企业 #' + tenantId) + (account ? ' / ' + account.name : ' / 企业共享');
    },
    async loadAdminAccounts() {
      const [catalog, accounts, scopes] = await Promise.all([
        this.api('/admin/permissions'), this.api('/admin/accounts'), this.api('/admin/data-scopes'),
      ]);
      this.permissionDefinitions = catalog.permissions || [];
      this.adminScopeCatalog = { tenants: scopes.tenants || [], accounts: scopes.accounts || [] };
      this.adminAccounts = (accounts.accounts || []).map(account => ({
        ...account,
        enabled: account.enabled !== false,
        permissions: Array.isArray(account.permissions) ? [...account.permissions] : [],
        tenantIds: Array.isArray(account.tenantIds) ? account.tenantIds.map(Number) : [],
        kfAccountIds: Array.isArray(account.kfAccountIds) ? account.kfAccountIds.map(Number) : [],
        editUsername: account.username || '',
        editName: account.name || '',
        newPassword: '',
        confirmPassword: '',
      }));
      this.clampListPage('adminPage', this.adminAccounts.length, this.tallListPageSize);
    },
    async createAdminAccount() {
      const account = this.newAdmin;
      this.pruneAdminKfScope(account);
      if (!account.username || !account.password) return this.showToast('请填写登录账号和初始密码');
      if (!this.validatePasswordPair(account.password, account.confirmPassword)) return;
      const currentPassword = await this.askSensitiveConfirm(`确认创建管理员 @${account.username}，分配 ${account.permissions.length} 项功能权限和 ${account.tenantIds.length} 个企业范围？`, '创建管理员账号？');
      if (!currentPassword) return;
      await this.api('/admin/accounts', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ ...account, currentPassword }),
      });
      this.newAdmin = { username: '', name: '', password: '', confirmPassword: '', permissions: [], tenantIds:[], kfAccountIds:[] };
      this.adminDialog = false;
      this.showToast('管理员账号已创建');
      this.loadAdminAccounts();
    },
    async openAdminEditDialog(account) {
      await this.loadAdminAccounts();
      this.editAdminDialog = { open: true, accountId: account.id };
    },
    async saveAdminProfile(account) {
      const username = (account.editUsername || '').trim();
      const name = (account.editName || '').trim();
      if (!username) return this.showToast('登录账号不能为空');
      if (username === account.username && name === (account.name || '')) return this.showToast('账号资料没有变化');
      const currentPassword = await this.askSensitiveConfirm(`确认把账号 @${account.username} 的登录账号改为 @${username}，显示名称改为“${name || username}”？`, '修改账号资料？');
      if (!currentPassword) return;
      await this.api('/admin/accounts/' + account.id + '/profile', {
        method: 'PUT', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ username, name, currentPassword }),
      });
      if (String(account.id) === String(this.currentUser.id)) await this.loadSession();
      this.showToast('账号资料已更新');
      this.loadAdminAccounts();
    },
    async saveAdminPermissions(account) {
      const currentPassword = await this.askSensitiveConfirm(`确认保存 @${account.username} 的 ${account.permissions.length} 项后台权限？`, '保存管理员权限？');
      if (!currentPassword) return;
      await this.api('/admin/accounts/' + account.id + '/permissions', {
        method: 'PUT', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ permissions: account.permissions || [], currentPassword }),
      });
      this.showToast('权限已保存并立即生效');
      this.loadAdminAccounts();
    },
    scopeAccountsFor(tenantIds) {
      const selected = new Set((tenantIds || []).map(Number));
      return (this.adminScopeCatalog.accounts || []).filter(account => selected.has(Number(account.tenantId)));
    },
    pruneAdminKfScope(account) {
      const allowed = new Set(this.scopeAccountsFor(account.tenantIds).map(item => Number(item.id)));
      account.kfAccountIds = (account.kfAccountIds || []).map(Number).filter(id => allowed.has(id));
    },
    async saveAdminDataScope(account) {
      this.pruneAdminKfScope(account);
      const currentPassword = await this.askSensitiveConfirm(`确认保存 @${account.username} 的数据范围：${account.tenantIds.length} 个企业、${account.kfAccountIds.length} 个限定客服号？`, '保存管理员数据范围？');
      if (!currentPassword) return;
      await this.api('/admin/accounts/' + account.id + '/data-scope', {
        method:'PUT', headers:{'Content-Type':'application/json'},
        body:JSON.stringify({ tenantIds:account.tenantIds, kfAccountIds:account.kfAccountIds, currentPassword }),
      });
      this.showToast('管理员数据范围已保存并立即生效');
      this.loadAdminAccounts();
    },
    async resetAdminAccountPassword(account) {
      if (!this.validatePasswordPair(account.newPassword, account.confirmPassword)) return;
      const currentPassword = await this.askSensitiveConfirm(`确认修改管理员 @${account.username} 的登录密码？修改成功后该账号需要重新登录。`, '修改管理员密码？');
      if (!currentPassword) return;
      await this.api('/admin/accounts/' + account.id + '/password', {
        method: 'PUT', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ password: account.newPassword, confirmPassword: account.confirmPassword, currentPassword }),
      });
      account.newPassword = '';
      account.confirmPassword = '';
      this.showToast('管理员密码已修改');
      if (String(account.id) === String(this.currentUser.id)) this.askToken();
    },
    async toggleAdminAccount(account) {
      const enabled = !account.enabled;
      const action = enabled ? '启用' : '停用';
      const currentPassword = await this.askSensitiveConfirm(`${action}管理员 @${account.username}？`, `${action}管理员账号？`);
      if (!currentPassword) return;
      await this.api('/admin/accounts/' + account.id + '/enabled', {
        method: 'PUT', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ enabled, currentPassword }),
      });
      this.showToast(`管理员账号已${action}`);
      this.loadAdminAccounts();
    },
    validatePasswordPair(password, confirmation) {
      if (!password || !confirmation) {
        this.showToast('请输入密码并再次确认');
        return false;
      }
      if (password.length < 8) {
        this.showToast('密码至少 8 位');
        return false;
      }
      if (password !== confirmation) {
        this.showToast('两次输入的密码不一致');
        return false;
      }
      return true;
    },
    async loadAgents(page) {
      const target = Math.max(1, Number(page || 1));
      const data = await this.api(`/agents?page=${target}&pageSize=${this.operationPageSize}`);
      this.agents = Array.isArray(data.rows) ? data.rows : [];
      this.agentTotal = Number(data.total || 0);
      this.agentPage = Math.min(target, this.totalAgentPages);
    },
    agentScopeAccountsFor(tenantIds) {
      const selected = new Set((tenantIds || []).map(Number));
      return (this.resourceScopeCatalog.accounts || []).filter(account => selected.has(Number(account.tenantId)));
    },
    pruneAgentKfScope(account) {
      const allowed = new Set(this.agentScopeAccountsFor(account.tenantIds).map(item => Number(item.id)));
      account.kfAccountIds = (account.kfAccountIds || []).map(Number).filter(id => allowed.has(id));
    },
    agentScopeLabel(agent) {
      const tenantIds = (agent.tenantIds || []).map(Number);
      if (!tenantIds.length) return '仅网页全局会话';
      const tenantNames = (this.resourceScopeCatalog.tenants || [])
        .filter(item => tenantIds.includes(Number(item.id))).map(item => item.name);
      const kfIds = (agent.kfAccountIds || []).map(Number);
      if (!kfIds.length) return `${tenantNames.join('、') || tenantIds.join('、')}（全部客服号）`;
      const kfNames = (this.resourceScopeCatalog.accounts || [])
        .filter(item => kfIds.includes(Number(item.id))).map(item => item.name);
      return `${tenantNames.join('、') || tenantIds.join('、')} · ${kfNames.join('、') || kfIds.join('、')}`;
    },
    async createAgentAccount() {
      if (this.agentSubmitting) return;
      const account = this.newAgent;
      if (!account.username || !account.name) return this.showToast('请填写客服登录账号和显示名称');
      if (!this.validatePasswordPair(account.password, account.confirmPassword)) return;
      const currentPassword = await this.askSensitiveConfirm(`确认创建客服账号 @${account.username}？`, '创建客服账号？');
      if (!currentPassword) return;
      this.agentSubmitting = true;
      try {
        await this.api('/agents', { method:'POST', headers:{'Content-Type':'application/json'},
          body:JSON.stringify({ ...account, currentPassword }) });
        this.newAgent = { username:'', name:'', password:'', confirmPassword:'', tenantIds:[], kfAccountIds:[] };
        this.showToast('客服账号已创建');
        await this.loadAgents(1);
      } finally {
        this.agentSubmitting = false;
      }
    },
    openAgentPassword(agent) {
      this.agentPasswordTarget = agent;
      this.agentPassword = { password:'', confirmPassword:'' };
    },
    closeAgentPassword() {
      this.agentPasswordTarget = null;
      this.agentPassword = { password:'', confirmPassword:'' };
    },
    openAgentScope(agent) {
      this.agentScopeTarget = {
        ...agent,
        tenantIds:(agent.tenantIds || []).map(Number),
        kfAccountIds:(agent.kfAccountIds || []).map(Number),
      };
      this.pruneAgentKfScope(this.agentScopeTarget);
    },
    closeAgentScope() { this.agentScopeTarget = null; },
    async saveAgentScope() {
      if (!this.agentScopeTarget) return;
      const account = this.agentScopeTarget;
      const currentPassword = await this.askSensitiveConfirm(`确认保存 @${account.username} 的服务范围？`, '保存客服服务范围？');
      if (!currentPassword) return;
      await this.api(`/agents/${account.id}/data-scope`, { method:'PUT', headers:{'Content-Type':'application/json'},
        body:JSON.stringify({ tenantIds:account.tenantIds, kfAccountIds:account.kfAccountIds, currentPassword }) });
      this.closeAgentScope();
      this.showToast('客服服务范围已保存');
      await this.loadAgents(this.agentPage);
    },
    async resetAgentPassword() {
      if (!this.agentPasswordTarget || !this.validatePasswordPair(this.agentPassword.password, this.agentPassword.confirmPassword)) return;
      const currentPassword = await this.askSensitiveConfirm(`确认重置客服 @${this.agentPasswordTarget.username} 的密码？该账号现有登录会立即失效。`, '重置客服密码？');
      if (!currentPassword) return;
      await this.api(`/agents/${this.agentPasswordTarget.id}/password`, { method:'PUT', headers:{'Content-Type':'application/json'},
        body:JSON.stringify({ ...this.agentPassword, currentPassword }) });
      this.closeAgentPassword();
      this.showToast('客服密码已重置');
    },
    async loadOperations() {
      await Promise.all([this.loadAppointments(1), this.loadWakeups(1)]);
    },
    async loadAppointments(page) {
      const target = Math.max(1, Number(page || 1));
      const status = this.appointmentStatus ? `&status=${encodeURIComponent(this.appointmentStatus)}` : '';
      const data = await this.api(`/appointments?page=${target}&pageSize=${this.operationPageSize}${status}`);
      this.appointments = Array.isArray(data.rows) ? data.rows : [];
      this.appointmentTotal = Number(data.total || 0); this.appointmentPage = Math.min(target, this.totalAppointmentPages);
    },
    async loadWakeups(page) {
      const target = Math.max(1, Number(page || 1));
      const status = this.wakeupStatus ? `&status=${encodeURIComponent(this.wakeupStatus)}` : '';
      const data = await this.api(`/wakeup?page=${target}&pageSize=${this.operationPageSize}${status}`);
      this.wakeups = Array.isArray(data.rows) ? data.rows : [];
      this.wakeupTotal = Number(data.total || 0); this.wakeupPage = Math.min(target, this.totalWakeupPages);
    },
    // ===== P2-11 批量唤醒 =====
    async loadWakeupCandidates() {
      const project = (this.batchWakeup.project || '').trim();
      if (!project) { this.showToast('请先填写项目名'); return; }
      this.batchWakeupLoading = true;
      this.batchWakeupRows = []; this.batchWakeupSelected = []; this.batchWakeupSearched = true;
      try {
        const r = await this.api('/wakeup/candidates?project=' + encodeURIComponent(project)
          + '&inactiveDays=' + (this.batchWakeup.inactiveDays || 3));
        this.batchWakeupRows = Array.isArray(r.rows) ? r.rows : [];
        this.showToast(this.batchWakeupRows.length
          ? '圈中 ' + this.batchWakeupRows.length + ' 位客户，请勾选后生成任务'
          : '没有符合条件的客户');
      } catch (e) {
        this.showToast('圈人失败：' + (e && e.message ? e.message : e));
      } finally {
        this.batchWakeupLoading = false;
      }
    },
    toggleAllWakeupCandidates(e) {
      this.batchWakeupSelected = e.target.checked ? this.batchWakeupRows.map(c => c.user_id) : [];
    },
    async createBatchWakeup() {
      if (!this.batchWakeupSelected.length || this.batchWakeupSubmitting) return;
      if (!await this.askConfirm('将为选中的 ' + this.batchWakeupSelected.length + ' 位客户生成回访任务（同一客户 '
        + this.wakeupCooldownDays + ' 天内不会重复生成）。确认？', '批量生成唤醒任务')) return;
      this.batchWakeupSubmitting = true;
      try {
        const r = await this.api('/wakeup/batch', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userIds: this.batchWakeupSelected, script: this.batchWakeup.script }),
        });
        this.showToast('已生成 ' + (r.created || 0) + ' 个任务，跳过 ' + (r.skipped || 0)
          + ' 位（冷却期内/已有待办）');
        this.batchWakeupSelected = [];
        this.batchWakeupRows = [];
        this.batchWakeupSearched = false;
        this.loadWakeups(1);
      } catch (e) {
        this.showToast('批量生成失败：' + (e && e.message ? e.message : e));
      } finally {
        this.batchWakeupSubmitting = false;
      }
    },
    async updateOperation(endpoint, id, status, type) {
      if (!await this.askConfirm(`确认将这条记录标记为“${this.operationStatusName(status)}”？`, '更新处理状态？')) return;
      await this.api(`/${endpoint}/${id}`, { method:'PUT', headers:{'Content-Type':'application/json'}, body:JSON.stringify({status}) });
      this.showToast('处理状态已更新');
      if (type === 'appointment') await this.loadAppointments(this.appointmentPage);
      if (type === 'wakeup') await this.loadWakeups(this.wakeupPage);
    },
    operationStatusName(status) {
      return { new:'待处理', contacted:'已联系', delivered:'已送达', closed:'已完成', cancelled:'已取消' }[status] || status || '—';
    },
    formatDateTime(value) { return AICS.formatDateTime(value) || '—'; },
    // Vue3 模板里不直接引用全局 AICS（模板作用域不认 window 全局，会崩）；统一走 methods
    formatShortDateTime(value) { return AICS.formatShortDateTime(value) || ''; },
    askConfirm(message, title) {
      return new Promise(resolve => {
        this.confirmResolver = resolve;
        this.confirmDialog = { title: title || '确认操作', message, requirePassword: false, currentPassword: '' };
      });
    },
    askSensitiveConfirm(message, title) {
      return new Promise(resolve => {
        this.confirmResolver = resolve;
        this.confirmDialog = { title: title || '确认敏感操作', message, requirePassword: true, currentPassword: '' };
      });
    },
    resolveConfirm(ok) {
      if (ok && this.confirmDialog && this.confirmDialog.requirePassword && !this.confirmDialog.currentPassword) {
        this.showToast('请输入当前超级管理员密码完成二次验证');
        return;
      }
      const resolve = this.confirmResolver;
      const result = ok && this.confirmDialog && this.confirmDialog.requirePassword
        ? this.confirmDialog.currentPassword : ok;
      this.confirmDialog = null;
      this.confirmResolver = null;
      if (resolve) resolve(result);
    },
    // W26-M6：操作日志
    async loadAudit(page) {
      const target = Math.max(1, Number(page || 1));
      const params = new URLSearchParams({ page: target, pageSize: this.auditPageSize });
      Object.entries(this.auditFilter || {}).forEach(([key, value]) => {
        if (value !== '' && value != null) params.set(key, value);
      });
      const result = await this.api('/api/audit-logs?' + params.toString());
      this.audits = Array.isArray(result.rows) ? result.rows : [];
      this.auditTotal = Number(result.total || 0);
      this.auditPage = Math.min(target, this.totalAuditServerPages);
    },
    resetAuditFilter() {
      this.auditFilter = { action: '', operator: '', from: '', to: '' };
      this.loadAudit(1);
    },
    auditActionName(a) {
      return {
        DELETE_IMAGE: '删除图片', UPDATE_IMAGE: '编辑图片', UPLOAD_IMAGE: '上传图片',
        CREATE_STORE: '新增门店', UPDATE_STORE: '编辑门店', DELETE_STORE: '删除门店',
        UPLOAD_DOC: '上传文档', UPLOAD_DOC_ASYNC: '上传文档', DELETE_DOC: '删除文档',
        UPDATE_CHUNK: '编辑切片', ADD_CHUNK: '新增切片', DELETE_CHUNK: '删除切片',
        CREATE_AGENT: '新增坐席', RESET_PASSWORD: '重置密码', CHANGE_PASSWORD: '修改密码',
        CREATE_ADMIN: '新增管理员', UPDATE_ADMIN_PROFILE: '修改管理员资料', RESET_ADMIN_PASSWORD: '修改管理员密码',
        UPDATE_ADMIN_PERMISSIONS: '分配管理员权限', ENABLE_ADMIN: '启用管理员', DISABLE_ADMIN: '停用管理员',
        UPDATE_SECRET: '更新密钥', CLEAR_SECRET: '清除密钥', UPDATE_CONFIG: '更新配置',
        CREATE_AI_MODEL: '新增 AI 模型', UPDATE_AI_MODEL: '修改 AI 模型', DELETE_AI_MODEL: '删除 AI 模型',
        ASSIGN_AI_MODEL_ROLE: '切换模型主备角色', ENABLE_AI_MODEL: '启用 AI 模型', DISABLE_AI_MODEL: '停用 AI 模型',
        SCORE_CONVERSATION: '会话评分', REVIEW_AI_SCORE: '人工复核评分',
        WAKEUP_BATCH_CREATE: '批量创建唤醒任务',
        CREATE_REGRESSION_CASE: '新增回归用例', UPDATE_REGRESSION_CASE: '编辑回归用例', DELETE_REGRESSION_CASE: '删除回归用例',
      }[a] || a;
    },
    auditChipClass(a) {
      return String(a).startsWith('DELETE') ? 'chip-new' : 'chip-done';
    },

    // ===== 多企业、多微信客服号 =====
    openWeComTenantDialog() {
      this.newTenant = { code:'', name:'', corpId:'', secret:'', callbackToken:'', aesKey:'', enabled:true };
      this.newTenantStep = 1;
      this.wecomTenantDialog = true;
    },
    newTenantCallbackPreview() {
      const code = String(this.newTenant.code || '').trim().toLowerCase() || '{企业编码}';
      return `${window.location.origin}/kf/callback/${code}`;
    },
    validateNewTenantStep(step, moveToError = false) {
      const item = this.newTenant;
      let message = '';
      if (step === 1) {
        if (!/^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$/.test(String(item.code || '').trim())) message = '企业编码须为 3-50 位小写字母、数字或短横线，且首尾不能是短横线';
        else if (!String(item.name || '').trim()) message = '请填写企业名称';
        else if (!String(item.corpId || '').trim()) message = '请填写企业 ID（CorpID）';
      } else if (step === 2 && !String(item.secret || '').trim()) {
        message = '请填写已获得微信客服接口权限的自建应用 Secret';
      } else if (step === 3) {
        if (!String(item.callbackToken || '').trim()) message = '请填写企微接收消息配置中的回调 Token';
        else if (!/^[A-Za-z0-9_-]{43}$/.test(String(item.aesKey || '').trim())) message = 'EncodingAESKey 必须是 43 位字母、数字、下划线或短横线';
      }
      if (!message) return true;
      if (moveToError) this.newTenantStep = step;
      this.showToast(message);
      return false;
    },
    nextWeComTenantStep() {
      if (this.validateNewTenantStep(this.newTenantStep)) this.newTenantStep++;
    },
    wecomDiagnosticCheck(tenant, key) {
      return ((tenant.diagnostics && tenant.diagnostics.checks) || []).find(item => item.key === key);
    },
    wecomAccessSteps(tenant) {
      const credentialsReady = tenant.enabled !== false && !!tenant.corpId
        && tenant.secretConfigured && tenant.callbackTokenConfigured && tenant.aesKeyConfigured;
      const apiCheck = this.wecomDiagnosticCheck(tenant, 'apiPermission');
      const accountCheck = this.wecomDiagnosticCheck(tenant, 'accountMatch');
      const enabledAccounts = (tenant.accounts || []).filter(account => account.enabled !== false).length;
      const connected = !!tenant.diagnostics && tenant.diagnostics.overall === 'connected';
      const steps = [
        { number:'①', label:'凭证配置', done:!!credentialsReady, detail:credentialsReady ? '已保存并启用' : '请补全并启用企业' },
        { number:'②', label:'接口权限', done:!!(apiCheck && apiCheck.passed), detail:!apiCheck ? '等待一键检查' : apiCheck.passed ? '读取客服号成功' : '权限未通过' },
        { number:'③', label:'回调验证', done:connected, detail:connected ? '已收到有效回调' : '等待测试消息' },
        { number:'④', label:'客服号启用', done:!!(accountCheck && accountCheck.passed), detail:enabledAccounts ? `已启用 ${enabledAccounts} 个` : '尚无已启用账号' },
      ];
      const current = steps.findIndex(step => !step.done);
      return steps.map((step, index) => ({...step, current:index === current}));
    },
    formatWeComTime(value) {
      if (!value) return '暂无';
      const date = new Date(value);
      if (Number.isNaN(date.getTime())) return '暂无';
      return date.toLocaleString('zh-CN', {hour12:false});
    },
    decorateWeComTenant(tenant) {
      const decorateAccount = account => ({
        ...account,
        draft: {
          openKfid: account.openKfid || '', name: account.name || '',
          businessScope: account.businessScope || '', welcomeMessage: account.welcomeMessage || '',
          enabled: account.enabled !== false,
        },
      });
      return {
        ...tenant,
        accounts: (tenant.accounts || []).map(decorateAccount),
        draft: {
          code: tenant.code || '', name: tenant.name || '', corpId: tenant.corpId || '',
          secret: '', callbackToken: '', aesKey: '', enabled: tenant.enabled !== false,
        },
        newAccount: { openKfid:'', name:'', businessScope:'', welcomeMessage:'', enabled:true },
        diagnostics: null, checking: false, syncing: false,
      };
    },
    async loadWeComTenants() {
      const data = await this.api('/api/wecom/tenants');
      this.wecomTenants = (data.tenants || []).map(this.decorateWeComTenant);
    },
    async createWeComTenant() {
      const item = this.newTenant;
      for (const step of [1, 2, 3]) if (!this.validateNewTenantStep(step, true)) return;
      if (!await this.askConfirm(`确认接入企业“${item.name}”（${item.code}）？`, '创建企业微信接入？')) return;
      this.wecomSaving = true;
      try {
        await this.api('/api/wecom/tenants', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(item) });
        this.newTenant = { code:'', name:'', corpId:'', secret:'', callbackToken:'', aesKey:'', enabled:true };
        this.newTenantStep = 1;
        this.wecomTenantDialog = false;
        this.showToast('企业微信接入已创建');
        await this.loadWeComTenants();
      } finally { this.wecomSaving = false; }
    },
    async saveWeComTenant(tenant) {
      if (!await this.askConfirm(`确认保存“${tenant.name}”的企业配置？填写的密钥将覆盖原值，留空则保持不变。`, '保存企业配置？')) return;
      this.wecomSaving = true;
      try {
        await this.api('/api/wecom/tenants/' + tenant.id, { method:'PUT', headers:{'Content-Type':'application/json'}, body:JSON.stringify(tenant.draft) });
        this.showToast('企业配置已保存并立即生效');
        await this.loadWeComTenants();
      } finally { this.wecomSaving = false; }
    },
    async deleteWeComTenant(tenant) {
      if (!await this.askConfirm(`删除“${tenant.name}”会同时删除其客服号配置。已有会话会保留，但不再能通过该企业发送消息。`, '确认删除企业微信接入？')) return;
      await this.api('/api/wecom/tenants/' + tenant.id, { method:'DELETE' });
      this.showToast('企业微信接入及客服号配置已删除');
      await this.loadWeComTenants();
    },
    async createWeComAccount(tenant) {
      const account = tenant.newAccount;
      if (!account.openKfid || !account.name) return this.showToast('请填写 open_kfid 和客服号名称');
      if (!await this.askConfirm(`确认在“${tenant.name}”下添加客服号“${account.name}”？`, '添加微信客服号？')) return;
      await this.api(`/api/wecom/tenants/${tenant.id}/accounts`, { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(account) });
      this.showToast('微信客服号已添加');
      await this.loadWeComTenants();
    },
    async saveWeComAccount(tenant, account) {
      if (!await this.askConfirm(`确认保存客服号“${account.name}”的业务范围、欢迎语和启用状态？`, '保存微信客服号？')) return;
      await this.api(`/api/wecom/tenants/${tenant.id}/accounts/${account.id}`, { method:'PUT', headers:{'Content-Type':'application/json'}, body:JSON.stringify(account.draft) });
      this.showToast('微信客服号已保存');
      await this.loadWeComTenants();
    },
    async deleteWeComAccount(tenant, account) {
      if (!await this.askConfirm(`确认删除客服号“${account.name}”（${account.openKfid}）？`, '删除微信客服号？')) return;
      await this.api(`/api/wecom/tenants/${tenant.id}/accounts/${account.id}`, { method:'DELETE' });
      this.showToast('微信客服号已删除');
      await this.loadWeComTenants();
    },
    async syncWeComAccounts(tenant) {
      tenant.syncing = true;
      try {
        const result = await this.api(`/api/wecom/tenants/${tenant.id}/accounts/sync`, {method:'POST'});
        this.showToast(result.result || '企微客服号同步完成');
        await this.loadWeComTenants();
      } finally { tenant.syncing = false; }
    },
    async diagnoseTenant(tenant) {
      tenant.checking = true;
      try {
        tenant.diagnostics = await this.api(`/api/wecom/tenants/${tenant.id}/diagnostics`);
        this.showToast(tenant.diagnostics.statusLabel === '已连接'
          ? '接入检查完成：该企业已收到有效回调'
          : tenant.diagnostics.statusLabel === '配置已通过'
            ? '配置与接口权限已通过，请继续验证消息回调'
            : '检查完成，请处理未通过项');
      } finally { tenant.checking = false; }
    },
    async copyTenantCallback(tenant) {
      if (!tenant.callbackUrl) return;
      try { await navigator.clipboard.writeText(tenant.callbackUrl); this.showToast('该企业回调地址已复制'); }
      catch (e) { this.showToast('复制失败，请手动选择地址'); }
    },

    // ===== 动态聊天模型与主备切换 =====
    aiModelRoleLabel(role) {
      return { primary:'主模型', fallback:'备用模型', candidate:'普通候选', eval:'评测专用' }[role] || role;
    },
    async loadAiModels() {
      this.modelLoading = true;
      try {
        const data = await this.api('/api/ai-models');
        this.aiModels = Array.isArray(data.models) ? data.models : [];
        this.modelSummary = { primary:data.primary || {}, fallback:data.fallback || {} };
        this.modelPage = Math.min(Math.max(1, this.modelPage), this.totalAiModelPages);
      } finally { this.modelLoading = false; }
      await this.loadCacheMetrics();
      // 统一模型管理：进入模型管理页时一并加载视觉/向量配置与通义密钥
      try { await this.loadConfigs(); } catch (e) { /* 配置中心不可用时不影响模型列表 */ }
      try { await this.loadSettings(); } catch (e) { /* 密钥清单不可用时不影响模型列表 */ }
    },
    async loadCacheMetrics() {
      this.cacheLoading = true;
      try {
        const m = await this.api('/api/metrics');
        const hit = Number(m['response_cache.hit'] || 0);
        const lookups = Number(m['response_cache.lookups'] || 0);
        const rate = Number(m['response_cache.hit_rate'] != null ? m['response_cache.hit_rate'] : (lookups > 0 ? (hit * 100 / lookups) : 0));
        this.cacheMetrics = { hit, lookups, rate };
        this.metricsRaw = m;
        this.gwHealth = {
          circuitOpen: !!m['primary_circuit_open'],
          consecutiveFailures: Number(m['primary_consecutive_failures'] || 0),
          failoverCount: Number(m['failover_count'] || 0),
          bypassCount: Number(m['circuit_bypass_count'] || 0),
          lastFailoverAt: m['last_failover_at'] || '',
        };
      } catch (e) {
        this.cacheMetrics = { hit: 0, lookups: 0, rate: 0 };
        this.gwHealth = { circuitOpen:false, consecutiveFailures:0, failoverCount:0, bypassCount:0, lastFailoverAt:'' };
      } finally { this.cacheLoading = false; }
    },
    openAiModelDialog(model) {
      this.modelKeyRevealed = false;
      this.modelDraft = model ? {
        id:model.id, displayName:model.displayName || '', baseUrl:model.baseUrl || '',
        modelName:model.modelName || '', apiKey:'', role:model.role || 'candidate', enabled:model.enabled !== false,
      } : { id:null, displayName:'', baseUrl:'', modelName:'', apiKey:'', role:'candidate', enabled:true };
      this.modelDialog = true;
    },
    closeAiModelDialog() {
      if (this.modelSaving) return;
      this.modelDialog = false;
      this.modelKeyRevealed = false;
      this.modelDraft = { id:null, displayName:'', baseUrl:'', modelName:'', apiKey:'', role:'candidate', enabled:true };
    },
    async saveAiModel() {
      const item = this.modelDraft;
      if (!String(item.displayName || '').trim() || !String(item.baseUrl || '').trim() || !String(item.modelName || '').trim()) {
        return this.showToast('请完整填写显示名称、API Base URL 和模型标识');
      }
      if (!item.id && !String(item.apiKey || '').trim()) return this.showToast('新增模型必须填写 API Key');
      if (!item.id && item.enabled === false && item.role !== 'candidate') return this.showToast('主模型或备用模型必须启用');
      const roleHint = !item.id && item.role !== 'candidate'
        ? `保存后会立即成为${this.aiModelRoleLabel(item.role)}，当前同角色模型将转为普通候选。` : '保存后将在下一次模型调用时热更新。';
      if (!await this.askConfirm(`${roleHint} API Key 将加密保存且不再回显。`, item.id ? '保存模型配置？' : '添加新模型？')) return;
      this.modelSaving = true;
      try {
        await this.api(item.id ? '/api/ai-models/' + item.id : '/api/ai-models', {
          method:item.id ? 'PUT' : 'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(item),
        });
        if (item.id) delete this.modelTestResults[item.id];
        this.modelDialog = false;
        this.showToast(item.id ? '模型配置已保存并立即生效' : '新模型已添加');
        await Promise.all([this.loadAiModels(), this.qualityJudgeProvider !== 'rules' ? this.loadQualityJudgeModels() : Promise.resolve()]);
      } finally { this.modelSaving = false; }
    },
    async assignAiModelRole(model, role) {
      const swapping = model.role && model.role !== 'candidate' && model.role !== role;
      const message = swapping
        ? `“${model.displayName}”当前是${this.aiModelRoleLabel(model.role)}。确认后，当前主模型与备用模型会自动互换。`
        : `确认把“${model.displayName}”设为${this.aiModelRoleLabel(role)}？当前${this.aiModelRoleLabel(role)}会转为普通候选。`;
      if (!await this.askConfirm(message, '切换模型角色？')) return;
      await this.api(`/api/ai-models/${model.id}/role`, { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({role}) });
      if (role === 'candidate') this.showToast('已降级为普通候选，现在可以停用或删除');
      else this.showToast('主备模型已切换并立即生效');
      await this.loadAiModels();
    },
    async toggleAiModel(model) {
      const next = !model.enabled;
      if (!await this.askConfirm(`${next ? '启用' : '停用'}“${model.displayName}”？${next ? '启用后仍需手动设为主模型或备用模型。' : '停用后系统不会调用该候选模型。'}`, `${next ? '启用' : '停用'}模型？`)) return;
      await this.api(`/api/ai-models/${model.id}/enabled`, { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({enabled:next}) });
      this.showToast(next ? '模型已启用' : '模型已停用');
      await this.loadAiModels();
    },
    async deleteAiModel(model) {
      if (!await this.askConfirm(`确认删除“${model.displayName}”？其 API Key 密文也会一并删除，操作不可恢复。`, '删除模型？')) return;
      await this.api('/api/ai-models/' + model.id, {method:'DELETE'});
      delete this.modelTestResults[model.id];
      this.showToast('模型已删除');
      await this.loadAiModels();
    },
    async testAiModel(model) {
      this.modelTestingId = model.id;
      try {
        const result = await this.api(`/api/ai-models/${model.id}/test`, {method:'POST'});
        this.modelTestResults[model.id] = result;
        this.showToast(`${result.displayName || model.displayName} 综合测试成功：基础 ${result.basicLatencyMs}ms，质量评测 ${result.qualityLatencyMs}ms`);
      } catch (e) {
        this.modelTestResults[model.id] = { passed:false, message:e && e.message ? e.message : '综合测试失败' };
      } finally { this.modelTestingId = null; }
    },

    // ===== 设置页（W27）：API Key =====
    async loadSettings() {
      const d = await this.api('/config/list');
      this.settingKeys = d.keys || [];
      this.settingValues = {};
      (d.keys || []).forEach(k => { this.settingValues[k.key] = k.value || ''; });
    },
    async saveSetting(item) {
      if (!this.settingValues[item.key]) return this.showToast('请输入新的密钥值');
      await this.api('/config/' + item.key, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ value: this.settingValues[item.key] || '' }),
      });
      this.showToast('已保存并生效 ✓'); this.loadSettings();
    },
    // 仅超管：查看/隐藏已保存密钥明文（后端每次都留审计）
    async toggleRevealSetting(item) {
      if (this.revealedKeys[item.key]) {
        this.revealedKeys[item.key] = false;
        this.settingValues[item.key] = '';
        return;
      }
      try {
        const r = await this.api('/config/secret/reveal?key=' + encodeURIComponent(item.key));
        if (!r.value) return this.showToast('未在数据库保存过该密钥（可能来自环境变量或未配置）');
        this.settingValues[item.key] = r.value;
        this.revealedKeys[item.key] = true;
        this.showToast('已显示明文；点「隐藏」还原输入框，编辑后可覆盖保存');
      } catch (e) { this.showToast((e && e.message) || '查看失败'); }
    },
    // 仅超管：查看/隐藏聊天模型已保存的 Key（编辑对话框内小眼睛）
    async toggleModelKeyReveal() {
      if (!this.modelDraft.id) return;
      if (this.modelKeyRevealed) {
        this.modelKeyRevealed = false;
        this.modelDraft.apiKey = '';
        return;
      }
      try {
        const r = await this.api('/api/ai-models/' + this.modelDraft.id + '/reveal-key');
        if (!r.value) return this.showToast('该模型未配置 Key');
        this.modelDraft.apiKey = r.value;
        this.modelKeyRevealed = true;
      } catch (e) { this.showToast((e && e.message) || '查看失败'); }
    },
    async clearSetting(item) {
      if (!await this.askConfirm(`将清除「${item.label}」的数据库配置；若存在环境变量将自动回退。`, '清除密钥？')) return;
      await this.api('/config/' + item.key, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ value: '' }),
      });
      this.showToast('数据库密钥已清除'); this.loadSettings();
    },
    async loadReport() {
      const query = this.reportQuery();
      const [report, daily, hotQuestions] = await Promise.all([
        this.api('/report/overview' + query),
        this.api('/report/daily' + query),
        this.api('/report/hot-questions' + query),
      ]);
      // 快速切换日期时，丢弃旧请求，避免卡片和趋势展示不同期间。
      if (query !== this.reportQuery()) return;
      this.report = report;
      this.daily = daily;
      this.hotQuestions = hotQuestions;
      await this.loadReportRealtime();
    },
    async loadReportRealtime() {
      if (this.reportRealtimeLoading) return;
      this.reportRealtimeLoading = true;
      try { this.reportRealtime = await this.api('/report/realtime'); }
      catch (e) { this.showToast((e && e.message) || '排队情况刷新失败'); }
      finally { this.reportRealtimeLoading = false; }
    },
    reportRate(value) {
      return value == null || !Number.isFinite(Number(value)) ? '暂无数据' : Number(value).toFixed(1) + '%';
    },
    reportDuration(value) {
      if (value == null || !Number.isFinite(Number(value))) return '暂无数据';
      const ms = Number(value);
      return ms >= 1000 ? (ms / 1000).toFixed(2) + ' 秒' : Math.round(ms) + ' 毫秒';
    },
    reportHandoffReason(reason) {
      return { CUSTOMER_REQUEST: '客户提出人工诉求', SAFETY: '安全规则转接', UNKNOWN: '具体原因未记录' }[reason] || '具体原因未记录';
    },
    reportHasRange() { return !!(this.reportRange.from || this.reportRange.to); },
    reportQuery() {
      const from = this.reportRange.from, to = this.reportRange.to;
      if (from || to) return '?from=' + encodeURIComponent(from || '') + '&to=' + encodeURIComponent(to || '');
      return '?days=7';
    },
    // ===== 双月日历范围选择器交互 =====
    toggleReportPicker() {
      this.pickerOpen = !this.pickerOpen;
      if (this.pickerOpen) {
        const base = this.reportRange.from ? new Date(this.reportRange.from + 'T00:00:00') : new Date();
        this.pickerView = { y: base.getFullYear(), m: base.getMonth() + 1 };
        this.pickerHover = '';
      }
    },
    pickerShiftMonth(delta) {
      let { y, m } = this.pickerView;
      m += delta;
      while (m > 12) { m -= 12; y++; }
      while (m < 1) { m += 12; y--; }
      this.pickerView = { y, m };
    },
    buildPickerMonth(first) {
      const y = first.getFullYear(), m = first.getMonth();
      const blanks = first.getDay();   // 周日在首位（与截图一致）
      const total = new Date(y, m + 1, 0).getDate();
      const today = new Date(); today.setHours(0, 0, 0, 0);
      const days = [];
      for (let d = 1; d <= total; d++) {
        const dd = new Date(y, m, d);
        days.push({
          day: d,
          date: `${y}-${String(m + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`,
          future: dd > today,
        });
      }
      return { label: `${y} 年 ${m + 1} 月`, blanks, days };
    },
    pickDate(item) {
      if (item.future) return;
      // 第一次点击选起点；已有完整区间时重新开始选
      if (!this.reportRange.from || (this.reportRange.from && this.reportRange.to)) {
        this.reportRange.from = item.date;
        this.reportRange.to = '';
        return;
      }
      let a = this.reportRange.from, b = item.date;
      if (b < a) { const t = a; a = b; b = t; }
      this.reportRange.from = a;
      this.reportRange.to = b;
      this.pickerOpen = false;
      this.pickerHover = '';
      this.loadReport();   // 选完区间自动应用查询
    },
    pickerCellClass(item) {
      const from = this.reportRange.from;
      const to = this.reportRange.to || this.pickerHover;   // 悬停实时预览区间
      return {
        future: item.future,
        selected: item.date === from || item.date === this.reportRange.to,
        inrange: !!(from && to && item.date > from && item.date < to),
      };
    },
    applyQuickRange(q) {
      const fmt = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
      const t = new Date();
      const shift = n => { const d = new Date(t); d.setDate(d.getDate() + n); return d; };
      let from, to;
      switch (q.kind) {
        case 'today': from = to = t; break;
        case 'yesterday': from = to = shift(-1); break;
        default: {
          const n = Number(String(q.kind).replace('d', '')) || 7;
          from = shift(-(n - 1)); to = t;
        }
      }
      this.reportRange = { from: fmt(from), to: fmt(to) };
      this.pickerOpen = false;
      this.loadReport();
    },
    onDocClickReport(e) {
      if (this.pickerOpen && !e.target.closest('.range-picker')) this.pickerOpen = false;
    },
    applyReportRange() {
      if (this.reportRange.from && this.reportRange.to && this.reportRange.from > this.reportRange.to) {
        this.showToast('开始日期不能晚于结束日期');
        return;
      }
      this.loadReport();
    },
    // CSV 导出：Cookie 鉴权同源下载（带 UTF-8 BOM，Excel 直开不乱码）
    async exportReport(kind) {
      if (this.reportExporting) return;
      this.reportExporting = true;
      try {
        const resp = await fetch('/report/export/' + kind + this.reportQuery());
        if (resp.status === 401) { this.askToken(); return; }
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        await this.downloadBlob(resp);
        this.showToast('已导出：' + ({ overview: '应答构成 CSV', daily: '每日趋势 CSV',
          'hot-questions': '热点问题 CSV', excel: 'Excel 报表（4 个工作表）' }[kind] || kind));
      } catch (e) {
        this.showToast('导出失败：' + (e && e.message ? e.message : e));
      } finally {
        this.reportExporting = false;
      }
    },
    async exportQualityEvaluations() {
      if (this.reportExporting) return;
      this.reportExporting = true;
      try {
        const params = new URLSearchParams();
        Object.entries(this.qualityFilter || {}).forEach(([key, value]) => {
          if (value !== '' && value != null) params.set(key, value);
        });
        const qs = params.toString();
        const resp = await fetch('/api/quality/evaluations/export' + (qs ? '?' + qs : ''));
        if (resp.status === 401) { this.askToken(); return; }
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        await this.downloadBlob(resp);
        this.showToast('评分记录已导出（最多 2000 条）');
      } catch (e) {
        this.showToast('导出失败：' + (e && e.message ? e.message : e));
      } finally {
        this.reportExporting = false;
      }
    },
    async downloadBlob(resp) {
      const blob = await resp.blob();
      const disposition = resp.headers.get('Content-Disposition') || '';
      const m = disposition.match(/filename="([^"]+)"/);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = m ? m[1] : 'export.csv';
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    },
    // 趋势图快捷范围与顶部指标、热点问题共同切换。
    // ===== AI 调优中心 / 策略配置 =====
    async loadConfigs() {
      if (!this.debugConfigsLoaded) {
        const r = await this.api('/config/ai-settings');
        let promptWorkspace = null;
        try { promptWorkspace = await this.api('/config/prompt-workspace'); } catch (e) { promptWorkspace = null; }
        this.promptWorkspace = promptWorkspace;
        const promptDraftValues = promptWorkspace && promptWorkspace.draft && promptWorkspace.draft.values
          ? promptWorkspace.draft.values : {};
        this.configItems = (r.items || []).map(item => ({
          ...item,
          value: item.versionedPrompt && Object.prototype.hasOwnProperty.call(promptDraftValues, item.key)
            ? String(promptDraftValues[item.key] == null ? '' : promptDraftValues[item.key])
            : (item.value == null ? '' : String(item.value)),
          original: item.value == null ? '' : String(item.value),
          draftValue: item.versionedPrompt && Object.prototype.hasOwnProperty.call(promptDraftValues, item.key)
            ? String(promptDraftValues[item.key] == null ? '' : promptDraftValues[item.key])
            : (item.value == null ? '' : String(item.value)),
          defaultValue: item.defaultValue == null ? '' : String(item.defaultValue),
          touched: false,
        }));
        if (promptWorkspace && promptWorkspace.draft) {
          try { this.promptGateTask = await this.api('/config/prompt-regression-gate/latest'); }
          catch (e) { this.promptGateTask = null; }
        } else this.promptGateTask = null;
        const groups = [...new Set(this.configItems.map(item => item.group))].filter(g => g !== '首页展示');
        if (!groups.includes(this.activeConfigGroup)) this.activeConfigGroup = groups[0] || '';
        this.debugConfigsLoaded = true;
      }
      if (!this.debugCasesLoaded) {
        try {
          const saved = await this.api('/api/quality/regression-cases?includeDisabled=false');
          if (Array.isArray(saved)) this.debugCases = saved.map(c => ({
            id:c.id, name:c.name, message:c.message, history:c.history || '',
            expected:c.expectedReplyType || 'AI', expectedAnswerPoints:c.expectedAnswerPoints || '',
            forbiddenAnswerPoints:c.forbiddenAnswerPoints || '', expectedAuthorityDomain:c.expectedAuthorityDomain || '',
            expectedAuthoritySource:c.expectedAuthoritySource || '', expectedModelInvoked:c.expectedModelInvoked == null ? null : !!c.expectedModelInvoked,
            actual:'', actualAnswer:'', assertionFailures:'', passed:null,
          }));
        } catch (e) { this.debugCases = []; }
        this.debugCasesLoaded = true;
      }
    },
    async saveConfig(c) {
      const error = this.configError(c);
      if (error) return this.showToast(error);
      if (c.versionedPrompt) {
        try {
          const workspace = await this.api('/config/prompt-draft', { method: 'PUT', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ values:{ [c.key]:c.value } }) });
          this.applyPromptWorkspace(workspace, true);
          c.value = c.draftValue;
          c.touched = false;
          this.showToast(c.label + ' 已保存到 Prompt 草稿，尚未影响线上');
        } catch (e) { this.showToast('草稿保存失败：' + (e && e.message ? e.message : e)); }
        return;
      }
      if (!await this.askConfirm(`「${c.label}」发布后会立即影响真实客户对话。`, '发布这项 AI 设置？')) return;
      const r = await this.api('/config/ai-settings', { method: 'PUT', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ values:{ [c.key]:c.value } }) });
      c.value = r.values && r.values[c.key] != null ? String(r.values[c.key]) : c.value;
      c.original = c.value; c.touched = false;
      this.showToast(c.label + ' 已发布');
    },
    // 统一模型管理：一次性发布视觉/向量模型的多个配置项（同一 PUT 请求）
    async saveModelEndpoint(items) {
      if (!Array.isArray(items) || !items.length) return;
      const values = {};
      for (const it of items) {
        if (!it || !it.key) continue;
        const err = this.configError(it);
        if (err) return this.showToast(it.label + '：' + err);
        values[it.key] = it.value;
      }
      if (!Object.keys(values).length) return;
      try {
        const r = await this.api('/config/ai-settings', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ values }) });
        for (const it of items) {
          const saved = r.values && r.values[it.key];
          if (saved != null) it.value = String(saved);
          it.original = it.value; it.touched = false;
        }
        this.showToast('已发布并立即生效 ✓');
      } catch (e) { this.showToast('发布失败：' + (e && e.message ? e.message : e)); }
    },
    async saveEmbeddingModel() {
      const items = [this.cfgEmbedBase, this.cfgEmbedName];
      for (const it of items) {
        if (!it || !it.key) return this.showToast('向量模型配置尚未加载');
        const err = this.configError(it);
        if (err) return this.showToast(it.label + '：' + err);
      }
      if (!await this.askConfirm('系统会先用候选模型重新向量化全部已有知识；只有全部成功且维度为 1024 时才切换，失败会继续使用原模型。', '安全切换向量模型？')) return;
      try {
        const r = await this.api('/config/embedding-model/switch', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            baseUrl: this.cfgEmbedBase.value,
            modelName: this.cfgEmbedName.value,
            apiKey: this.settingValues['model.embedding.apiKey'] || '',
          }),
        });
        this.embeddingSwitchTask = { taskId: r.taskId, status: r.status || 'queued', total_chunks: 0, processed_chunks: 0 };
        this.showToast(r.message || '已提交向量模型切换任务');
        await this.pollEmbeddingSwitch(r.taskId);
      } catch (e) {
        this.showToast('切换提交失败：' + (e && e.message ? e.message : e));
      }
    },
    async pollEmbeddingSwitch(taskId) {
      if (this.embeddingSwitchPolling) return;
      this.embeddingSwitchPolling = true;
      try {
        for (let i = 0; i < 1200; i++) {
          const task = await this.api('/config/embedding-model/switch/status?taskId=' + encodeURIComponent(taskId));
          if (!task || task.found === false) throw new Error('找不到向量模型切换任务');
          this.embeddingSwitchTask = task;
          if (task.status === 'done') {
            this.cfgEmbedBase.original = this.cfgEmbedBase.value;
            this.cfgEmbedName.original = this.cfgEmbedName.value;
            this.settingValues['model.embedding.apiKey'] = '';
            await this.loadSettings();
            this.showToast('已有知识已全部重新向量化，向量模型切换完成 ✓');
            return;
          }
          if (task.status === 'failed') {
            this.showToast('切换失败，已继续使用原模型：' + (task.error || '未知错误'));
            return;
          }
          await new Promise(resolve => setTimeout(resolve, 1500));
        }
        this.showToast('任务仍在后台执行，可稍后刷新查看状态');
      } catch (e) {
        this.showToast('获取切换进度失败：' + (e && e.message ? e.message : e));
      } finally {
        this.embeddingSwitchPolling = false;
      }
    },
    configError(c) {
      if (c.required && !String(c.value || '').trim()) return '值不能为空';
      if (c.input === 'number' || c.input === 'integer') {
        const n = Number(c.value);
        if (!Number.isFinite(n)) return '请输入有效数字';
        if (c.input === 'integer' && !Number.isInteger(n)) return '请输入整数';
        if (c.min != null && n < Number(c.min)) return `不能小于 ${c.min}`;
        if (c.max != null && n > Number(c.max)) return `不能大于 ${c.max}`;
      }
      if (c.input === 'timeRange' && !/^\d{2}:\d{2}-\d{2}:\d{2}$/.test(c.value)) return '格式应为 09:00-22:00';
      if (c.input === 'json') { try { const v=JSON.parse(c.value); if (!v || Array.isArray(v) || typeof v!=='object') return '请输入 JSON 对象'; } catch(e) { return 'JSON 格式不正确'; } }
      if (c.key === 'intent.projectQuestionTemplate' && !c.value.includes('{project}')) return '必须包含 {project} 占位符';
      return '';
    },
    formatConfigBound(value) {
      if (value == null || value === '') return '不限';
      const number = Number(value);
      return Number.isFinite(number) ? String(number) : String(value);
    },
    configHelpId(c) {
      return 'config-help-' + String(c && c.key ? c.key : 'unknown').replace(/[^a-zA-Z0-9_-]/g, '-');
    },
    async saveAllConfigs() {
      const changed = this.configItems.filter(c => c.value !== (c.versionedPrompt ? c.draftValue : c.original));
      if (!changed.length) return;
      const invalid = changed.find(c => this.configError(c));
      if (invalid) return this.showToast(invalid.label + '：' + this.configError(invalid));
      const promptItems = changed.filter(c => c.versionedPrompt);
      const directItems = changed.filter(c => !c.versionedPrompt);
      if (directItems.length && !await this.askConfirm(`其中 ${directItems.length} 项普通参数会立即发布；${promptItems.length} 项 Prompt 只保存为草稿。`, '保存这些修改？')) return;
      try {
        if (promptItems.length) {
          const values = Object.fromEntries(promptItems.map(c => [c.key,c.value]));
          const workspace = await this.api('/config/prompt-draft', { method:'PUT', headers:{'Content-Type':'application/json'}, body:JSON.stringify({values}) });
          this.applyPromptWorkspace(workspace, true);
          promptItems.forEach(c => { c.value=c.draftValue; c.touched=false; });
        }
        if (directItems.length) {
          const values = Object.fromEntries(directItems.map(c => [c.key,c.value]));
          const r = await this.api('/config/ai-settings', { method:'PUT', headers:{'Content-Type':'application/json'}, body:JSON.stringify({values}) });
          directItems.forEach(c => { c.value = r.values && r.values[c.key] != null ? String(r.values[c.key]) : c.value; c.original=c.value; c.draftValue=c.value; c.touched=false; });
        }
        this.showToast(promptItems.length && directItems.length ? '普通参数已发布，Prompt 修改已保存为草稿' : (promptItems.length ? 'Prompt 草稿已保存，测试后再发布版本' : 'AI 参数已发布'));
      } catch (e) { this.showToast('保存失败：' + (e && e.message ? e.message : e)); }
    },
    resetConfigDrafts() {
      this.configItems.forEach(c => { c.value = c.versionedPrompt ? c.draftValue : c.original; c.touched=false; });
      this.showToast('已撤销尚未保存的编辑');
    },
    restoreConfigDefault(c) {
      c.value = c.defaultValue; c.touched = true;
      this.showToast(c.label + ' 已恢复为默认草稿，发布后生效');
    },
    applyPromptWorkspace(workspace, updateEditor) {
      const previousHash = this.promptWorkspace && this.promptWorkspace.draft
        ? this.promptWorkspace.draft.draftHash : null;
      this.promptWorkspace = workspace || null;
      const values = workspace && workspace.draft && workspace.draft.values ? workspace.draft.values : {};
      (this.configItems || []).filter(c => c.versionedPrompt).forEach(c => {
        const value = Object.prototype.hasOwnProperty.call(values, c.key) ? String(values[c.key] == null ? '' : values[c.key]) : c.original;
        c.draftValue = value;
        if (updateEditor === false) { c.value = value; c.touched = false; }
      });
      const nextHash = workspace && workspace.draft ? workspace.draft.draftHash : null;
      if (previousHash !== nextHash) this.promptGateTask = null;
    },
    usePromptDraftInDebugger() {
      if (!this.promptDraftCount) return;
      this.debugSection = 'playground';
      this.switchTab('aidev');
      this.showToast('对话模拟器将使用主对话相关 Prompt 草稿；线上版本不会改变');
    },
    async publishPromptDraft() {
      if (this.promptReleaseBusy) return;
      const editedItems = this.configItems.filter(c => c.versionedPrompt && c.value !== c.draftValue);
      const invalid = editedItems.find(c => this.configError(c));
      if (invalid) return this.showToast(invalid.label + '：' + this.configError(invalid));
      if (editedItems.length) return this.showToast('请先运行发布检查；系统会先保存当前 Prompt 编辑');
      if (!this.promptGatePassed) return this.showToast('当前草稿尚未通过发布检查');
      this.promptReleaseBusy = true;
      try {
        if (!this.promptSavedDraftCount) return this.showToast('当前没有可发布的 Prompt 草稿');
        if (!await this.askConfirm(`全部回归检查已通过。将发布 ${this.promptSavedDraftCount} 项 Prompt 修改并立即用于新对话。`, '发布 Prompt 新版本？')) return;
        const result = await this.api('/config/prompt-release', { method:'POST' });
        this.promptGateTask = null;
        this.debugConfigsLoaded = false;
        await this.loadConfigs();
        if (this.promptHistoryOpen) await this.loadPromptHistory();
        this.showToast((result && result.result) || 'Prompt 新版本已发布');
      } catch (e) { this.showToast('Prompt 发布失败：' + (e && e.message ? e.message : e)); }
      finally { this.promptReleaseBusy = false; }
    },
    async runPromptRegressionGate() {
      if (this.promptGatePolling || this.promptReleaseBusy) return;
      const editedItems = this.configItems.filter(c => c.versionedPrompt && c.value !== c.draftValue);
      const invalid = editedItems.find(c => this.configError(c));
      if (invalid) return this.showToast(invalid.label + '：' + this.configError(invalid));
      const count = (this.debugCases || []).length;
      if (!await this.askConfirm(`系统会用当前 Prompt 草稿运行全部${count ? ` ${count} 条` : ''}启用回归用例；生成型用例可能调用真实模型并产生额度消耗。`, '运行 Prompt 发布检查？')) return;
      this.promptGatePolling = true;
      try {
        if (editedItems.length) {
          const values = Object.fromEntries(editedItems.map(c => [c.key,c.value]));
          const workspace = await this.api('/config/prompt-draft', {method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({values})});
          this.applyPromptWorkspace(workspace, true);
          editedItems.forEach(c => { c.value=c.draftValue; c.touched=false; });
        }
        if (!this.promptSavedDraftCount) throw new Error('当前没有可检查的 Prompt 草稿');
        const task = await this.api('/config/prompt-regression-gate', {method:'POST'});
        this.promptGateTask = task;
        await this.pollPromptRegressionGate(task.id);
      } catch (e) {
        this.showToast('发布检查失败：' + (e && e.message ? e.message : e));
      } finally { this.promptGatePolling = false; }
    },
    async pollPromptRegressionGate(taskId) {
      if (!taskId) throw new Error('发布检查任务编号缺失');
      for (let i=0; i<1200; i++) {
        const task = await this.api('/config/prompt-regression-gate/status?taskId=' + encodeURIComponent(taskId));
        if (!task || task.found === false) throw new Error('找不到发布检查任务');
        this.promptGateTask = task;
        if (task.status === 'passed') { this.showToast(`发布检查通过：${task.passedCount}/${task.requestedCount}`); return; }
        if (task.status === 'failed') { this.showToast(`发布检查未通过：${task.failedCount || 0} 条失败`); return; }
        await new Promise(resolve => setTimeout(resolve, 1500));
      }
      this.showToast('发布检查仍在后台运行，可稍后返回查看状态');
    },
    async discardPromptDraft() {
      if (!await this.askConfirm('将删除你已保存的 Prompt 草稿，线上版本不会改变。', '放弃 Prompt 草稿？')) return;
      this.promptReleaseBusy = true;
      try {
        const workspace = await this.api('/config/prompt-draft', { method:'DELETE' });
        this.applyPromptWorkspace(workspace, false);
        this.promptGateTask = null;
        this.showToast('Prompt 草稿已放弃');
      } catch (e) { this.showToast('放弃草稿失败：' + (e && e.message ? e.message : e)); }
      finally { this.promptReleaseBusy = false; }
    },
    async togglePromptHistory() {
      this.promptHistoryOpen = !this.promptHistoryOpen;
      this.promptReleaseDetail = null;
      if (this.promptHistoryOpen) await this.loadPromptHistory();
    },
    async loadPromptHistory() {
      try { this.promptHistory = await this.api('/config/prompt-releases?limit=20') || []; }
      catch (e) { this.promptHistory = []; this.showToast('版本历史加载失败：' + (e && e.message ? e.message : e)); }
    },
    async viewPromptRelease(releaseId) {
      try { this.promptReleaseDetail = await this.api('/config/prompt-releases/' + encodeURIComponent(releaseId)); }
      catch (e) { this.showToast('版本差异加载失败：' + (e && e.message ? e.message : e)); }
    },
    async rollbackPromptRelease(release) {
      if (!release || !release.id) return;
      const draftWarning = this.promptPendingCount ? '你当前的 Prompt 草稿也会被清除。' : '';
      if (!await this.askConfirm(`系统会恢复 ${release.version} 的全部 Prompt，并生成一个新的回滚版本；不会删除现有历史。${draftWarning}`, '回滚 Prompt？')) return;
      this.promptReleaseBusy = true;
      try {
        const result = await this.api('/config/prompt-releases/' + encodeURIComponent(release.id) + '/rollback', { method:'POST' });
        this.debugConfigsLoaded = false;
        await this.loadConfigs();
        await this.loadPromptHistory();
        this.promptReleaseDetail = null;
        this.showToast((result && result.result) || 'Prompt 已回滚');
      } catch (e) { this.showToast('Prompt 回滚失败：' + (e && e.message ? e.message : e)); }
      finally { this.promptReleaseBusy = false; }
    },
    promptActionLabel(action) {
      return ({BASELINE:'基线',PUBLISH:'发布',ROLLBACK:'回滚'}[action] || action || '—');
    },
    promptReleaseDiffRows() {
      if (!this.promptReleaseDetail) return [];
      return this.promptReleaseDetail.current
        ? (this.promptReleaseDetail.diffFromPrevious || [])
        : (this.promptReleaseDetail.diffFromCurrent || []);
    },
    addForbiddenWord() {
      const w = (this.newForbiddenWord || '').trim();
      if (!w) return;
      const item = this.forbiddenItem;
      if (!item) return;
      if (this.forbiddenWordsList.some(x => x === w)) { this.showToast('该禁说词已存在'); this.newForbiddenWord = ''; return; }
      item.value = [...this.forbiddenWordsList, w].join('\n');
      this.newForbiddenWord = '';
    },
    removeForbiddenWord(w) {
      const item = this.forbiddenItem;
      if (!item) return;
      item.value = this.forbiddenWordsList.filter(x => x !== w).join('\n');
    },
    async saveForbidden() {
      const fw = this.forbiddenItem, act = this.forbiddenActionItem;
      if (!fw) return;
      const values = { [fw.key]: fw.value };
      if (act) values[act.key] = act.value;
      try {
        const r = await this.api('/config/ai-settings', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ values }) });
        fw.original = fw.value; fw.touched = false;
        if (act) { act.original = act.value; act.touched = false; }
        this.showToast('禁说词已发布');
      } catch (e) { this.showToast('发布失败：' + (e && e.message ? e.message : e)); }
    },
    resetForbidden() {
      const fw = this.forbiddenItem, act = this.forbiddenActionItem;
      if (fw) { fw.value = fw.original; fw.touched = false; }
      if (act) { act.value = act.original; act.touched = false; }
    },
    debugPayload(useDraft, message, runModel, withContext) {
      const val = key => {
        const item = this.configItems.find(c => c.key === key);
        return item ? item.value : '';
      };
      const draft = useDraft !== false;
      const context = withContext !== false;
      const numberOr = (v, fallback) => Number.isFinite(parseFloat(v)) ? parseFloat(v) : fallback;
      return {
        message: message,
        history: context ? this.debugHistoryText : '',
        profile: context ? this.debugProfile : '',
        lat: context && this.debugLat !== '' ? Number(this.debugLat) : null,
        lng: context && this.debugLng !== '' ? Number(this.debugLng) : null,
        relevanceThreshold: draft ? numberOr(val('threshold.relevance'), 0.5) : null,
        faqDirectThreshold: draft ? numberOr(val('threshold.faqDirect'), 0.15) : null,
        tone: draft ? (val('prompt.tone') || null) : null,
        refusalAnswer: draft ? (val('refusal.answer') || null) : null,
        systemPrompt: draft ? (val('agent.systemPrompt') || null) : null,
        ragRules: draft ? (val('prompt.ragRules') || null) : null,
        moduleCharBudgets: draft ? (val('prompt.moduleCharBudgets') || null) : null,
        classifierPrompt: draft ? (val('agent.classifierPrompt') || null) : null,
        projectIntroAiEnabled: draft ? val('projectIntro.aiEnabled') === 'true' : null,
        projectIntroRequireBothImages: draft ? val('projectIntro.requireBothImages') === 'true' : null,
        projectIntroDecisionPrompt: draft ? (val('projectIntro.decisionPrompt') || null) : null,
        runModel: !!runModel,
      };
    },
    async callDebug(payload) {
      const result = await this.api('/api/chat/debug/run', {
        method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload),
      });
      if (result && result.error) throw new Error(result.error);
      return result;
    },
    async runDebug(runModel) {
      const message = (this.debugMsg || '').trim();
      if (!message) return this.showToast('请输入客户消息');
      this.debugging = true;
      try {
        const result = await this.callDebug(this.debugPayload(true, message, !!runModel, true));
        this.debugResult = result;
        this.debugInspector = 'route';
        // 始终把客户消息记入对话流，保证多轮上下文（画像继承、指代）在路由预览下也能累积；
        // 仅当本次确有回答（直达答案或真实模型生成）时才补一条 AI 气泡。
        this.debugMessages.push({ role: 'user', content: message });
        if (result.answer) {
          this.debugMessages.push({ role: 'assistant', content: result.answer, type: result.replyType });
        }
      } finally {
        this.debugging = false;
      }
    },
    applyDebugPreset() {
      const p = this.debugPresets.find(x => x.key === this.debugPreset);
      if (!p) return;
      this.debugMsg = p.message;
      this.debugProfile = p.profile || '';
      this.debugResult = null;
    },
    clearDebugConversation() {
      this.debugMessages = [];
      this.debugHistoryOverride = '';
      this.debugResult = null;
      this.debugCompare = null;
      this.showToast('模拟上下文已清空');
    },
    async runCompare() {
      const message = (this.debugMsg || '').trim();
      if (!message) return this.showToast('请先输入客户消息');
      this.debugComparing = true;
      try {
        const [live, draft] = await Promise.all([
          this.callDebug(this.debugPayload(false, message, false, true)),
          this.callDebug(this.debugPayload(true, message, false, true)),
        ]);
        this.debugCompare = { live, draft };
      } finally { this.debugComparing = false; }
    },
    async copyDebugPrompt() {
      if (!this.debugResult || !this.debugResult.prompt) return;
      try { await navigator.clipboard.writeText(this.debugResult.prompt); this.showToast('Prompt 已复制'); }
      catch (e) { this.showToast('复制失败，请手动选中'); }
    },
    debugStatusName(s) {
      return { matched: '命中', executed: '已执行', ready: '已就绪', passed: '通过', rejected: '已过滤', failed: '失败', empty: '无命中', skipped: '已跳过' }[s] || s;
    },
    debugTypeName(t) { return { SAFETY: '情绪安全', INTRO: 'AI 首次项目介绍', FACT: '结构化事实', FAQ: 'FAQ 直回', AGENT: '业务工具', CLARIFY: '追问澄清', REFUSAL: '兜底回复', ERROR: '服务异常', AI: 'AI 生成' }[t] || t; },
    debugChipClass(t) { return { SAFETY: 'chip-warn', INTRO: 'chip-done', FACT: 'chip-done', FAQ: 'chip-done', AGENT: 'chip-done', CLARIFY: 'chip-warn', REFUSAL: 'chip-new', AI: 'chip-warn' }[t] || ''; },
    regressionNeedsAnswer(c) {
      return !!String(c.expectedAnswerPoints || '').trim()
        || !!String(c.forbiddenAnswerPoints || '').trim() || c.expectedModelInvoked === true;
    },
    async runOneCase(c) {
      c.actual = ''; c.actualAnswer = ''; c.assertionFailures = ''; c.passed = null;
      if (!c.message || !c.expected) return;
      try {
        // 服务端按断言统一决定是否生成，客户端只提交用例及草稿。
        const payload = this.debugPayload(true, c.message, false, false);
        payload.history = c.history || '';
        payload.expectedReplyType = c.expected || '';
        payload.expectedAnswerPoints = c.expectedAnswerPoints || '';
        payload.forbiddenAnswerPoints = c.forbiddenAnswerPoints || '';
        payload.expectedAuthorityDomain = c.expectedAuthorityDomain || '';
        payload.expectedAuthoritySource = c.expectedAuthoritySource || '';
        payload.expectedModelInvoked = c.expectedModelInvoked == null ? null : !!c.expectedModelInvoked;
        const r = await this.callDebug(payload);
        c.actual = r.replyType;
        c.actualAnswer = r.answer || '';
        const valid = r.assertion && typeof r.assertion.passed === 'boolean'
          && Array.isArray(r.assertion.failures) && r.assertion.failures.every(reason => typeof reason === 'string');
        c.passed = !!(!r.modelError && valid && r.assertion.passed === true && r.assertion.failures.length === 0);
        c.assertionFailures = r.modelError || (valid ? r.assertion.failures.join('；') : '服务端返回了无效的断言结果');
      } catch (e) { c.actual = 'ERROR'; c.assertionFailures = (e && e.message) || '运行失败'; c.passed = false; }
    },
    async runDebugCase(c) {
      this.debugSuiteRunning = true;
      try {
        await this.runOneCase(c);
        const verdict = c.passed === true ? '通过' : (c.passed === false ? `失败（实际 ${this.debugTypeName(c.actual)}）` : '未运行');
        this.showToast(`用例「${c.name || '未命名'}」：${verdict}`);
      } finally { this.debugSuiteRunning = false; }
    },
    async runDebugSuite() {
      const cases = this.debugCases;
      if (!cases.length) return this.showToast('没有可执行的回归用例');
      const modelCandidates = cases.filter(c => ['AI','INTRO'].includes(c.expected) && this.regressionNeedsAnswer(c));
      if (modelCandidates.length && !await this.askConfirm(
        `其中 ${modelCandidates.length} 条生成型用例需要检查最终回答，可能调用真实模型并产生额度消耗；其他直回用例不会因此调用模型。`,
        '运行全部回归用例？')) return;
      this.debugSuiteRunning = true;
      try {
        // 并发上限 4：既比串行快，又不一次性打爆模型/检索接口
        const CONCURRENCY = 4;
        let cursor = 0;
        const worker = async () => {
          while (cursor < cases.length) {
            const c = cases[cursor++];
            await this.runOneCase(c);
          }
        };
        await Promise.all(Array.from({ length: Math.min(CONCURRENCY, cases.length) }, () => worker()));
        this.showToast(`回归完成：${this.debugSuiteStats.passed}/${this.debugSuiteStats.total} 通过`);
      } finally { this.debugSuiteRunning = false; }
    },
    addDebugCase() {
      this.debugCases.unshift({ id: null, name: '新用例', message: '', history:'', expected: 'AI', expectedAnswerPoints:'',
        forbiddenAnswerPoints:'', expectedAuthorityDomain:'', expectedAuthoritySource:'', expectedModelInvoked:null,
        actual: '', actualAnswer:'', assertionFailures:'', passed: null });
    },
    async saveDebugCase(c) {
      if (!c.name || !c.message) return this.showToast('用例名称和客户消息不能为空');
      const payload = this.regressionCasePayload(c);
      const saved = await this.api(c.id ? `/api/quality/regression-cases/${c.id}` : '/api/quality/regression-cases', {
        method:c.id?'PUT':'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(payload),
      });
      Object.assign(c, this.regressionCaseFromApi(saved));
      this.showToast('回归用例已保存并共享');
    },
    async removeDebugCase(c,i) {
      if (!await this.askConfirm(`删除回归用例“${c.name || '未命名用例'}”？`, '删除回归用例？')) return;
      if (c.id) await this.api(`/api/quality/regression-cases/${c.id}`, {method:'DELETE'});
      this.debugCases.splice(i,1);
      this.showToast('回归用例已删除');
    },
    async resetDebugCases() {
      this.showToast('默认用例已由数据库统一维护；可手动新增或导入需要的用例');
    },
    exportDebugCases() {
      // 只导出用例规格，剥离 actual/passed 等运行态数据。
      // 剥离运行态 actual/passed 与本地 id 噪声，导出的文件可直接再导入且不会带脏数据。
      const spec = this.debugCases.map(c => {
        const o = this.regressionCasePayload(c);
        delete o.enabled;
        if (c.id) o.id = c.id;
        return o;
      });
      const blob = new Blob([JSON.stringify(spec, null, 2)], {type:'application/json'});
      const url = URL.createObjectURL(blob), a = document.createElement('a');
      a.href = url; a.download = 'ai-debug-cases.json'; a.click(); URL.revokeObjectURL(url);
    },
    async importDebugCases(e) {
      const file = e.target.files && e.target.files[0];
      if (!file) return;
      try {
        const rows = JSON.parse(await file.text());
        if (!Array.isArray(rows)) throw new Error('格式不对');
        let imported = 0, updated = 0;
        const byId = new Map(this.debugCases.map(c => [c.id, c]));
        for (const c of rows) {
          if (!c.message) continue;
          const payload = this.regressionCasePayload({ ...c, name:c.name || '导入用例', expected:c.expectedReplyType || c.expected || 'AI' });
          // 已带 DB id 且本地存在 → 更新（避免重复导入生成重复用例）；否则新建
          if (c.id && byId.has(c.id)) {
            const target = byId.get(c.id);
            const saved = await this.api(`/api/quality/regression-cases/${c.id}`, {method:'PUT', headers:{'Content-Type':'application/json'}, body: JSON.stringify(payload)});
            Object.assign(target, this.regressionCaseFromApi(saved));
            updated++;
          } else {
            const saved = await this.api('/api/quality/regression-cases', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(payload)});
            this.debugCases.unshift(this.regressionCaseFromApi(saved));
            imported++;
          }
        }
        this.showToast(`已导入 ${imported} 条、更新 ${updated} 条回归用例`);
      } catch (err) { this.showToast('导入失败：请检查 JSON 格式'); }
      e.target.value = '';
    },
    regressionCasePayload(c) {
      return {
        name:c.name, message:c.message, history:c.history || '', expectedReplyType:c.expected || c.expectedReplyType || 'AI',
        expectedAnswerPoints:c.expectedAnswerPoints || '', forbiddenAnswerPoints:c.forbiddenAnswerPoints || '',
        expectedAuthorityDomain:c.expectedAuthorityDomain || '', expectedAuthoritySource:c.expectedAuthoritySource || '',
        expectedModelInvoked:c.expectedModelInvoked == null ? null : !!c.expectedModelInvoked, enabled:true,
      };
    },
    regressionCaseFromApi(saved) {
      return {
        id:saved.id, name:saved.name, message:saved.message, history:saved.history || '', expected:saved.expectedReplyType || 'AI',
        expectedAnswerPoints:saved.expectedAnswerPoints || '', forbiddenAnswerPoints:saved.forbiddenAnswerPoints || '',
        expectedAuthorityDomain:saved.expectedAuthorityDomain || '', expectedAuthoritySource:saved.expectedAuthoritySource || '',
        expectedModelInvoked:saved.expectedModelInvoked == null ? null : !!saved.expectedModelInvoked,
        actual:'', actualAnswer:'', assertionFailures:'', passed:null,
      };
    },
    regressionAssertionSummary(c) {
      let count = 0;
      if (c.expectedAnswerPoints) count++;
      if (c.forbiddenAnswerPoints) count++;
      if (c.expectedAuthorityDomain || c.expectedAuthoritySource) count++;
      if (c.expectedModelInvoked != null) count++;
      return count ? `${count} 类附加断言` : '仅检查路由（点击补充）';
    },
    configVal(k) { const it = this.configItems.find(x => x.key === k); return it ? it.value : ''; },
    barWidth(v) {
      const max = Math.max(...this.daily.map(d => d.user_messages), 1);
      return Math.round(v / max * 100);
    },
    typeName(t) {
      return { SAFETY: '情绪安全', INTRO: 'AI首次项目介绍', FACT: '结构化事实', FAQ: 'FAQ直回', AGENT: '业务工具', CLARIFY: '追问澄清', AI: 'AI生成', REFUSAL: '兜底回复', ERROR: '服务异常', UNKNOWN: '未记录' }[t] || t;
    },

    async loadKb() {
      this.kb = await this.api('/knowledge/list');
      this.clampListPage('kbPage', this.knowledgeDocuments.length, this.listPageSize);
      const savedTaskId = localStorage.getItem('aics_kb_reindex_task');
      if (savedTaskId && !this.kbReindexPolling) this.pollKnowledgeReindex(savedTaskId);
    },
    async reindexKb() {
      if (this.kbReindexRunning) return;
      const warning = '系统会把当前账号可管理范围内的已发布客户知识发送给当前配置的向量模型。全部向量生成成功后才替换旧索引，失败时旧索引会继续可用。';
      if (!await this.askConfirm(warning, '重建全部知识向量？')) return;
      try {
        const r = await this.api('/knowledge/reindex', { method: 'POST' });
        this.kbReindexTask = { id:r.taskId, status:r.status || 'queued', total_chunks:0, processed_chunks:0 };
        localStorage.setItem('aics_kb_reindex_task', String(r.taskId));
        this.showToast(r.message || '已提交知识向量重建任务');
        await this.pollKnowledgeReindex(r.taskId);
      } catch (e) {
        this.showToast('重建提交失败：' + (e && e.message ? e.message : e));
      }
    },

    // ===== 结构化事实维护（业务唯一事实源） =====
    async loadFacts() {
      this.facts = await this.api('/knowledge/facts');
    },
    factStatusLabel(s) {
      return { draft: '草稿', published: '已发布', disabled: '已停用' }[s] || s || '—';
    },
    factValidity(f) {
      const from = f.valid_from ? String(f.valid_from).slice(0, 16).replace('T', ' ') : '';
      const until = f.valid_until ? String(f.valid_until).slice(0, 16).replace('T', ' ') : '';
      if (!from && !until) return '长期有效';
      return (from || '即日起') + ' ~ ' + (until || '长期');
    },
    openFactEditor(f) {
      // datetime-local 需要 yyyy-MM-ddTHH:mm 格式，其余字段浅拷贝避免直接改列表
      const norm = v => (v ? String(v).slice(0, 16) : '');
      this.factEdit = {
        ...f,
        valid_from: norm(f.valid_from),
        valid_until: norm(f.valid_until),
      };
    },
    async saveFact() {
      const f = this.factEdit;
      if (!f) return;
      if (!(f.fact_type || '').trim()) { this.showToast('请填写事实类型'); return; }
      if (f.status === 'published' && !(f.display_text || '').trim()) {
        this.showToast('发布状态必须填写对客户展示的内容');
        return;
      }
      if (f.valid_from && f.valid_until && f.valid_until <= f.valid_from) {
        this.showToast('失效时间必须晚于生效时间');
        return;
      }
      const payload = {
        project: (f.project || '').trim() || null,
        factType: (f.fact_type || '').trim(),
        displayText: (f.display_text || '').trim() || null,
        status: f.status,
        source: (f.source || '').trim() || null,
        reviewer: (f.reviewer || '').trim() || null,
        validFrom: f.valid_from || null,
        validUntil: f.valid_until || null,
        tenantId: f.tenant_id != null && f.tenant_id !== '' ? Number(f.tenant_id) : null,
        kfAccountId: f.kf_account_id != null && f.kf_account_id !== '' ? Number(f.kf_account_id) : null,
      };
      if (!await this.askConfirm('保存后立即按新内容生效（发布状态会直接作为 AI 回答依据）。确认保存？', '保存事实')) return;
      this.factSaving = true;
      try {
        await this.api('/knowledge/facts/' + encodeURIComponent(f.fact_key), {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        });
        this.showToast('事实已保存并生效');
        this.factEdit = null;
        this.loadFacts();
      } catch (e) {
        this.showToast('保存失败：' + (e && e.message ? e.message : e));
      } finally {
        this.factSaving = false;
      }
    },
    async pollKnowledgeReindex(taskId) {
      if (this.kbReindexPolling) return;
      this.kbReindexPolling = true;
      try {
        for (let i = 0; i < 1200; i++) {
          const task = await this.api('/knowledge/reindex/status?taskId=' + encodeURIComponent(taskId));
          if (!task || task.found === false) throw new Error('找不到知识向量重建任务');
          this.kbReindexTask = task;
          if (task.status === 'done') {
            localStorage.removeItem('aics_kb_reindex_task');
            this.showToast('知识向量重建完成，共 ' + Number(task.processed_chunks || 0) + ' 片 ✓');
            await this.loadKb();
            return;
          }
          if (task.status === 'failed') {
            localStorage.removeItem('aics_kb_reindex_task');
            this.showToast('重建失败，旧索引未被替换：' + (task.error || '未知错误'));
            return;
          }
          await new Promise(resolve => setTimeout(resolve, 1500));
        }
        this.showToast('重建任务仍在后台执行，可稍后返回知识库页面查看');
      } catch (e) {
        this.showToast('获取重建进度失败：' + (e && e.message ? e.message : e));
      } finally {
        this.kbReindexPolling = false;
      }
    },
    async uploadKb() {
      const f = this.$refs.kbFile.files[0];
      if (!f) return this.showToast('请先选择文件');
      this.uploadingKb = true;
      try {
        const fd = new FormData(); fd.append('file', f);
        const scope = this.resourceScopePayload();
        if (scope.tenantId) fd.append('tenantId', String(scope.tenantId));
        if (scope.kfAccountId) fd.append('kfAccountId', String(scope.kfAccountId));
        const r = await this.api('/knowledge/upload/async', { method: 'POST', body: fd });
        this.showToast('已提交《' + (r.docName || '') + '》，正在后台处理…');
        this.pollImport(r.taskId);
      } finally {
        this.uploadingKb = false;
      }
    },
    // 轮询异步导入任务：每 2 秒查一次，done/failed 后刷新列表（最多等 2 分钟）。
    // 连续提交多个文件时以最新一次为准（epoch 递增让旧循环退出），避免多个循环叠加造成提示错乱。
    async pollImport(taskId) {
      const epoch = this._importPollEpoch = (this._importPollEpoch || 0) + 1;
      for (let i = 0; i < 60; i++) {
        if (epoch !== this._importPollEpoch) return;   // 已有更新的导入在轮询
        await new Promise(res => setTimeout(res, 2000));
        if (epoch !== this._importPollEpoch) return;
        let st;
        try {
          st = await this.api('/knowledge/import/status?taskId=' + taskId);
        } catch (e) {
          this.showToast('查询导入状态失败，请稍后手动刷新'); return;
        }
        if (st.status === 'done') {
          this.showToast('《' + (st.file_name || '') + '》入库成功，共 ' + (st.chunk_count || 0) + ' 片');
          this.loadKb(); return;
        }
        if (st.status === 'failed') {
          this.showToast('《' + (st.file_name || '') + '》导入失败：' + (st.error || '未知原因'));
          return;
        }
      }
      this.showToast('处理超时，请稍后在列表查看'); this.loadKb();
    },
    async deleteKb(document) {
      const name = document.doc_name || '';
      if (!await this.askConfirm(`删除后《${name}》的全部知识切片将无法恢复。`, '删除知识库文档？')) return;
      const url = document.id ? '/knowledge/document/' + document.id : '/knowledge/doc?docName=' + encodeURIComponent(name);
      await this.api(url, { method: 'DELETE' });
      this.showToast('已删除'); this.loadKb();
    },

    async openKbEditor(document, page = 1) {
      this.kbEditDocument = document;
      this.kbEditDoc = document.doc_name || String(document || '');
      this.kbChunkPage = page;
      const url = document.id ? '/knowledge/document/' + document.id + '/chunks' : '/knowledge/chunks?docName=' + encodeURIComponent(this.kbEditDoc);
      const chunks = await this.api(url);
      this.kbChunks = (Array.isArray(chunks) ? chunks : []).map(c => this.decorateKbChunk(c));
      this.kbPreviewId = '';
      this.closeKbTest();
      this.clampListPage('kbChunkPage', this.kbChunks.length, this.tallListPageSize);
    },
    closeKbEditor() {
      this.kbEditDoc = '';
      this.kbEditDocument = null;
      this.kbChunks = [];
      this.kbPreviewId = '';
      this.closeKbTest();
    },
    parseKbFaq(content) {
      const lines = String(content || '').split(/\r?\n/);
      const questionIndex = lines.findIndex(line => line.startsWith('问：'));
      const answerIndex = lines.findIndex(line => line.startsWith('答：'));
      let categoryIndex = -1;
      for (let i = lines.length - 1; i >= 0; i--) {
        if (lines[i].startsWith('分类：')) { categoryIndex = i; break; }
      }
      const question = questionIndex >= 0 ? lines[questionIndex].slice(2).trim() : '';
      let answer = '';
      if (answerIndex >= 0) {
        const end = categoryIndex > answerIndex ? categoryIndex : lines.length;
        const answerLines = lines.slice(answerIndex, end);
        answerLines[0] = answerLines[0].slice(2);
        answer = answerLines.join('\n').trim();
      }
      const category = categoryIndex >= 0 ? lines[categoryIndex].slice(3).trim() : '';
      return { question, answer, category };
    },
    decorateKbChunk(chunk) {
      const item = Object.assign({}, chunk, { _original_content: String(chunk.content || '') });
      if (item.knowledge_type === 'faq') {
        const faq = this.parseKbFaq(item.content);
        item.faq_question = faq.question;
        item.faq_answer = faq.answer;
        item.faq_category = faq.category;
      }
      return item;
    },
    kbChunkContent(c) {
      if (c.knowledge_type !== 'faq') return String(c.content || '');
      const question = String(c.faq_question || '').trim();
      const answer = String(c.faq_answer || '').trim();
      const category = String(c.faq_category || '').trim();
      return '问：' + question + '\n答：' + answer + (category ? '\n分类：' + category : '');
    },
    kbChunkDirty(c) {
      return this.kbChunkContent(c) !== String(c._original_content || '');
    },
    // 知识库内容检索：按关键词即时查找切片（后端 LIKE，跨发布状态）
    async searchKbContent() {
      const q = (this.kbSearch || '').trim();
      if (!q) return this.showToast('请输入要搜索的关键词');
      this.kbSearching = true;
      try {
        const res = await this.api('/knowledge/search-content?q=' + encodeURIComponent(q) + '&limit=100');
        this.kbSearchResults = Array.isArray(res) ? res : [];
        if (!this.kbSearchResults.length) this.showToast('没有匹配的知识切片');
      } catch (e) {
        this.showToast('搜索失败：' + (e && e.message ? e.message : e));
      } finally {
        this.kbSearching = false;
      }
    },
    clearKbSearch() {
      this.kbSearchResults = [];
      this.kbSearch = '';
    },
    // 点击命中项：打开其所属文档的切片编辑器，并定位到命中的那一片。
    // 注意：不要清空 kbSearchResults——否则关闭编辑器后会掉回未搜索的文档列表；
    // 保留它，关闭弹窗后用户仍停留在搜索结果界面。
    async jumpToSearchChunk(r) {
      const page = Math.max(1, Math.floor((Number(r.chunk_index) - 1) / this.tallListPageSize) + 1);
      await this.openKbEditor({ doc_name: r.doc_name }, page);
    },
    docStatusLabel(s) {
      return ({ published: '已发布', draft: '草稿', disabled: '已停用' })[s] || s || '已发布';
    },
    // 关键词高亮：先转义 HTML 再包 <mark>，避免 v-html 注入
    kbHighlight(content) {
      const q = (this.kbSearch || '').trim();
      const text = String(content == null ? '' : content);
      const esc = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      if (!q) return esc;
      const safeQ = q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      try {
        return esc.replace(new RegExp('(' + safeQ + ')', 'gi'), '<mark>$1</mark>');
      } catch (e) {
        return esc;
      }
    },
    // 编辑框行数：按内容长度估算（40 字符/行），最长 16 行内部滚动，不用手动拖窗口
    kbRows(text) {
      const lines = (text || '').split('\n').length;
      const est = Math.ceil((text || '').length / 40);
      return Math.min(16, Math.max(4, Math.max(lines + 1, est)));
    },
    async saveChunk(c) {
      const content = this.kbChunkContent(c);
      if (c.knowledge_type === 'faq' && !String(c.faq_question || '').trim()) return this.showToast('请填写客户常见问题');
      if (c.knowledge_type === 'faq' && !String(c.faq_answer || '').trim()) return this.showToast('请填写标准回答');
      c.content = content;
      const wasGoverned = !!c.governance_reason;
      const result = await this.api('/knowledge/chunk/' + c.id, {
        method: 'PUT', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ content }),
      });
      if (result.governed) {
        c.status = 'disabled'; c.has_embedding = false;
        c.embedding_strategy = 'dynamic-price-no-vector-v1';
        c.governance_reason = result.governanceReason || '动态价格内容已自动停用';
        c.governance_override = false;
        this.showToast('内容已保存，并因包含动态价格自动退出客户检索');
      } else {
        if (wasGoverned) c.status = 'published';
        c.has_embedding = true; c.governance_reason = null;
        c.governance_override = false;
        c.embedding_strategy = c.knowledge_type === 'faq' ? 'faq-question-v1' : 'content-v1';
        this.showToast(wasGoverned ? '动态价格已移除，内容已重新发布并生成向量' : '已保存并重新向量化');
      }
      c._original_content = content;
      if (this.kbTestChunkId === c.id) {
        this.kbTestResult = null;
        this.kbTestError = '';
      }
      // 保存后停留在编辑态（方便连续修改多条），不重载列表（内容已 v-model 同步，避免滚动重置）
      this.loadKb();
    },
    async setChunkStatus(c, status) {
      if (this.kbChunkDirty(c)) return this.showToast('请先保存当前修改，再调整启用状态');
      const enabling = status === 'published';
      const governed = !!c.governance_reason;
      const message = enabling && governed
        ? '这条内容包含动态价格或金额。启用后会重新生成向量并参与客户回答，可能与项目中心的实时价格冲突。确认仍要人工启用吗？'
        : (enabling ? '确认启用这条知识内容并让它参与客户检索吗？' : '确认停用这条知识内容吗？停用后将不再参与客户检索。');
      if (!await this.askConfirm(message, enabling && governed ? '人工启用动态价格内容？' : (enabling ? '启用知识内容？' : '停用知识内容？'))) return;
      const result = await this.api('/knowledge/chunk/' + c.id + '/status', {
        method: 'PUT', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
          status,
          acknowledgeGovernanceRisk: enabling && governed,
        }),
      });
      c.status = result.status;
      c.governance_override = !!result.governanceOverride;
      c.governance_reason = result.governanceReason || c.governance_reason || null;
      c.has_embedding = !!result.hasEmbedding;
      this.showToast(result.governanceOverride ? '已人工启用，该内容现在会参与客户检索' : (enabling ? '知识内容已启用' : '知识内容已停用'));
      this.loadKb();
    },
    kbDefaultTestQuestion(c) {
      if (c.knowledge_type === 'faq' && String(c.faq_question || '').trim()) return String(c.faq_question).trim();
      if (c.project) return '我想了解' + c.project;
      if (c.category) return '我想了解' + c.category;
      const heading = String(c.content || '').match(/^#{1,6}\s+(.+)$/m);
      return heading ? '我想了解' + heading[1].trim() : '';
    },
    openKbTest(c) {
      if (this.kbTestChunkId === c.id) return this.closeKbTest();
      this.kbTestChunkId = c.id;
      this.kbTestQuestion = this.kbDefaultTestQuestion(c);
      this.kbTestResult = null;
      this.kbTestError = '';
    },
    closeKbTest() {
      this.kbTestRequestId++;
      this.kbTestChunkId = null;
      this.kbTestQuestion = '';
      this.kbTestResult = null;
      this.kbTestError = '';
      this.kbTestRunning = false;
    },
    async runKbChunkTest(c, runModel) {
      if (this.kbChunkDirty(c)) return this.showToast('测试使用已保存内容，请先保存当前修改');
      const message = String(this.kbTestQuestion || '').trim();
      if (!message) return this.showToast('请输入模拟客户问题');
      this.kbTestRunning = true;
      this.kbTestResult = null;
      this.kbTestError = '';
      const requestId = ++this.kbTestRequestId;
      try {
        const result = await this.callDebug({
          message, history:'', profile:'', lat:null, lng:null,
          relevanceThreshold:null, faqDirectThreshold:null, tone:null, refusalAnswer:null,
          projectIntroAiEnabled:null, projectIntroRequireBothImages:null,
          projectIntroDecisionPrompt:null, runModel:!!runModel,
        });
        if (requestId === this.kbTestRequestId) this.kbTestResult = result;
      } catch (e) {
        if (requestId === this.kbTestRequestId) this.kbTestError = '测试失败：' + (e && e.message ? e.message : e);
      } finally {
        if (requestId === this.kbTestRequestId) this.kbTestRunning = false;
      }
    },
    openKbTestInLab() {
      const question = this.kbTestQuestion;
      const result = this.kbTestResult;
      this.closeKbEditor();
      this.debugMsg = question;
      this.debugResult = result;
      this.debugSection = 'playground';
      this.debugInspector = 'retrieval';
      this.switchTab('aidev');
    },
    // 轻量 Markdown 预览渲染：先转义 HTML 再套用基础标签，避免 v-html 注入风险。
    // 仅用于后台知识库编辑器预览（信任内容来自管理员编辑），支持标题/列表/表格/加粗/斜体/代码/段落。
    renderMd(src) {
      if (!src) return '<span class="muted">（空内容）</span>';
      const esc = (t) => String(t).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      const inline = (t) => esc(t)
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
        .replace(/\*([^*]+)\*/g, '<em>$1</em>')
        .replace(/`([^`]+)`/g, '<code>$1</code>');
      const lines = String(src).split(/\r?\n/);
      let html = '', i = 0, inList = false, inTable = false;
      const closeList = () => { if (inList) { html += '</ul>'; inList = false; } };
      const closeTable = () => { if (inTable) { html += '</tbody></table>'; inTable = false; } };
      const splitRow = (row) => row.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim());
      const isSep = (row) => { const cs = splitRow(row); return cs.length > 0 && cs.every((c) => /^:?-{2,}:?$/.test(c)); };
      while (i < lines.length) {
        const line = lines[i];
        // 表格：表头行 + 分隔行（|---|---| 等）
        if (line.includes('|') && i + 1 < lines.length && isSep(lines[i + 1])) {
          closeList();
          const head = splitRow(line);
          let t = '<table><thead><tr>' + head.map((c) => '<th>' + inline(c) + '</th>').join('') + '</tr></thead><tbody>';
          i += 2;
          while (i < lines.length && lines[i].includes('|')) {
            const cells = splitRow(lines[i]);
            t += '<tr>' + cells.map((c) => '<td>' + inline(c) + '</td>').join('') + '</tr>';
            i++;
          }
          t += '</tbody>';
          html += t; inTable = true; continue;
        }
        const h = line.match(/^(#{1,6})\s+(.*)$/);
        if (h) { closeList(); closeTable(); html += '<h' + h[1].length + '>' + inline(h[2]) + '</h' + h[1].length + '>'; i++; continue; }
        const li = line.match(/^\s*[-*]\s+(.*)$/);
        if (li) { closeTable(); if (!inList) { html += '<ul>'; inList = true; } html += '<li>' + inline(li[1]) + '</li>'; i++; continue; }
        if (line.trim() === '') { closeList(); closeTable(); i++; continue; }
        closeList(); closeTable();
        html += '<p>' + inline(line) + '</p>';
        i++;
      }
      closeList(); closeTable();
      return html;
    },
    async deleteChunk(id) {
      if (!await this.askConfirm('该知识切片删除后无法恢复。', '删除这条知识？')) return;
      await this.api('/knowledge/chunk/' + id, { method: 'DELETE' });
      const page = this.kbChunkPage;
      this.showToast('已删除'); this.openKbEditor(this.kbEditDocument || {doc_name:this.kbEditDoc}, page); this.loadKb();
    },

    blankProject() {
      return {
        id:null, parentId:'', tenantId:null, kfAccountId:null, code:'', name:'', serviceScope:'', status:'published', sortOrder:100, aliasText:'', imageIds:[], storeIds:[],
        offer:{ activityPrice:'', originalPrice:'', deposit:'', briefCopy:'', saleCopy:'', status:'published' },
      };
    },
    async loadProjectOptions() {
      const result = await this.api('/projects/options');
      this.projectOptionNames = result.projects || [];
    },
    async loadRootProjectOptions() {
      const result = await this.api('/projects/root-options');
      this.rootProjectOptions = result.projects || [];
    },
    async loadProjectWorkspace() {
      const [projects, resources] = await Promise.all([this.api('/projects'), this.api('/projects/resources')]);
      this.serviceProjects = projects || [];
      this.projectOptionNames = this.serviceProjects.filter(project => project.status === 'published').map(project => project.name);
      this.projectImages = resources.images || [];
      this.projectStores = resources.stores || [];
    },
    resetProjectEditorUi() {
      this.projectEditorTab = 'basic';
      this.projectAliasInput = '';
      this.projectImageSearch = '';
      this.projectStoreSearch = '';
      this.projectStoreCity = '';
      this.projectStoreDistrict = '';
    },
    focusProjectName() {
      this.$nextTick(() => {
        const input = this.$refs.projectNameInput;
        if (input && typeof input.focus === 'function') input.focus();
      });
    },
    openNewProject() {
      this.projectDraft = this.blankProject();
      this.resetProjectEditorUi();
      this.projectDialog = true;
      this.focusProjectName();
    },
    startProjectEdit(project) {
      const offer = project.offer || {};
      this.projectDraft = {
        id:project.id, parentId:project.parentId ? String(project.parentId) : '', tenantId:project.tenantId || null, kfAccountId:project.kfAccountId || null,
        code:project.code || '', name:project.name || '', serviceScope:project.serviceScope || '', status:project.status || 'draft', sortOrder:Number(project.sortOrder || 0),
        aliasText:(project.aliases || []).filter(alias => alias !== project.name).join('\n'),
        imageIds:(project.imageIds || []).map(Number), storeIds:(project.storeIds || []).map(Number),
        offer:{
          activityPrice:offer.activityPrice == null ? '' : String(offer.activityPrice),
          originalPrice:offer.originalPrice == null ? '' : String(offer.originalPrice),
          deposit:offer.deposit == null ? '' : String(offer.deposit),
          briefCopy:offer.briefCopy || '', saleCopy:offer.saleCopy || '', status:offer.status || 'draft',
        },
      };
      this.resetProjectEditorUi();
      this.projectDialog = true;
      this.focusProjectName();
    },
    closeProjectEditor() {
      if (this.projectSaving) return;
      this.projectDialog = false;
      this.projectAliasInput = '';
    },
    addProjectAlias() {
      if (!this.projectDraft) return;
      const alias = String(this.projectAliasInput || '').trim();
      if (!alias) return;
      if (alias === String(this.projectDraft.name || '').trim()) {
        this.showToast('项目名称本身无需重复添加为别名');
        return;
      }
      if (this.projectAliasList.includes(alias)) {
        this.showToast('这个别名已经添加过了');
        return;
      }
      this.projectDraft.aliasText = [...this.projectAliasList, alias].join('\n');
      this.projectAliasInput = '';
    },
    removeProjectAlias(alias) {
      if (!this.projectDraft) return;
      this.projectDraft.aliasText = this.projectAliasList.filter(value => value !== alias).join('\n');
    },
    selectFilteredProjectImages() {
      if (!this.projectDraft) return;
      const selected = new Set((this.projectDraft.imageIds || []).map(Number));
      this.filteredProjectImages.filter(img => img.enabled !== false).forEach(img => selected.add(Number(img.id)));
      this.projectDraft.imageIds = [...selected];
    },
    selectFilteredProjectStores() {
      if (!this.projectDraft) return;
      const selected = new Set((this.projectDraft.storeIds || []).map(Number));
      this.filteredProjectStores.forEach(store => selected.add(Number(store.id)));
      this.projectDraft.storeIds = [...selected];
    },
    clearFilteredProjectStores() {
      if (!this.projectDraft) return;
      const currentIds = new Set(this.filteredProjectStores.map(store => Number(store.id)));
      this.projectDraft.storeIds = (this.projectDraft.storeIds || [])
        .map(Number).filter(id => !currentIds.has(id));
    },
    onProjectStoreCityChange() {
      if (!this.projectStoreDistricts.includes(this.projectStoreDistrict)) {
        this.projectStoreDistrict = '';
      }
    },
    projectStoreCountByCity(city) {
      return (this.projectStores || []).filter(store => String(store.city || '').trim() === city).length;
    },
    projectStoreCountByDistrict(district) {
      return (this.projectStores || []).filter(store =>
        String(store.city || '').trim() === this.projectStoreCity
        && String(store.district || '').trim() === district).length;
    },
    numberOrNull(value) {
      if (value == null || String(value).trim() === '') return null;
      const number = Number(value);
      return Number.isFinite(number) ? number : null;
    },
    async saveProject() {
      const draft = this.projectDraft;
      if (!draft || !String(draft.name || '').trim()) {
        this.projectEditorTab = 'basic';
        this.showToast('请填写项目名称');
        this.focusProjectName();
        return;
      }
      const sortOrder = Number(draft.sortOrder || 0);
      if (!Number.isInteger(sortOrder) || sortOrder < 0 || sortOrder > 100000) {
        this.projectEditorTab = 'basic';
        return this.showToast('展示排序必须是 0 到 100000 之间的整数');
      }
      const code = String(draft.code || '').trim();
      if (code && !/^[A-Za-z0-9._-]{2,80}$/.test(code)) {
        this.projectEditorTab = 'basic';
        return this.showToast('项目编码只能包含字母、数字、点、下划线和短横线');
      }
      const activityPrice = this.numberOrNull(draft.offer.activityPrice);
      const originalPrice = this.numberOrNull(draft.offer.originalPrice);
      const deposit = this.numberOrNull(draft.offer.deposit);
      if ([activityPrice, originalPrice, deposit].some(value => value != null && value < 0)) {
        this.projectEditorTab = 'offer';
        return this.showToast('价格不能小于 0');
      }
      if (deposit != null && activityPrice != null && deposit > activityPrice) {
        this.projectEditorTab = 'offer';
        return this.showToast('订金不能大于活动价');
      }
      this.projectSaving = true;
      try {
        const payload = {
          parentId:draft.parentId ? Number(draft.parentId) : null,
          code, name:String(draft.name || '').trim(), serviceScope:String(draft.serviceScope || '').trim(), status:draft.status,
          sortOrder,
          aliases:String(draft.aliasText || '').split(/[\n,，、]+/).map(value => value.trim()).filter(Boolean),
          offer:{
            activityPrice, originalPrice, deposit, briefCopy:draft.offer.briefCopy || '', saleCopy:draft.offer.saleCopy || '',
            status:draft.offer.status || 'draft',
          },
          imageIds:(draft.imageIds || []).map(Number), storeIds:(draft.storeIds || []).map(Number),
        };
        if (!draft.id) Object.assign(payload, this.resourceScopePayload());
        await this.api(draft.id ? '/projects/' + draft.id : '/projects', {
          method:draft.id ? 'PUT' : 'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(payload),
        });
        this.projectDialog = false;
        this.showToast('项目已保存，首页、识别、价格、图片和门店已同步');
        await this.loadProjectWorkspace();
      } finally {
        this.projectSaving = false;
      }
    },
    async disableProject(project) {
      const children = this.projectChildCount(project.id);
      const detail = children > 0
        ? '该主项目下有 ' + children + ' 个子项目，停用后这些子项目也会一并停用。历史价格和素材不会删除。'
        : '停用后客户侧不再展示或匹配该项目，历史价格和素材不会删除。';
      if (!await this.askConfirm(detail, '停用项目“' + project.name + '”？')) return;
      await this.api('/projects/' + project.id, {method:'DELETE'});
      this.showToast(children > 0 ? '主项目及其子项目已停用' : '项目已停用');
      await this.loadProjectWorkspace();
    },
    projectChildCount(projectId) {
      return (this.serviceProjects || []).filter(project =>
        Number(project.parentId || 0) === Number(projectId)).length;
    },
    projectStatusLabel(status) { return ({draft:'草稿',published:'已发布',disabled:'已停用'})[status] || status; },
    toggleStoreProject(project) {
      const values = this.storeProjectValues.slice();
      const idx = values.indexOf(project);
      if (idx >= 0) values.splice(idx, 1);
      else values.push(project);
      this.editStore.projects = values.join(',');
    },

    async loadImages() {
      this.images = await this.api('/images/list');
      this.clampListPage('imgPage', this.filteredImages.length, this.listPageSize);
    },
    async uploadImg() {
      const f = this.$refs.imgFile.files[0];
      if (!f) return this.showToast('请先选择图片');
      if (!this.imgProject) return this.showToast('请选择图片所属项目');
      this.uploadingImg = true;
      try {
        const fd = new FormData();
        fd.append('file', f); fd.append('name', this.imgName); fd.append('tags', this.imgTags);
        fd.append('project', this.imgProject); fd.append('assetType', this.imgType);
        fd.append('enabled', String(this.imgEnabled));
        fd.append('validFrom', this.imgType === 'promo' ? this.imgValidFrom : '');
        fd.append('validUntil', this.imgType === 'promo' ? this.imgValidUntil : '');
        const scope = this.resourceScopePayload();
        if (scope.tenantId) fd.append('tenantId', String(scope.tenantId));
        if (scope.kfAccountId) fd.append('kfAccountId', String(scope.kfAccountId));
        await this.api('/images/upload', { method: 'POST', body: fd });
        this.imgName = this.imgTags = ''; this.imgProject = ''; this.imgType = 'effect';
        this.imgEnabled = true; this.imgValidFrom = this.imgValidUntil = ''; this.$refs.imgFile.value = '';
        this.showToast('图片已上传'); this.loadImages();
      } finally {
        this.uploadingImg = false;
      }
    },
    async deleteImg(id) {
      if (!await this.askConfirm('删除后客服将无法继续使用这张图片。', '删除图片素材？')) return;
      await this.api('/images?id=' + id, { method: 'DELETE' });
      this.showToast('已删除'); this.loadImages();
    },

    async loadStores() {
      const params = [];
      if (this.storeFilter) params.push('city=' + encodeURIComponent(this.storeFilter));
      if (this.storeAddress) params.push('address=' + encodeURIComponent(this.storeAddress));
      const qs = params.length ? '?' + params.join('&') : '';
      this.stores = await this.api('/stores' + qs);
      this.clampListPage('storePage', this.stores.length, this.listPageSize);
    },
    // 门店城市过滤 300ms 防抖，避免每敲一个字母就发一次请求
    debouncedLoadStores() {
      if (this.storeTimer) clearTimeout(this.storeTimer);
      this.storePage = 1;
      this.storeTimer = setTimeout(() => this.loadStores(), 300);
    },
    // 图片编辑：打开弹窗并预填
    startImgEdit(img) {
      this.imgEditId = img.id;
      this.imgEditPreview = img.url || '';
      this.imgEditName = img.name || '';
      this.imgEditTags = img.tags || '';
      this.imgEditProject = img.project || '';
      this.imgEditType = img.asset_type || 'effect';
      this.imgEditEnabled = img.enabled !== false;
      this.imgEditValidFrom = this.toDateTimeLocal(img.valid_from);
      this.imgEditValidUntil = this.toDateTimeLocal(img.valid_until);
    },
    // 图片编辑：保存名称/标签（PUT /images/{id}）
    async saveImgEdit() {
      if (!this.imgEditId) return;
      await this.api('/images/' + this.imgEditId, {
        method: 'PUT', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
          name: this.imgEditName, tags: this.imgEditTags, project: this.imgEditProject,
          assetType: this.imgEditType, enabled: this.imgEditEnabled,
          validFrom: this.imgEditType === 'promo' ? this.imgEditValidFrom : '',
          validUntil: this.imgEditType === 'promo' ? this.imgEditValidUntil : '',
        }),
      });
      this.cancelImgEdit();
      this.showToast('已保存'); this.loadImages();
    },
    cancelImgEdit() {
      this.imgEditId = null; this.imgEditPreview = ''; this.imgEditName = ''; this.imgEditTags = ''; this.imgEditProject = '';
      this.imgEditType = 'effect'; this.imgEditEnabled = true;
      this.imgEditValidFrom = ''; this.imgEditValidUntil = '';
    },
    toDateTimeLocal(value) {
      const s = AICS.formatDateTime(value);
      return s ? s.replace(' ', 'T') : '';
    },
    imageValidityText(img) {
      const now = new Date();
      if (img.valid_from && new Date(img.valid_from) > now) return '未到生效时间';
      if (img.valid_until && new Date(img.valid_until) < now) return '已过期';
      const from = img.valid_from ? AICS.formatDate(img.valid_from) : '立即';
      const until = img.valid_until ? AICS.formatDate(img.valid_until) : '长期';
      return from + ' 至 ' + until;
    },
    blankStore() { return { city: '', district: '', name: '', address: '', lat: '', lng: '', projects: '' }; },
    // ===== P1-4 地图选点：所见即所得校验/修正门店坐标 =====
    async openMapPicker() {
      try {
        const r = await this.api('/stores/map-key');
        if (!r.configured || !r.key) {
          this.showToast('未配置腾讯地图 key（设置页 map.tencent_key）');
          return;
        }
        this.mapPicked = { lat: '', lng: '' };
        this.mapDialog = true;
        this.$nextTick(() => this.initPickerMap(r.key));
      } catch (e) {
        this.showToast('地图配置加载失败');
      }
    },
    loadMapScript(key) {
      return new Promise((resolve, reject) => {
        if (window.TMap) { resolve(); return; }
        const s = document.createElement('script');
        s.src = 'https://map.qq.com/api/gljs?v=1.exp&key=' + encodeURIComponent(key);
        s.onload = resolve;
        s.onerror = () => reject(new Error('地图脚本加载失败，请检查 key 的 JS SDK 域名白名单'));
        document.head.appendChild(s);
      });
    },
    async initPickerMap(key) {
      try {
        await this.loadMapScript(key);
        const TMap = window.TMap;
        const hasPoint = parseFloat(this.editStore.lat) && parseFloat(this.editStore.lng);
        const center = new TMap.LatLng(
          parseFloat(this.editStore.lat) || 39.908823,
          parseFloat(this.editStore.lng) || 116.397470);
        const el = this.$refs.pickerMap;
        this._pickerMap = new TMap.Map(el, { center, zoom: hasPoint ? 15 : 10 });
        this._pickerMarker = new TMap.MultiMarker({ map: this._pickerMap, geometries: [] });
        if (hasPoint) {
          // 编辑已有门店：把已存坐标先摆上地图，方便核对是否准确
          this.mapPicked = {
            lat: parseFloat(this.editStore.lat).toFixed(6),
            lng: parseFloat(this.editStore.lng).toFixed(6),
          };
          this.setPickerPoint(center);
        }
        this._pickerMap.on('click', (e) => this.setPickerPoint(e.latLng));
      } catch (e) {
        this.showToast(e.message || '地图初始化失败');
        this.mapDialog = false;
      }
    },
    setPickerPoint(latLng) {
      const TMap = window.TMap;
      const lat = latLng.getLat(), lng = latLng.getLng();
      this.mapPicked = { lat: lat.toFixed(6), lng: lng.toFixed(6) };
      this._pickerMarker.setGeometries([{ id: 'picked', position: new TMap.LatLng(lat, lng) }]);
    },
    async mapSearchPlace() {
      const addr = (this.mapSearch || '').trim();
      if (!addr || !window.TMap) return;
      this.mapSearching = true;
      try {
        const r = await this.api('/stores/geocode', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ address: addr }),
        });
        if (r && r.ok) {
          const TMap = window.TMap;
          const ll = new TMap.LatLng(r.lat, r.lng);
          this._pickerMap.setCenter(ll);
          this._pickerMap.setZoom(15);
          this.setPickerPoint(ll);
        } else {
          this.showToast((r && r.error) || '未找到该地址');
        }
      } finally {
        this.mapSearching = false;
      }
    },
    applyMapPoint() {
      if (!this.mapPicked.lat || !this.mapPicked.lng) {
        this.showToast('请先在地图上点选门店位置');
        return;
      }
      this.editStore.lat = this.mapPicked.lat;
      this.editStore.lng = this.mapPicked.lng;
      this.closeMapPicker();
      this.showToast('已填入选点坐标');
    },
    closeMapPicker() {
      this.mapDialog = false;
      if (this._pickerMap) { try { this._pickerMap.destroy(); } catch (e) { /* 已销毁 */ } this._pickerMap = null; }
      this._pickerMarker = null;
    },
    // 输入地址 → 腾讯地图自动解析经纬度（填 lat/lng，保存时也可自动解析兜底）
    async autoLocate() {
      const addr = (this.editStore.address || '').trim();
      if (!addr) return this.showToast('请先填写详细地址');
      this.geocoding = true;
      try {
        const r = await this.api('/stores/geocode', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ address: addr, city: this.editStore.city || undefined }),
        });
        if (r && r.ok) {
          this.editStore.lat = String(r.lat);
          this.editStore.lng = String(r.lng);
          this.showToast('已自动定位 ✅ 经纬度已填入');
        } else {
          this.showToast((r && r.error) || '定位失败，请检查地址或地图 key 配置');
        }
      } catch (e) {
        this.showToast('定位失败，请稍后再试');
      } finally {
        this.geocoding = false;
      }
    },
    async saveStore() {
      const s = this.editStore;
      if (!s.city || !s.name || !s.address) return this.showToast('城市、门店名、地址必填');
      const method = s.id ? 'PUT' : 'POST';
      const url = s.id ? '/stores/' + s.id : '/stores';
      const payload = s.id ? {...s} : {...s, ...this.resourceScopePayload()};
      await this.api(url, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
      this.storeDialog = false; this.showToast('已保存'); this.loadStores();
    },
    async deleteStore(id) {
      if (!await this.askConfirm('删除后 AI 将不再向客户推荐这家门店。', '删除门店？')) return;
      await this.api('/stores/' + id, { method: 'DELETE' });
      this.showToast('已删除'); this.loadStores();
    },

    // 会话质检：默认加载最近会话（/api/conversations/query，管理鉴权），关键词+时间过滤+分页（W26-M2）
    async loadConvs(page) {
      this.convPage = page || 1;
      const params = new URLSearchParams();
      if (this.convKeyword && this.convKeyword.trim()) params.set('keyword', this.convKeyword.trim());
      if (this.convDays) params.set('days', this.convDays);
      params.set('page', this.convPage);
      params.set('pageSize', this.convPageSize);
      const qs = params.toString();
      const r = await this.api('/api/conversations/query' + (qs ? '?' + qs : ''));
      this.convs = (r && r.rows) || [];
      this.convTotal = (r && r.total) || 0;
      this.convDetail = [];
    },
    // AI 质量评测：评分和展示均为离线管理操作，不进入客户实时回复链路
    async loadQuality() {
      this.qualityLoading = true;
      try {
        await Promise.all([this.loadQualityJudgeModels(), this.loadQualityOverview(), this.loadQualityRows(this.qualityPage || 1), this.loadQualityRules()]);
        await this.resumeQualityBatch();
      } finally { this.qualityLoading = false; }
    },
    async loadQualityRules() {
      try {
        const list = await this.api('/api/quality/rules');
        if (Array.isArray(list) && list.length) this.qualityRulesList = list;
      } catch (e) { /* 后端未返回规则时保留前端兜底列表，不影响评分主流程 */ }
    },
    startEditRules() {
      this.qualityRulesDraft = (this.qualityRulesList || []).map(r => ({
        code: r.code, category: r.category, dimension: r.dimension, deduction: r.deduction,
        trigger: r.trigger, note: r.note,
        suggestion: r.suggestion == null ? '' : r.suggestion,
        enabled: r.enabled !== false,
        pattern: r.pattern == null ? null : r.pattern,
        allDeductions: (r.allDeductions && typeof r.allDeductions === 'object') ? Object.assign({}, r.allDeductions) : { [r.dimension]: r.deduction || 0 }
      }));
      this.qualityRulesEditMode = true;
    },
    cancelEditRules() {
      this.qualityRulesEditMode = false;
      this.qualityRulesDraft = [];
    },
    isRuntimeSuggestionRule(code) {
      return code === 'wrong_store_hours' || code === 'wrong_agent_hours';
    },
    async saveQualityRules() {
      this.qualityRulesSaving = true;
      try {
        const incoming = {};
        for (const r of this.qualityRulesDraft) {
          const cfg = { enabled: !!r.enabled, deductions: r.allDeductions || {} };
          if (!this.isRuntimeSuggestionRule(r.code)) cfg.suggestion = (r.suggestion == null ? '' : String(r.suggestion));
          if (r.pattern != null && String(r.pattern).trim() !== '') cfg.pattern = String(r.pattern).trim();
          incoming[r.code] = cfg;
        }
        await this.api('/api/quality/rules', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(incoming)
        });
        await this.loadQualityRules();
        this.qualityRulesEditMode = false;
        this.qualityRulesDraft = [];
        this.showToast('质量评分规则已保存，立即生效');
      } catch (e) { /* api 已提示错误 */ }
      finally { this.qualityRulesSaving = false; }
    },
    async loadQualityJudgeModels() {
      const result = await this.api('/api/quality/judge-models');
      this.qualityAiJudgeEnabled = result.aiJudgeEnabled === true;
      this.qualityJudgeModels = Array.isArray(result.models) && result.models.length
        ? result.models : [{provider:'rules',label:'仅规则风险扫描（非质量分）',displayName:'',modelName:''}];
      const currentAvailable = this.qualityJudgeModels.some(model => model.provider === this.qualityJudgeProvider);
      if (!currentAvailable) {
        const recommended = this.qualityAiJudgeEnabled && (this.qualityJudgeModels.find(model => model.provider === 'eval' && model.modelName)
          || this.qualityJudgeModels.find(model => model.provider !== 'rules' && model.modelName));
        this.qualityJudgeProvider = recommended ? recommended.provider : 'rules';
      }
      if (!this.qualityAiJudgeEnabled && this.qualityJudgeProvider !== 'rules') this.qualityJudgeProvider = 'rules';
    },
    async loadQualityOverview() {
      this.qualityOverview = await this.api('/api/quality/overview');
    },
    async loadQualityRows(page) {
      const target = Math.max(1, Number(page || 1));
      const params = new URLSearchParams({ page: target, pageSize: this.qualityPageSize });
      Object.entries(this.qualityFilter || {}).forEach(([key,value]) => {
        if (value !== '' && value != null) params.set(key, value);
      });
      const result = await this.api('/api/quality/evaluations?' + params.toString());
      this.qualityRows = Array.isArray(result.rows) ? result.rows : [];
      this.qualityTotal = Number(result.total || 0);
      this.qualityPage = Math.min(target, this.totalQualityPages);
    },
    async batchScoreQuality() {
      if (this.qualityScoring) return;
      const extra = this.qualityJudgeProvider === 'rules'
        ? '本次只执行确定性风险扫描，不消耗模型额度，也不会产生质量分。'
        : `本次只调用${this.qualityJudgeModelLabel({judgeProvider:this.qualityJudgeProvider})}进行 AI 语义复核，并升级已有规则扫描记录；不会自动切换模型。`;
      if (!await this.askConfirm(`将按配置上限评测最近的待处理完整会话；已完成语义评分或人工确认的记录不会被覆盖。${extra}`, '批量评测待处理会话？')) return;
      this.qualityScoring = true;
      try {
        const result = await this.api('/api/quality/evaluations/batch-score', {
          method:'POST', headers:{'Content-Type':'application/json'},
          body:JSON.stringify({ conversationIds:[], judgeProvider:this.qualityJudgeProvider }),
        });
        this.qualityBatchTask = result;
        this.showToast(`已提交后台评测任务 #${result.id}，可在当前页面查看进度`);
        this.pollQualityBatch(result.id);
      } catch (e) {
        this.qualityScoring = false;
      }
    },
    async resumeQualityBatch() {
      if (this.qualityBatchPolling) return;
      try {
        const task = await this.api('/api/quality/evaluations/batch-score/latest');
        if (!task || !task.found) return;
        this.qualityBatchTask = task;
        if (task.active) {
          this.qualityScoring = true;
          this.pollQualityBatch(task.id);
        }
      } catch (e) { /* 任务状态不影响质量报表主流程 */ }
    },
    async pollQualityBatch(taskId) {
      if (!taskId || this.qualityBatchPolling) return;
      this.qualityBatchPolling = true;
      this.qualityScoring = true;
      try {
        while (true) {
          const task = await this.api(`/api/quality/evaluations/batch-score/${taskId}`);
          if (!task || !task.found) throw new Error('评测任务不存在或无权查看');
          this.qualityBatchTask = task;
          if (!task.active) {
            const failures = Array.isArray(task.failures) ? task.failures : [];
            const firstFailure = failures.length ? failures[0].error : task.errorMessage || '';
            this.showToast(`评测${task.status==='succeeded'?'完成':'已结束'}：${task.succeededCount || 0} 条成功，${task.failedCount || 0} 条失败${firstFailure ? '；首个原因：' + firstFailure : ''}`);
            await Promise.all([this.loadQualityOverview(), this.loadQualityRows(this.qualityPage || 1)]);
            if (this.qualityDetailOpen && this.qualityDetail && this.qualityDetail.conversationId) {
              const detail = await this.api('/api/quality/evaluations/conversation/' + encodeURIComponent(this.qualityDetail.conversationId));
              this.qualityDetail = detail || {};
              this.prepareQualityReview();
            }
            break;
          }
          await new Promise(resolve => setTimeout(resolve, 1000));
        }
      } catch (e) { /* api 已统一展示错误 */ }
      finally {
        this.qualityBatchPolling = false;
        this.qualityScoring = false;
      }
    },
    async scoreQuality(conversationId, judgeProvider) {
      if (!conversationId || this.isQualityScoring(conversationId)) return;
      const selectedProvider = judgeProvider || this.qualityJudgeProvider;
      this.qualityScoringIds = Object.assign({}, this.qualityScoringIds, { [conversationId]: true });
      try {
        const result = await this.api('/api/quality/evaluations/' + encodeURIComponent(conversationId) + '/score', {
          method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({judgeProvider:selectedProvider}),
        });
        const movedByFilter = result.qualityScored && this.qualityFilter.status === 'pending'
          ? '；当前仅显示待语义评分，记录已移至“待人工复核”' : '';
        this.showToast((result.warning || (result.qualityScored ? `质量评分完成：${result.totalScore} 分` : '规则风险扫描完成，仍待语义评分')) + movedByFilter);
        await this.loadQuality();
        this.qualityDetail = result;
        this.prepareQualityReview();
        this.qualityDetailOpen = true;
      } finally {
        const next = Object.assign({}, this.qualityScoringIds);
        delete next[conversationId];
        this.qualityScoringIds = next;
      }
    },
    async scoreQualityDetail() {
      const conversationId = this.qualityDetail && this.qualityDetail.conversationId;
      if (!conversationId) return;
      await this.scoreQuality(conversationId, this.qualityDetailJudgeProvider || this.qualityJudgeProvider || 'rules');
    },
    isQualityScoring(conversationId) {
      const single = !!(conversationId && this.qualityScoringIds && this.qualityScoringIds[conversationId]);
      const batch = !!(conversationId && this.qualityBatchTask && this.qualityBatchTask.active
        && this.qualityBatchTask.currentConversationId === conversationId);
      return single || batch;
    },
    async openQualityDetail(row) {
      let data;
      try {
        data = await this.api('/api/quality/evaluations/conversation/' + encodeURIComponent(row.conversationId));
      } catch (e) {
        this.qualityDetail = {};   // 接口异常时清空，避免 null 拖垮整页渲染
        this.qualityDetailOpen = true;
        return;
      }
      // 防御：只接受对象，非对象（null / 数组 / HTML 错误页）当作空，绝不白屏
      this.qualityDetail = (data && typeof data === 'object' && !Array.isArray(data)) ? data : {};
      this.prepareQualityReview();
      this.qualityDetailOpen = true;
    },
    prepareQualityReview() {
      const d = this.qualityDetail || {};
      if (!this.qualityJudgeModels.some(model => model.provider === this.qualityDetailJudgeProvider)) {
        const preferred = this.qualityJudgeModels.some(model => model.provider === this.qualityJudgeProvider)
          ? this.qualityJudgeProvider : 'rules';
        this.qualityDetailJudgeProvider = preferred;
      }
      this.qualityReview = { result:d.reviewResult || 'agree', note:d.reviewNote || '', scores:{
        intent:Number(d.intent || 0), accuracy:Number(d.accuracy || 0), context:Number(d.context || 0), safety:Number(d.safety || 0),
        naturalness:Number(d.naturalness || 0), resolution:Number(d.resolution || 0), handoff:Number(d.handoff || 0),
      }};
    },
    async reviewQuality() {
      if (!this.qualityDetail.id) return;
      if (!await this.askConfirm('人工复核只会更新评分结论，不会自动修改线上 AI 配置。', '保存人工复核？')) return;
      this.qualityDetail = await this.api(`/api/quality/evaluations/${this.qualityDetail.id}/review`, {
        method:'PUT', headers:{'Content-Type':'application/json'}, body:JSON.stringify(this.qualityReview),
      });
      this.prepareQualityReview();
      this.showToast(this.qualityFilter.status === 'scored'
        ? '人工复核已保存；当前仅显示待人工复核，记录已移至“已人工复核”'
        : '人工复核已保存');
      await Promise.all([this.loadQualityOverview(), this.loadQualityRows(this.qualityPage)]);
    },
    async addQualityRegression() {
      if (!this.qualityDetail.id) return;
      if (!await this.askConfirm('将取会话最后一轮客户消息和之前的上下文，加入所有管理员共享的回归用例库。', '加入回归用例？')) return;
      const item = await this.api(`/api/quality/evaluations/${this.qualityDetail.id}/regression-case`, {method:'POST'});
      this.showToast(`已加入回归用例：${item.name}`);
      this.debugCasesLoaded = false;
    },
    retestQualityInLab() {
      const messages = this.qualityDetail.messages || [];
      let lastUser = -1;
      for (let i=messages.length-1;i>=0;i--) if (messages[i].role==='user') { lastUser=i; break; }
      if (lastUser >= 0) {
        this.debugMsg = messages[lastUser].content || '';
        this.debugHistoryOverride = messages.slice(0,lastUser).filter(m=>m.role!=='agent')
          .map(m=>(m.role==='user'?'用户：':'客服：')+(m.content||'')).join('\n');
      }
      this.qualityDetailOpen = false;
      this.switchTab('aidev'); this.debugSection = 'playground';
      this.showToast('已把原对话带入 AI 调优中心，可调整草稿后复测');
    },
    qualityDimensions(source) {
      const defs = [['intent','意图识别',20],['accuracy','回答准确性',20],['context','上下文连续性',15],['safety','安全合规',20],['naturalness','话术自然度',10],['resolution','问题解决程度',10],['handoff','转人工合理性',5]];
      return defs.map(([key,name,max]) => { const value=Number(source[key] || 0); return {key,name,max,value,pct:Math.max(0,Math.min(100,value/max*100))}; });
    },
    qualityRuleDimensions() {
      return [['intent','意图识别',20],['accuracy','回答准确性',20],['context','上下文连续性',15],['safety','安全合规',20],['naturalness','话术自然度',10],['resolution','问题解决程度',10],['handoff','转人工合理性',5]].map(([key,name,max]) => ({ key, name, max }));
    },
    qualityDimName(key) {
      const map = { intent:'意图识别', accuracy:'回答准确性', context:'上下文连续性', safety:'安全合规', naturalness:'话术自然度', resolution:'问题解决程度', handoff:'转人工合理性' };
      return map[key] || key || '—';
    },
    qualityScoreClass(score) { const value=Number(score||0); return value>=90?'good':value>=70?'mid':'low'; },
    qualityBatchProgress(task) {
      const total = Math.max(0, Number(task && task.requestedCount || 0));
      if (!total) return task && task.active ? 4 : 100;
      return Math.max(2, Math.min(100, Number(task.processedCount || 0) / total * 100));
    },
    qualityBatchStatusLabel(status) {
      return { queued:'排队中', running:'正在评测中', succeeded:'已完成', partial:'部分完成', failed:'评测失败' }[status] || status || '—';
    },
    qualityAttemptStatusLabel(status) {
      return { pending:'待评测', running:'正在评测中', succeeded:'评测成功', failed:'评测失败' }[status] || status || '—';
    },
    qualityAttemptModelLabel(attempt) {
      if (!attempt || attempt.judgeProvider === 'rules') return '规则风险扫描';
      const role = attempt.judgeProvider === 'primary' ? '主模型' : attempt.judgeProvider === 'fallback' ? '备用模型' : '评测专用';
      return attempt.judgeModel ? `${role}（${attempt.judgeModel}）` : role;
    },
    qualityAttemptErrorLabel(code) {
      return {
        rate_limited:'接口限流', timeout:'调用超时', authentication_failed:'鉴权失败',
        model_unavailable:'模型不可用', invalid_response:'返回格式错误', network_error:'网络连接失败',
        model_error:'模型调用异常', rule_engine_error:'规则计算异常', persistence_error:'结果保存失败',
        application_restarted:'应用重启中断', legacy_unknown:'旧版本未记录原因',
      }[code] || '评测失败';
    },
    qualityJudgeOptionLabel(model) {
      if (!model || model.provider === 'rules') return '仅规则风险扫描（非质量分）';
      return `${model.label || (model.provider === 'primary' ? '主模型' : '备用模型')} · ${model.modelName || '未配置名称'}`;
    },
    qualityJudgeModelLabel(item) {
      const provider = item && item.judgeProvider;
      const configured = this.qualityJudgeModels.find(model => model.provider === provider);
      const role = provider === 'primary' ? '主模型' : provider === 'fallback' ? '备用模型' : provider === 'eval' ? '评测专用' : 'AI 模型';
      const name = (configured && configured.displayName) || (item && item.judgeModel) || (configured && configured.modelName) || '';
      return name ? `${role}（${name}）` : role;
    },
    qualityJudgeAttemptLabel(item) {
      return this.qualityJudgeModelLabel({
        judgeProvider: (item && item.judgeErrorProvider) || (item && item.judgeProvider),
        judgeModel: (item && item.judgeErrorModel) || (item && item.judgeModel),
      });
    },
    qualityJudgeFailureLabel(item) {
      const code = item && item.judgeErrorCode;
      return {
        rate_limited:'接口限流', timeout:'调用超时', authentication_failed:'鉴权失败',
        model_unavailable:'模型不可用', invalid_response:'返回格式错误',
        network_error:'网络连接失败', model_error:'模型调用异常', legacy_unknown:'旧版本未记录原因',
      }[code] || '语义复核失败';
    },
    decisionTraceOf(message) {
      if (!message) return null;
      let trace = message.decisionTrace != null ? message.decisionTrace : message.decision_trace;
      if (trace && typeof trace === 'object' && trace.value != null) trace = trace.value;
      if (typeof trace === 'string') {
        try { trace = JSON.parse(trace); } catch (e) { return null; }
      }
      return trace && typeof trace === 'object' && !Array.isArray(trace) ? trace : null;
    },
    formatDecisionTrace(trace) {
      if (!trace) return '';
      const yesNo = value => value === true ? '是' : value === false ? '否' : '—';
      const promptModules = Array.isArray(trace.promptModules)
        ? trace.promptModules.map(module => `${module.id}@${module.version}`).join('、')
        : '';
      return [
        `路由：${trace.route || '—'}`,
        `原因：${trace.routeReason || '—'}`,
        `权威领域：${trace.authorityDomain || '—'}`,
        `权威来源：${trace.authoritySource || '—'}`,
        `允许知识降级：${yesNo(trace.knowledgeFallbackAllowed)}`,
        `需要生成模型：${yesNo(trace.modelRequired)}`,
        `候选 / 采用：${Number(trace.candidateCount || 0)} / ${Number(trace.relevantCount || 0)}`,
        `来源：${Array.isArray(trace.sourceRefs) && trace.sourceRefs.length ? trace.sourceRefs.join('、') : '—'}`,
        `Prompt：${trace.promptVersion ? `${trace.promptVersion} · ${trace.promptHash || '无指纹'}` : '未使用生成 Prompt'}`,
        `启用模块：${promptModules || '—'}`,
      ].join('\n');
    },
    qualityJudgeSource(row) {
      if (!row || !row.judgeProvider) return '规则风险扫描';
      const source = `规则约束 + ${this.qualityJudgeModelLabel(row)}`;
      if (row.judgeErrorCode) {
        const failure = this.qualityJudgeFailureLabel(row);
        return row.qualityScored
          ? `${source}（最近重评${failure}，已保留原评分）`
          : `规则约束 + ${this.qualityJudgeAttemptLabel(row)}（${failure}）`;
      }
      if (row.judgeEnabled && !row.qualityScored) return `${source}（旧版待重评）`;
      return row.judgeEnabled ? source : `${source}（语义复核失败）`;
    },
    qualityTrendHeight(score) { return Math.max(2, Math.min(100, Number(score || 0))); },
    qualityTrendTitle(point) { return `${AICS.formatDate(point.day)}：已语义评分平均 ${point.score || 0} 分，${point.count || 0} 个会话`; },
    // 查看会话详情：用列表返回的 user_id 作为归属校验参数
    async viewConv(c) {
      let data;
      try {
        data = await this.api('/api/conversations/' + encodeURIComponent(c.conversation_id) + '/messages');
      } catch (e) {
        this.convDetail = [];   // 接口异常时清空，避免 null 拖垮整页渲染
        return;
      }
      // 防御：只接受数组，任何非数组（null / 对象 / HTML 错误页）都当作空，绝不白屏
      this.convDetail = Array.isArray(data) ? data : [];
      this.convDetailPage = 1;
      this.convDetailOpen = true;   // 居中弹窗展示，不用滚动到页面底部
    },
    // 会话质检：删除指定会话（级联删消息），需确认避免误删
    async deleteConv(c) {
      const ok = await this.askConfirm('将一并删除该会话下的全部 ' + (c.message_count || 0) + ' 条消息，且不可恢复。', '确认删除该会话？');
      if (!ok) return;
      try {
        await this.api('/api/conversations/' + encodeURIComponent(c.conversation_id), { method: 'DELETE' });
        this.convSelected = this.convSelected.filter(id => id !== c.conversation_id);
        this.showToast('会话已删除');
        // 若删除的是当前页最后一条且非首页，回退上一页避免空页
        if (this.convs.length <= 1 && this.convPage > 1) {
          this.loadConvs(this.convPage - 1);
        } else {
          this.loadConvs(this.convPage);
        }
      } catch (e) {
        this.showToast('删除失败：' + (e && e.message ? e.message : e));
      }
    },
    // 会话质检：超级管理员清空全部会话（需密码二次验证）
    async clearAllConvs() {
      const currentPassword = await this.askSensitiveConfirm('将永久删除全部会话及其全部消息，且不可恢复。操作前请确认已备份数据库。', '清空全部会话记录？');
      if (!currentPassword) return;
      this.convClearing = true;
      try {
        const r = await this.api('/api/conversations/clear', {
          method: 'DELETE', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ currentPassword }),
        });
        this.showToast('已清空全部会话，共 ' + (r.deleted || 0) + ' 条');
        this.convSelected = [];
        this.loadConvs(1);
      } catch (e) {
        this.showToast('清空失败：' + (e && e.message ? e.message : e));
      } finally {
        this.convClearing = false;
      }
    },
    // 会话质检：表头全选/取消全选（仅作用于当前页，不去跨页勾选，避免误删）
    toggleConvAll(val) {
      const pageIds = this.convs.map(c => c.conversation_id);
      if (val) {
        this.convSelected = Array.from(new Set([...this.convSelected, ...pageIds]));
      } else {
        const pageSet = new Set(pageIds);
        this.convSelected = this.convSelected.filter(id => !pageSet.has(id));
      }
    },
    // 会话质检：批量删除选中会话（逐个调用现有删除接口，复用审计与级联删除逻辑）
    async deleteSelectedConvs() {
      if (!this.convSelected.length) return;
      const ok = await this.askConfirm('将删除选中的 ' + this.convSelected.length + ' 个会话及其全部消息，且不可恢复。', '确认批量删除会话？');
      if (!ok) return;
      this.convDeleting = true;
      try {
        const ids = [...this.convSelected];
        const results = await Promise.allSettled(
          ids.map(id => this.api('/api/conversations/' + encodeURIComponent(id), { method: 'DELETE' }))
        );
        const failed = results.filter(r => r.status === 'rejected');
        this.convSelected = [];
        if (failed.length) {
          this.showToast('批量删除完成：' + (ids.length - failed.length) + ' 个成功，' + failed.length + ' 个失败');
        } else {
          this.showToast('已批量删除 ' + ids.length + ' 个会话');
        }
        // 若当前页被删空且非首页，回退避免空页
        if (this.convs.length <= ids.length && this.convPage > 1) {
          this.loadConvs(this.convPage - 1);
        } else {
          this.loadConvs(this.convPage);
        }
      } catch (e) {
        this.showToast('批量删除失败：' + (e && e.message ? e.message : e));
      } finally {
        this.convDeleting = false;
      }
    },
    // 质检详情角色图标：客户 👤 / 人工坐席 👩💼 / AI 🤖（区分人工与 AI，便于质检）
    roleIcon(role) {
      if (role === 'user') return '👤 ';
      if (role === 'agent') return '👩‍💼 ';
      return '🤖 ';
    },
    // 报表构成条宽度（空数组兜底，避免 NaN）
    compBarPx(count, composition) {
      const max = Math.max(...(composition || []).map(x => x.count), 1);
      return Math.round(count * 200 / max);
    },
  },
});
// 全局错误处理器：任何渲染/运行错误暴露到控制台。API 错误已在 api() 中 toast 提示，
// 不再修改 document.title，避免把浏览器标签页变成 "⚠ 模型角色只能..." 这类难看文案。
app.config.errorHandler = (err, _instance, info) => {
  console.error('[Vue error]', info || '', err);
};
// Vue3 模板作用域不认 window 全局：把 AICS 挂到组件实例，模板里 AICS.xxx 才可用（兜底；新代码统一走 methods）
app.config.globalProperties.AICS = AICS;
app.mount('#app');
