-- 知识治理：动态事实退出向量 FAQ，医疗内容统一降为非承诺口径，补齐项目归属与别名。

-- 一、补齐项目别名。项目中心是运行时意图识别的唯一来源，FAQ 里存在的错别字也应能命中。
INSERT INTO service_project_alias(project_id, alias)
SELECT p.id, v.alias
FROM service_project p
JOIN (VALUES
    ('抗衰', '抗老'), ('抗衰', '面部提升'), ('抗衰', '提拉紧致'),
    ('抗衰', '紧致提升'), ('抗衰', '抗帅'),
    ('抗衰全脸', '全脸'), ('抗衰全脸', '全脸抗衰'), ('抗衰全脸', '全脸提升'),
    ('眼袋', '去眼袋'), ('眼袋', '眼代'),
    ('淡斑', '祛斑'), ('淡斑', '黄褐斑'), ('淡斑', '雀斑'),
    ('淡斑', '色斑'), ('淡斑', '淡班'),
    ('胡子', '胡须'), ('胡子', '脱胡子'), ('胡子', '脱胡须'), ('胡子', '胡子管理')
) AS v(project_name, alias) ON v.project_name=p.name
ON CONFLICT DO NOTHING;

-- 二、参考资料改为稳定知识。价格、订金、赠品、接车、门店范围均只从结构化主数据读取。
WITH replacement(doc_name, content) AS (VALUES
    ('产品-双逆博士.md', $kb$
# 产品·双逆博士

“双逆博士”是资料中使用的抗衰仪器名称，涉及 MFU（微聚焦超声）与 RF（射频）技术。

仪器的具体型号、注册或备案信息、适用范围、操作层次和人员资质，需要按实际门店公示资料核验。线上客服不能据此承诺固定效果、完全无痛、无恢复期或适合所有人。

是否适合使用以及具体方案，应结合健康状况、用药、皮肤情况和专业评估确定。
$kb$),
    ('产品-水光娃娃.md', $kb$
# 产品·水光娃娃

“水光娃娃”是历史资料中出现的面部补水护理名称，可能作为附加护理或活动内容出现。

它是否提供、是否收费、适用于哪个项目以及具体服务内容，必须以项目中心当前已发布话术和具体门店确认为准，不能把历史赠送方案当作长期承诺。
$kb$),
    ('对比-四项目综合对比.md', $kb$
# 项目·综合对比

当前主项目包括抗衰、眼袋、淡斑和胡子管理；抗衰全脸作为抗衰的子项目独立维护报价和支持门店。

- 抗衰：面向紧致提升等诉求，具体部位和方案以专业评估为准。
- 抗衰全脸：属于抗衰子项目，服务范围不得从“单部位”口径推断。
- 眼袋：面向眼周问题咨询，是否适合及改善程度因人而异。
- 淡斑：需要先区分斑点类型和皮肤情况，不能承诺去除。
- 胡子管理：面向胡须管理诉求，需要次数和效果因人而异。

价格、订金、活动话术、图片和支持门店均从项目中心实时读取；本参考资料不保存具体金额和门店数量。
$kb$),
    ('概念-MFU-RF技术.md', $kb$
# 概念·MFU 与 RF

MFU 通常指微聚焦超声，RF 通常指射频。两类技术可能用于皮肤相关项目，但具体作用层次、参数、适用范围和风险取决于实际设备及方案。

线上客服只能作一般概念说明，不能承诺完全无痛、无恢复期、长期保持或适合所有人。具体设备资质、人员资质和个人适用性应按门店公示信息及专业评估核验。
$kb$),
    ('概念-恢复期.md', $kb$
# 概念·恢复与护理

恢复情况会受项目、设备参数、个人皮肤和身体状况影响，不能统一承诺不红不肿、没有恢复期或第二天一定不受影响。

操作前应确认具体项目的书面护理要求；操作后按专业人员给出的指引执行。若出现明显、持续或加重的不适，应及时联系门店或正规医疗机构处理。
$kb$),
    ('概念-禁忌症.md', $kb$
# 概念·适用性与禁忌

是否适合操作不能只按少数疾病名单判断。健康状况、既往病史、过敏史、近期手术或注射史、怀孕和哺乳情况以及当前用药，都可能影响评估结果。

不要为了项目自行停药、减药或继续用药；尤其涉及抗凝、抗血小板等药物时，应先咨询开药医生。线上客服不作个人医疗判断，具体由具备相应资质的专业人员评估。
$kb$),
    ('抗衰-面部紧致提升.md', $kb$
# 项目·抗衰面部紧致提升

抗衰项目面向面部紧致、松弛和轮廓等相关诉求，可能使用 MFU、RF 等仪器技术。抗衰单部位与抗衰全脸是不同报价项目，具体服务部位以项目中心和门店确认为准。

实际效果、需要次数、疼痛感受和恢复情况因人而异，不能承诺一次见效、固定维持时间或不反弹。是否适合需结合健康状况、用药、皮肤情况和专业评估确定。
$kb$),
    ('淡斑-祛斑.md', $kb$
# 项目·淡斑

淡斑项目面向色斑、暗沉等诉求。斑点类型、形成时间、肤质和个人情况会影响方案及结果，线上不能承诺所有斑点都能去除，也不能只按年龄判断是否适合。

实际效果、需要次数、皮肤反应和恢复情况因人而异。具体方案、护理要求和适用性应由专业人员评估。
$kb$),
    ('眼袋-眼袋去除.md', $kb$
# 项目·眼袋

眼袋项目面向眼周相关诉求。不同眼袋类型、年龄、健康状况和皮肤基础对应的适用性不同，不能承诺所有类型或所有年龄都适合，也不能承诺从根源解决。

实际效果、需要次数、疼痛感受和恢复情况因人而异。具体服务方案、人员资质和支持门店需要按项目中心及门店公示信息确认。
$kb$),
    ('胡子-胡子去除.md', $kb$
# 项目·胡子管理

胡子管理项目面向唇胡、络腮胡和胡青等相关诉求，可能使用仪器进行毛发管理。

实际效果、需要次数、疼痛感受、皮肤反应和恢复情况因人而异，不能承诺一次完成、永久去除或完全不伤皮肤。具体技术、适用性、护理要求和支持门店应由专业人员及项目中心确认。
$kb$)
)
UPDATE knowledge_chunk k
SET content=btrim(r.content), search_text=btrim(r.content), risk_level='medium',
    safe_direct=false, update_time=now()
