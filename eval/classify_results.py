# -*- coding: utf-8 -*-
"""会话测试结果分类器：实质性回答 / 空泛非回答 / 澄清反问 / 规范拒答，并给出与治理前基线的对比"""
import json, os, sys, re
from collections import defaultdict

PATH = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "comprehensive_results.json")
d = json.load(open(PATH, encoding="utf-8"))

NON_ANSWER = ["没收到", "没发出来", "请问有什么可以帮您", "再说一遍", "没听清", "问题好像",
              "AI 服务暂时不可用", "请求处理失败", "换个方式"]
CLARIFY = ["哪个项目", "想了解哪", "告诉我项目", "您想问哪", "什么项目", "哪个部位"]
REFUSE = ["转人工", "已为您转接", "无法提供", "不在服务范围", "暂时无法"]

def classify(text):
    t = text or ""
    if any(k in t for k in NON_ANSWER): return "non_answer"
    if any(k in t for k in CLARIFY): return "clarify"
    if any(k in t for k in REFUSE): return "refuse"
    if len(t.strip()) >= 12: return "substantive"
    return "short"

rows = []
for k in d["knowledge"]:
    rows.append(("知识-" + k["cat"], k["q"], k.get("reply") or "", k))
for e in d["extra"]:
    rows.append(("扩展-" + e["cat"], e["q"], e.get("reply") or "", e))

stat = defaultdict(list)
for cat, q, reply, _ in rows:
    stat[classify(reply)].append((cat, q, reply))

LABEL = {"substantive": "✅ 实质回答", "clarify": "🔵 澄清反问", "refuse": "🟡 规范拒答", "non_answer": "⚠️ 空泛非回答", "short": "❓ 过短"}
total = len(rows)
print(f"=== 总计 {total} 题 ===")
for key in ["substantive", "clarify", "refuse", "non_answer", "short"]:
    items = stat.get(key, [])
    print(f"{LABEL[key]}: {len(items)} ({len(items)*100//total}%)")

print("\n--- 分类明细 ---")
for key in ["non_answer", "short", "clarify", "refuse"]:
    for cat, q, reply in stat.get(key, []):
        print(f"[{LABEL[key]}] ({cat}) {q} => {reply[:70]}")

# 分类别统计
print("\n--- 分类别 ✅/总数 ---")
bycat = defaultdict(lambda: [0, 0])
for cat, q, reply, _ in rows:
    bycat[cat][1] += 1
    if classify(reply) in ("substantive", "refuse"):
        bycat[cat][0] += 1  # 拒答对库外题也算合格
for cat in sorted(bycat):
    ok, tot = bycat[cat]
    print(f"  {cat}: {ok}/{tot}")

# 多轮流
print("\n--- 多轮流 ---")
for f in d["flows"]:
    hits = sum(1 for t in f["turns"] if not t.get("error") and any(e in (t.get("reply") or "") for e in f["expect"]))
    print(f"  {f['name']}: 命中关键词轮次 {hits}/{len(f['turns'])}")
    for t in f["turns"]:
        mark = "OK" if not t.get("error") else "ERR:" + str(t.get("error"))[:40]
        print(f"    T{t['turn']} [{mark}] {t['msg']} => {(t.get('reply') or '')[:60]}")

json.dump({k: [{"cat": c, "q": q, "reply": r, "class": k} for c, q, r in stat[k]] for k in stat},
          open(PATH.replace(".json", ".classified.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("\nWROTE", PATH.replace(".json", ".classified.json"))
