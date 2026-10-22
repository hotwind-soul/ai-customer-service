import json, re
from collections import defaultdict

base = "C:/Users/JT/WorkBuddy/新/ai-customer-service/eval"
kc = json.load(open(f"{base}/dump_knowledge_chunk.json", encoding="utf-8"))
proj = json.load(open(f"{base}/dump_projects.json", encoding="utf-8"))

known = set(r["name"] for r in proj["service_project"])
for a in proj["alias"]:
    known.add(a["alias"])

price_re = re.compile(r"(\d+(?:\.\d+)?)\s*元")
# 健全性：KB 里到底扫到多少价格
total_price_hits = 0
per_doc = defaultdict(int)
for c in kc:
    n = len(price_re.findall(c.get("content") or ""))
    total_price_hits += n
    if n: per_doc[c["doc_name"]] += n
print(f"[sanity] KB 价格提及总数={total_price_hits}, 含价格的文档={dict(per_doc)}")

# 扩展1：KB 提到但项目中心不存在的项目名（常见医美项目词）
suspect = ["美白","嫩肤","祛痘","祛痘印","隆鼻","瘦脸","瘦脸针","植发","丰胸","隆胸","双眼皮",
           "纹眉","脱毛","水光针","光子嫩肤","热玛吉","超声刀","线雕","溶脂","抽脂","丰唇","隆下巴"]
print("\n[扩展1] KB 提到但项目中心没有的项目词：")
for term in suspect:
    hits = [f"{c['doc_name']}#{c['chunk_index']}" for c in kc if term in (c.get("content") or "")]
    if hits:
        print(f"  ⚠ {term}: {len(hits)} 处  e.g. {hits[:3]}")

# 扩展2：杭州/北京 门店断言（已知：杭州/北京无门店）
print("\n[扩展2] KB 中杭州/北京相关提及：")
for city in ["杭州","北京"]:
    hits = []
    for c in kc:
        if city in (c.get("content") or ""):
            # 取上下文
            i = c["content"].index(city)
            ctx = c["content"][max(0,i-25):i+25]
            hits.append((f"{c['doc_name']}#{c['chunk_index']}", ctx))
    if hits:
        print(f"  {city}: {len(hits)} 处")
        for h in hits[:6]:
            print(f"     {h[0]} …{h[1]}…")

# 扩展3：门店覆盖总数一致性（KB 是否宣称固定门店数/城市数）
print("\n[扩展3] KB 中门店数/城市数宣称：")
claims = []
for c in kc:
    for m in re.finditer(r"(\d+)\s*(家|个城市|座城市|家门店)", c.get("content") or ""):
        i = m.start()
        claims.append((f"{c['doc_name']}#{c['chunk_index']}", c["content"][max(0,i-20):i+20]))
for h in claims[:20]:
    print(f"   {h[0]} …{h[1]}…")
print(f"  (项目中心 store 表共 {len(proj['store'])} 家, store_project 关联 {len(proj['store_project'])} 条)")
