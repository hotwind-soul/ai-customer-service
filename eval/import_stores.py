# -*- coding: utf-8 -*-
"""
一次性脚本：把 门店.xlsx 导入结构化的 store 表（通过 /stores 接口）。
从 map_link 里解析腾讯地图坐标（coord=纬度,经度）。
运行：python import_stores.py   （需服务已重启、store 表已建好）
"""
import json
import os
import re
import urllib.request

import openpyxl

XLSX = "../../temp_kb/门店.xlsx"
# 后台已取消固定管理口令；脚本先用管理员账号登录，再使用短期登录令牌。
ADMIN_USERNAME = os.environ.get("AICS_ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = os.environ.get("AICS_ADMIN_PASSWORD", "")
ROOT = "http://localhost:8080"
BASE = ROOT + "/stores"


def login():
    if not ADMIN_PASSWORD:
        raise RuntimeError("请先设置 AICS_ADMIN_PASSWORD；账号可用 AICS_ADMIN_USERNAME 指定")
    body = json.dumps({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}).encode("utf-8")
    req = urllib.request.Request(ROOT + "/agent/login", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        result = json.loads(resp.read().decode("utf-8"))
    if not result.get("ok") or result.get("role") not in ("admin", "super_admin"):
        raise RuntimeError("管理员登录失败或账号没有后台权限")
    if result.get("role") == "admin" and "stores.manage" not in result.get("permissions", []):
        raise RuntimeError("当前管理员没有“门店资料”权限")
    return result["token"]


def main():
    admin_token = login()
    wb = openpyxl.load_workbook(XLSX)
    ws = wb.active
    rows = list(ws.iter_rows(values_only=True))
    header = rows[0]
    print("列：", header)

    ok, fail = 0, 0
    for row in rows[1:]:
        city, district, name, address, project, map_link = (list(row) + [None] * 6)[:6]
        if not city or not name:
            continue
        lat = lng = None
        if map_link:
            m = re.search(r"coord=([\d.]+),([\d.]+)", str(map_link))
            if m:
                lat, lng = float(m.group(1)), float(m.group(2))

        body = json.dumps({
            "city": str(city), "district": str(district or ""),
            "name": str(name), "address": str(address or ""),
            "lat": lat, "lng": lng,
            "projects": str(project or ""),
        }).encode("utf-8")
        req = urllib.request.Request(BASE, data=body,
                                     headers={"Content-Type": "application/json",
                                              "X-Admin-Token": admin_token})
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                json.loads(resp.read().decode("utf-8"))
            ok += 1
        except Exception as e:
            fail += 1
            print(f"失败: {city} {name} -> {e}")

    print(f"完成：成功 {ok} 家，失败 {fail} 家")


if __name__ == "__main__":
    main()
