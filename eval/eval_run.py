# -*- coding: utf-8 -*-
"""
第 5 周 · 批量评测脚本
流程：逐题调用 /api/chat/rag → 用 DeepSeek 当裁判对比标准答案打分 → 输出评测报告.xlsx

评分规则：
  正确 1 分 / 部分正确 0.5 分 / 错误 0 分；另统计幻觉（编造了知识库没有的信息）。
运行：python eval_run.py   （需要环境变量 DEEPSEEK_API_KEY，且服务在 8080 端口运行中）
"""
import json
import os
import time
import urllib.request
import urllib.parse

import openpyxl

BASE = "http://localhost:8080"
DEEPSEEK_KEY = os.environ.get("DEEPSEEK_API_KEY", "")
QWEN_KEY = os.environ.get("DASHSCOPE_API_KEY", "")

# 业务事实清单（P2-1 抽出来共用，裁判打分依据；新增价格/承诺/品牌时同步维护这里）
BUSINESS_FACTS = ("这是医美客服场景，以下业务事实成立——抗衰298元/全脸688元、淡斑198元、眼袋468元、"
                  "胡子280元、订金8-9元可退、免费接车、打车报销60元内车费、全国连锁昇媛美容；"
                  "双逆博士是机构引进的抗衰仪器品牌（MFU微聚焦超声+RF射频双技术、非手术无创），"
                  "机构可做的项目包括抗衰/淡斑/眼袋/胡子四类。")


