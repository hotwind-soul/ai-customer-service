import psycopg2, json, sys

OUT = "C:/Users/JT/WorkBuddy/新/ai-customer-service/eval"
conn = psycopg2.connect(host="127.0.0.1", port=5432, dbname="aics", user="postgres", password="postgres123")
cur = conn.cursor()

# ---------- 项目中心: business_fact ----------
cur.execute("select column_name from information_schema.columns where table_name='business_fact' order by ordinal_position")
bf_cols = [r[0] for r in cur.fetchall()]
cur.execute("select * from business_fact order by 1")
bf_rows = [dict(zip(bf_cols, r)) for r in cur.fetchall()]
# 去掉可能很大的 blob 列（如有）
bf_clean = []
for d in bf_rows:
    nd = {}
    for k, v in d.items():
        s = str(v)
        nd[k] = (s[:400] if len(s) > 400 else s)
    bf_clean.append(nd)
with open(f"{OUT}/dump_business_fact.json", "w", encoding="utf-8") as f:
    json.dump(bf_clean, f, ensure_ascii=False, indent=1, default=str)
print(f"[business_fact] cols={bf_cols}")
print(f"[business_fact] rows={len(bf_rows)}")

# ---------- 知识库: knowledge_chunk（排除 embedding 向量） ----------
cur.execute("select column_name from information_schema.columns where table_name='knowledge_chunk' order by ordinal_position")
kc_cols = [r[0] for r in cur.fetchall()]
kc_cols_no_emb = [c for c in kc_cols if c != "embedding"]
ph = ", ".join(f'"{c}"' for c in kc_cols_no_emb)
cur.execute(f"select {ph} from knowledge_chunk order by doc_name, chunk_index")
kc_rows = [dict(zip(kc_cols_no_emb, r)) for r in cur.fetchall()]
with open(f"{OUT}/dump_knowledge_chunk.json", "w", encoding="utf-8") as f:
    json.dump(kc_rows, f, ensure_ascii=False, indent=1, default=str)
print(f"[knowledge_chunk] cols={kc_cols_no_emb}")
print(f"[knowledge_chunk] rows={len(kc_rows)}")

# 快速画像
cur.execute("select doc_name, count(*) from knowledge_chunk group by doc_name order by doc_name")
print("[knowledge_chunk] by doc_name:")
for d, n in cur.fetchall():
    print(f"   {n:4d}  {d}")
cur.execute("select project, count(*) from knowledge_chunk group by project order by project")
print("[knowledge_chunk] by project column:")
for p, n in cur.fetchall():
    print(f"   {n:4d}  {p}")

cur.close(); conn.close()
print("DUMP DONE")
