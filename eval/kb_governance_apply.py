import psycopg2, json

BK = "C:/Users/JT/WorkBuddy/ai-customer-service/backup_kb_governance_2026-08-28.json"
conn = psycopg2.connect(host="127.0.0.1", port=5432, dbname="aics", user="postgres", password="postgres123")
cur = conn.cursor()

def snapshot(table, where, params=()):
    cur.execute(f"select * from {table} where {where}", params)
    cols = [d[0] for d in cur.description]
    return [dict(zip(cols, r)) for r in cur.fetchall()]

backup = {
    "faq_unreviewed_published": snapshot("knowledge_chunk", "doc_name='FAQ数据表.xlsx' and risk_level='unreviewed' and status='published'"),
    "faq_disabled_72": snapshot("knowledge_chunk", "doc_name='FAQ数据表.xlsx' and status='disabled'"),
    "business_fact_draft_2": snapshot("business_fact", "status='draft'"),
}
json.dump(backup, open(BK, "w", encoding="utf-8"), ensure_ascii=False, indent=1, default=str)
print(f"[backup] unreviewed={len(backup['faq_unreviewed_published'])} disabled={len(backup['faq_disabled_72'])} draft_facts={len(backup['business_fact_draft_2'])} -> {BK}")

# ---- 1) 两条问题话术内容修正 ----
cur.execute("update knowledge_chunk set content = replace(content, '多数客户一次可见改善', '改善情况因人而异，以到店专业评估为准'), update_time=now() where id=764 and content like '%多数客户一次可见改善%'")
print("[fix 764] rows:", cur.rowcount)
cur.execute("update knowledge_chunk set content = replace(content, '亲，我们现在很忙，店里客人比较多不太方便，要不您到线下门店来看吧。', '建议您先和我们预约，到店后会有老师专门接待您。'), update_time=now() where id=862 and content like '%现在很忙%'")
print("[fix 862] rows:", cur.rowcount)

# ---- 2) 其余 unreviewed 批量打审核标记（risk_level 与已发布 109 条一致取 high；safe_direct 不动）----
cur.execute("update knowledge_chunk set risk_level='high', update_time=now() where doc_name='FAQ数据表.xlsx' and risk_level='unreviewed' and status='published'")
print("[mark reviewed] rows:", cur.rowcount)

# ---- 3) 删除 2 条长期 draft 的 business_fact ----
cur.execute("delete from business_fact where fact_key in ('activity.availability','service.pickupPolicy') and status='draft'")
print("[del draft facts] rows:", cur.rowcount)

# ---- 4) 删除 72 条 disabled FAQ（已备份）----
cur.execute("delete from knowledge_chunk where doc_name='FAQ数据表.xlsx' and status='disabled'")
print("[del disabled faq] rows:", cur.rowcount)

conn.commit()

# ---- 5) 验证 ----
cur.execute("select count(*) from knowledge_chunk where risk_level='unreviewed' and status='published'")
print("[verify] unreviewed+published =", cur.fetchone()[0])
cur.execute("select status, count(*) from knowledge_chunk where doc_name='FAQ数据表.xlsx' group by status")
print("[verify] FAQ by status =", cur.fetchall())
cur.execute("select count(*) from business_fact")
print("[verify] business_fact rows =", cur.fetchone()[0])
cur.execute("select chunk_index, content from knowledge_chunk where id in (764,862)")
for r in cur.fetchall(): print("[verify content]", r[0], r[1][:60].replace(chr(10), ' / '))
cur.close(); conn.close()
print("GOVERNANCE DONE")