FROM replacement r
WHERE k.doc_name=r.doc_name AND k.knowledge_type='reference';

-- 三、参考资料关联标准项目；综合、恢复和禁忌类资料保持全局。
WITH mapping(doc_name, project_name) AS (VALUES
    ('产品-双逆博士.md', '抗衰'),
    ('产品-水光娃娃.md', '抗衰'),
    ('概念-MFU-RF技术.md', '抗衰'),
    ('抗衰-面部紧致提升.md', '抗衰'),
    ('眼袋-眼袋去除.md', '眼袋'),
    ('淡斑-祛斑.md', '淡斑'),
    ('胡子-胡子去除.md', '胡子')
)
UPDATE knowledge_chunk k
SET project=p.name, project_id=p.id, update_time=now()
FROM mapping m
JOIN service_project p ON p.name=m.project_name AND p.status='published'
WHERE k.doc_name=m.doc_name;

-- 四、医疗与效果类 FAQ 改为不承诺口径。问题保持不变，避免破坏已有召回入口。
UPDATE knowledge_chunk
SET content=split_part(content, E'\n', 1)
        || E'\n答：是否适合不能只按年龄、性别或单一情况判断，需要结合健康状况、用药、皮肤基础和具体项目，由专业人员评估。'
        || E'\n分类：' || COALESCE(NULLIF(category,''), '通用'),
    risk_level='high', safe_direct=false, update_time=now()
WHERE knowledge_type='faq' AND content ~ '^问：.*(什么年龄|多大年龄|70岁|60岁|50岁|小姑娘|年纪大|男的能做|男生做|男的可以做)';

UPDATE knowledge_chunk
SET content=split_part(content, E'\n', 1)
        || E'\n答：既往病史、过敏史、近期手术或注射史以及当前用药都需要如实告知。不要为做项目自行停药或调整用药，请先咨询开药医生，再由具备相应资质的专业人员评估。'
        || E'\n分类：' || COALESCE(NULLIF(category,''), '通用'),
    risk_level='high', safe_direct=false, update_time=now()
WHERE knowledge_type='faq' AND content ~ '^问：.*(有病史|阿司匹林|正在服药|心脏支架|心脏病|癫痫|麻药过敏)';

UPDATE knowledge_chunk
SET content=split_part(content, E'\n', 1)
        || E'\n答：实际效果、需要次数和维持时间会因个人基础、具体方案及护理情况不同而变化，不能保证一次见效、固定维持时间、完全去除或不反弹，请以专业评估为准。'
        || E'\n分类：' || COALESCE(NULLIF(category,''), '通用'),
    risk_level='high', safe_direct=false, update_time=now()
WHERE knowledge_type='faq' AND content ~ '^问：.*(效果|反弹|永久|几次|一次就|一次到位|维持|能管|管几年|多久能看到|黑眼圈能去掉|黄褐斑能去掉|斑点很多年|什么类型的眼袋|会不会脸僵|老了会不会)';

