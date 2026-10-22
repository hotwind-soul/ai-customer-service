import psycopg2, json

OUT = "C:/Users/JT/WorkBuddy/新/ai-customer-service/eval"
conn = psycopg2.connect(host="127.0.0.1", port=5432, dbname="aics", user="postgres", password="postgres123")
cur = conn.cursor()

def cols_of(table):
    cur.execute("select column_name from information_schema.columns where table_name=%s order by ordinal_position", (table,))
    return [r[0] for r in cur.fetchall()]

def dump(table, where=""):
    cs = cols_of(table)
    ph = ", ".join(f'"{c}"' for c in cs)
    cur.execute(f"select {ph} from {table} {where}")
    return [dict(zip(cs, r)) for r in cur.fetchall()]

sp = dump("service_project")
alias = dump("service_project_alias")
offer = dump("project_offer")
sp_store = dump("store_project")
# 门店名（用于门店冲突）
try:
    stores = dump("store")
except Exception as e:
    stores = []

with open(f"{OUT}/dump_projects.json","w",encoding="utf-8") as f:
    json.dump({"service_project":sp,"alias":alias,"offer":offer,
               "store_project":sp_store,"store":stores}, f, ensure_ascii=False, indent=1, default=str)

print(f"[service_project] {len(sp)} rows")
for r in sp: print("   ", r)
print(f"[alias] {len(alias)} rows")
print(f"[offer] {len(offer)} rows")
for r in offer: print("   ", r)
print(f"[store_project] {len(sp_store)} rows")
print(f"[store] {len(stores)} rows")
cur.close(); conn.close()
print("PROJECTS DUMP DONE")
