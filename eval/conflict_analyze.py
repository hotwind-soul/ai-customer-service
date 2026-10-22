import json, re
from collections import defaultdict

base = "C:/Users/JT/WorkBuddy/新/ai-customer-service/eval"
kc = json.load(open(f"{base}/dump_knowledge_chunk.json", encoding="utf-8"))
proj = json.load(open(f"{base}/dump_projects.json", encoding="utf-8"))

sp = {r["name"]: r for r in proj["service_project"]}
auth = defaultdict(set)
act = {}
deposit = {}
for r in proj["service_project"]:
    name = r["name"]; pid = r["id"]
    for o in proj["offer"]:
        if o["project_id"] == pid:
            for k in ("activity_price", "original_price", "deposit"):
                v = o.get(k)
                if v is not None:
                    auth[name].add(int(float(v)))
            act[name] = int(float(o["activity_price"]))
            deposit[name] = int(float(o.get("deposit") or 0))
# 子项目并入父；计算「到店再付」派生价
children = defaultdict(list)
for r in proj["service_project"]:
    if r.get("parent_id"):
        children[r["parent_id"]].append(r["name"])
auth_ext = {n: set(v) for n, v in auth.items()}
derived = {}
for r in proj["service_project"]:
    name = r["name"]
    d = act[name] - deposit.get(name, 0)
    derived[name] = d
    auth_ext[name].add(d)
    # 父项目合并子项目价格
    parent = next((p["name"] for p in proj["service_project"] if p["id"] == r.get("parent_id")), None)
    if parent:
        auth_ext[parent] |= auth[name]
        auth_ext[parent].add(d)

print("=== 权威活动价 / 到店再付 ===")
for n in act:
    print(f"  {n}: 活动{act[n]} 到店再付{derived[n]} 合法集{auth_ext.get(n)}")
print()

price_re = re.compile(r"(\d+(?:\.\d+)?)\s*元")
conflicts = []
PROJECTS = list(auth_ext.keys())

def flag(**kw): conflicts.append(kw)

for c in kc:
    content = c.get("content") or ""
    doc = c.get("doc_name"); idx = c.get("chunk_index")
    col_proj = c.get("project")
    # 1) project 列孤儿
    if col_proj and col_proj not in auth_ext:
        flag(type="project_col_orphan", doc=doc, idx=idx, project_col=col_proj, snippet=content[:140])
    # 2) 按项目名分段：每段归属其前的项目名，避免价格串到隔壁项目
    positions = []
    for name in PROJECTS:
        for m in re.finditer(re.escape(name), content):
            positions.append((m.start(), m.end(), name))
    positions.sort()
    for i, (s, e, name) in enumerate(positions):
        nxt = positions[i + 1][0] if i + 1 < len(positions) else len(content)
        seg = content[e:nxt]
        for pm in price_re.finditer(seg):
            pr = int(float(pm.group(1)))
            if pr not in auth_ext[name]:
                flag(type="price_mismatch", doc=doc, idx=idx, project=name,
                     price=pr, window=seg.strip()[:80], valid=sorted(auth_ext[name]))
    # 3) col_proj 整段松散检查：出现一个完全不属于任何项目合法集的“孤价”（排除报销/车费/订金语境）
    if col_proj and col_proj in auth_ext:
        allvalid = set().union(*auth_ext.values())
        for pm in price_re.finditer(content):
            pr = int(float(pm.group(1)))
            if pr not in allvalid:
                w = content[max(0, pm.start()-15):pm.end()+15]
                if any(k in w for k in ("报销", "车费", "订金", "定金", "抵扣")):
                    continue
                flag(type="orphan_price_in_project_chunk", doc=doc, idx=idx,
                     project=col_proj, price=pr, window=w.strip())

by_type = defaultdict(list)
for x in conflicts: by_type[x["type"]].append(x)
print(f"=== 检出 {len(conflicts)} 条 ===\n")
for t, items in by_type.items():
    print(f"## {t}  ({len(items)})")
    for x in items[:50]:
        if t == "price_mismatch":
            print(f"  [{x['doc']}#{x['idx']}] {x['project']} 价{x['price']} 合法{x['valid']} | {x['window']}")
        elif t == "orphan_price_in_project_chunk":
            print(f"  [{x['doc']}#{x['idx']}] {x['project']} 孤价{x['price']} | {x['window']}")
        else:
            print(f"  [{x['doc']}#{x['idx']}] col={x.get('project_col')} | {x['snippet']}")
    print()

json.dump(conflicts, open(f"{base}/conflict_result.json", "w", encoding="utf-8"),
          ensure_ascii=False, indent=1, default=str)
print("WROTE conflict_result.json")