UPDATE knowledge_chunk
SET content=split_part(content, E'\n', 1)
        || E'\n答：历史资料显示该项目采用仪器方式，但具体设备、原理、操作步骤、人员资质和是否涉及其他操作，应以实际门店公示资料及专业评估为准。'
        || E'\n分类：' || COALESCE(NULLIF(category,''), '通用'),
    risk_level='medium', safe_direct=false, update_time=now()
WHERE knowledge_type='faq' AND content ~ '^问：.*(是打针还是手术|眼袋怎么去除|雀斑怎么淡|色斑怎么处理|什么仪器|怎么做的|不用开刀)';

UPDATE knowledge_chunk
SET content=split_part(content, E'\n', 1)
        || E'\n答：疼痛感受、皮肤反应和恢复情况会因项目及个人情况不同而变化，不能保证完全不痛、不红不肿或没有恢复期；具体请在操作前由专业人员确认。'
        || E'\n分类：' || COALESCE(NULLIF(category,''), '通用'),
    risk_level='high', safe_direct=false, update_time=now()
WHERE knowledge_type='faq' AND content ~ '^问：.*(痛不痛|打麻药|红肿|恢复期|会伤害皮肤|副作用|能直接上班|能马上走|几天能恢复|要休息几天|做完会疼|做完会肿|会结痂)';

UPDATE knowledge_chunk
SET content=split_part(content, E'\n', 1)
        || E'\n答：不同项目的操作前准备和操作后护理要求不同，请以专业人员针对本次项目给出的书面指引为准；如出现明显、持续或加重的不适，请及时联系门店或正规医疗机构。'
        || E'\n分类：' || COALESCE(NULLIF(category,''), '通用'),
    risk_level='high', safe_direct=false, update_time=now()
WHERE knowledge_type='faq' AND content ~ '^问：.*(术后|做完.*(洗脸|护肤|化妆|冰敷|饮食|喝茶|喝酒|吃辣|游泳|桑拿|出汗|晒太阳|运动|洗澡|不适|注意什么|出门|上班|使用产品|吹空调|开车|戴眼镜)|需要空腹|做之前|刮了胡子)';

UPDATE knowledge_chunk
SET content=split_part(content, E'\n', 1)
        || E'\n答：机构资质、人员执业信息、项目许可范围和售后规则需要按具体门店及公示资料核验，不能用“全国连锁”或口头承诺代替；需要的话可以回复“转人工”协助确认。'
        || E'\n分类：' || COALESCE(NULLIF(category,''), '通用'),
    risk_level='high', safe_direct=false, update_time=now()
WHERE knowledge_type='faq' AND content ~ '^问：.*(专业医生|资质|正规店|大医院|比医院|做坏|差评|效果不满意|开店多久)';

UPDATE knowledge_chunk
SET content=split_part(content, E'\n', 1)
        || E'\n答：不能用价格、连锁规模或口头保证直接判断效果和服务质量。建议结合具体门店公示资质、项目范围、收费明细和实际评估后再决定。'
        || E'\n分类：' || COALESCE(NULLIF(category,''), '通用'),
    risk_level='medium', safe_direct=false, update_time=now()
WHERE knowledge_type='faq' AND content ~ '^问：.*(朋友在别家|其他机构|其他美容院|价格比别人|之前被骗|会不会一直推销|留了电话会不会骚扰)';

-- 五、动态事实不再参与向量检索。价格、活动、退款、支付、营业时间、门店数量、
-- 停车与接车均由 project_offer / business_fact / store / store_project 实时回答。
UPDATE knowledge_chunk
SET status='disabled', risk_level='high', safe_direct=false, update_time=now()
WHERE knowledge_type='faq' AND content ~ '^问：.*(价格|多少钱|到店再付|订金|定金|退款|退订|活动|名额|隐形消费|额外费用|收费|预约和直接到店|预定|到店不满意|接送|路费|接车|送回|有点远|停车|营业|多少家店|支付|刷卡|分期|医保|发票|排队|做什么项目|什么项目可以做|都做什么|主要做什么|有哪些项目|有什么项目)';

-- 六、修正当前欢迎语与人工客服配置的冲突；仅替换已知旧文案，不覆盖运营自定义内容。
UPDATE system_config
SET value=replace(value, '人工客服在线时间是上午 10:00 至晚上 22:00',
                         '人工客服在线时间是上午 09:00 至晚上 22:00'),
    update_time=now()
WHERE key='welcome.message'
  AND value LIKE '%人工客服在线时间是上午 10:00 至晚上 22:00%';