def ask_rag(question: str) -> dict:
    """调用系统的 RAG 接口（每题独立会话，不带 conversationId）"""
    url = BASE + "/api/chat/rag?" + urllib.parse.urlencode({"message": question})
    with urllib.request.urlopen(urllib.request.Request(url, method="POST"), timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def judge(question: str, standard: str, ai_answer: str) -> dict:
    """让 DeepSeek 当阅卷老师：对比标准答案要点给 AI 回答打分"""
    prompt = f"""你是严格的阅卷老师。请对比「标准答案要点」和「AI 客服的回答」，按 JSON 格式评分。

评分标准：
- verdict: "正确"（要点全覆盖且无错误；附带正确的额外信息不扣分）/ "部分正确"（答对一部分或遗漏次要要点）/ "错误"（答错关键信息）
- hallucination: true/false —— 仅当 AI 编造了【与事实不符或无法佐证的虚假信息】时才为 true，例如：虚构的价格、地址、人名、承诺。
  特别注意：{BUSINESS_FACTS}
  AI 主动补充了这些【真实存在】的额外信息不算幻觉（话多不等于造假），也不影响 verdict。
- reason: 一句话说明评分理由

标准答案要点：{standard}
AI 客服的回答：{ai_answer}
用户问题：{question}

只输出 JSON，例如：{{"verdict":"正确","hallucination":false,"reason":"要点全覆盖"}}"""

    req = urllib.request.Request(
        "https://api.deepseek.com/v1/chat/completions",
        data=json.dumps({
            "model": "deepseek-chat",
            "messages": [{"role": "user", "content": prompt}],
            "response_format": {"type": "json_object"},
            "temperature": 0,
        }).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {DEEPSEEK_KEY}",
        },
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        content = json.loads(resp.read().decode("utf-8"))
    return json.loads(content["choices"][0]["message"]["content"])


def judge_cross(question: str, standard: str, ai_answer: str) -> dict:
    """P2-1 交叉裁判：用通义千问（百炼）复核 DeepSeek 的评分。

    动机：裁判与考生同模型（DeepSeek 评 DeepSeek）存在自我偏袒风险。
    交叉裁判用另一个模型独立打分，只复核抽检样本，不一致时以人工裁决为准。
    复用阿里云百炼 OpenAI 兼容端点（与 Embedding 同一个 Key），无需新账号。
    """
    prompt = f"""你是独立的复核裁判。请对比「标准答案要点」和「AI 客服的回答」，按 JSON 格式评分。

评分标准：
- verdict: "正确"（要点全覆盖且无错误；附带正确的额外信息不扣分）/ "部分正确"（答对一部分或遗漏次要要点）/ "错误"（答错关键信息）
- hallucination: true/false —— 仅当 AI 编造了【与事实不符或无法佐证的虚假信息】时才为 true，例如：虚构的价格、地址、人名、承诺。
  特别注意：{BUSINESS_FACTS}
  AI 主动补充了这些【真实存在】的额外信息不算幻觉（话多不等于造假），也不影响 verdict。
- reason: 一句话说明评分理由

标准答案要点：{standard}
AI 客服的回答：{ai_answer}
用户问题：{question}

只输出 JSON，例如：{{"verdict":"正确","hallucination":false,"reason":"要点全覆盖"}}"""

    req = urllib.request.Request(
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        data=json.dumps({
            "model": "qwen-plus",
            "messages": [{"role": "user", "content": prompt}],
            "response_format": {"type": "json_object"},
            "temperature": 0,
        }).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {QWEN_KEY}",
        },
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        content = json.loads(resp.read().decode("utf-8"))
    return json.loads(content["choices"][0]["message"]["content"])


def main():
    if not DEEPSEEK_API_KEY_ok():
        return

    wb = openpyxl.load_workbook("评测集.xlsx")
    ws = wb.active
    rows = list(ws.iter_rows(min_row=2, values_only=True))

    # 报告文件
    rwb = openpyxl.Workbook()
    rws = rwb.active
    rws.title = "评测报告"
    rws.append(["编号", "分类", "问题", "标准答案要点", "AI回答", "评分", "幻觉", "评分理由", "最佳距离"])

    total, score_sum, hallucinations = 0, 0.0, 0
    category_stats = {}
    collected = []   # P2-1 交叉抽检用：收集每题 (question, standard, answer, verdict, halluc)

    for num, cat, question, standard in rows:
        total += 1
        try:
            rag = ask_rag(question)
            answer = rag.get("answer", "")
            best_dist = min((s["distance"] for s in rag.get("sources", [])), default=None)
            result = judge(question, standard, answer)
        except Exception as e:
            answer, best_dist = f"[调用失败] {e}", None
            result = {"verdict": "错误", "hallucination": False, "reason": f"接口异常: {e}"}

        verdict = result.get("verdict", "错误")
        halluc = bool(result.get("hallucination", False))
        score = {"正确": 1.0, "部分正确": 0.5}.get(verdict, 0.0)
        score_sum += score
        hallucinations += 1 if halluc else 0

        c = category_stats.setdefault(cat, [0, 0.0])
        c[0] += 1
        c[1] += score

        rws.append([num, cat, question, standard, answer, verdict,
                    "是" if halluc else "否", result.get("reason", ""), best_dist])
        collected.append((question, standard, answer, verdict, halluc))
        print(f"[{num:02d}] {cat} | {verdict} | 幻觉:{'是' if halluc else '否'} | {question}")
        time.sleep(0.5)  # 温柔一点，别打满 API

    # P2-1 交叉抽检：全部"非正确"题 + 20% 随机"正确"题，用通义千问复核 DeepSeek 的评分
    cross_reviewed = cross_check(collected)

    # 汇总
    print("\n" + "=" * 50)
    print(f"总题数: {total}   总得分: {score_sum:.1f}   正确率: {score_sum/total*100:.1f}%")
    print(f"幻觉数: {hallucinations}   幻觉率: {hallucinations/total*100:.1f}%")
    print("-" * 50)
    for cat, (n, s) in category_stats.items():
        print(f"  {cat}: {s/n*100:.0f}% ({s:.1f}/{n})")
    print("=" * 50)
    print("达标线：正确率 ≥ 85%、幻觉率 ≤ 3%")

    rws.append([])
    rws.append(["汇总", "", f"正确率 {score_sum/total*100:.1f}%", f"幻觉率 {hallucinations/total*100:.1f}%"])
    if cross_reviewed:
        rws.append(["交叉抽检", "", f"复核 {cross_reviewed[0]} 题", f"评分不一致 {cross_reviewed[1]} 题"])
        for item in cross_reviewed[2]:
            rws.append(["", "", f"题: {item[0]}", f"DeepSeek={item[1]} → 千问={item[2]}  (需人工裁决: {item[3][:60]})"])
    for col, w in zip("ABCDEFGHI", [6, 10, 30, 40, 60, 10, 6, 40, 10]):
        rws.column_dimensions[col].width = w
    rwb.save("评测报告.xlsx")
    print("详细结果已写入 评测报告.xlsx")


def cross_check(collected):
    """P2-1 交叉裁判抽检：全部"非正确"题 + 20% 随机"正确"题，用通义千问复核 DeepSeek 的评分。
    返回 (复核题数, 不一致题数, 不一致明细列表)；未配置 DASHSCOPE_API_KEY 时返回空列表跳过。"""
    if not QWEN_KEY or not collected:
        if not QWEN_KEY:
            print("\n（跳过交叉抽检：未设置环境变量 DASHSCOPE_API_KEY，配置后可启用双模型裁判）")
        return []

    import random
    random.seed(42)   # 固定随机种子，多次运行抽检样本可复现
    disputed = [c for c in collected if c[3] != "正确"]
    ok_samples = [c for c in collected if c[3] == "正确"]
    n_ok = max(1, int(len(collected) * 0.2))
    random.shuffle(ok_samples)
    sample = disputed + ok_samples[:n_ok]

    print(f"\n交叉抽检：{len(sample)} 题（{len(disputed)} 非正确 + {min(n_ok, len(ok_samples))} 正确）用通义千问复核…")
    mismatches = []
    for question, standard, answer, ds_verdict, _ in sample:
        try:
            qwen = judge_cross(question, standard, answer)
            qv = qwen.get("verdict", "错误")
            if qv != ds_verdict:
                mismatches.append((question, ds_verdict, qv, qwen.get("reason", "")))
                print(f"  ⚠️ 评分不一致 [{ds_verdict} vs {qv}] {question}\n    千问理由: {qwen.get('reason','')}")
        except Exception as e:
            print(f"  ⚠️ 千问复核失败（跳过）: {e}")
        time.sleep(0.3)

    print(f"交叉抽检完成：{len(sample)} 题复核，{len(mismatches)} 题评分不一致（需人工裁决）")
    return [len(sample), len(mismatches), mismatches]


def DEEPSEEK_API_KEY_ok() -> bool:
    if not DEEPSEEK_KEY:
        print("错误：环境变量 DEEPSEEK_API_KEY 未设置")
        return False
    return True


if __name__ == "__main__":
    main()
