# -*- coding: utf-8 -*-
"""Current-version local evaluation without sending answers to an external judge."""
import json
import pathlib
import time
import urllib.parse
import urllib.request

import openpyxl

BASE = "http://127.0.0.1:8080"
HERE = pathlib.Path(__file__).resolve().parent


def ask(question: str) -> dict:
    url = BASE + "/api/chat/rag?" + urllib.parse.urlencode({"message": question})
    request = urllib.request.Request(url, method="POST")
    with urllib.request.urlopen(request, timeout=90) as response:
        return json.loads(response.read().decode("utf-8"))


def main():
    workbook = openpyxl.load_workbook(HERE / "评测集.xlsx", read_only=True)
    rows = list(workbook.active.iter_rows(min_row=2, values_only=True))
    print(json.dumps({"event": "start", "total": len(rows)}, ensure_ascii=False), flush=True)
    for number, category, question, standard in rows:
        started = time.perf_counter()
        try:
            result = ask(str(question))
            item = {
                "number": number,
                "category": category,
                "question": question,
                "standard": standard,
                "answer": result.get("answer", ""),
                "sources": result.get("sources", []),
                "images": result.get("images", []),
                "elapsedMs": round((time.perf_counter() - started) * 1000),
            }
        except Exception as error:
            item = {
                "number": number,
                "category": category,
                "question": question,
                "standard": standard,
                "error": str(error),
                "elapsedMs": round((time.perf_counter() - started) * 1000),
            }
        print(json.dumps(item, ensure_ascii=False), flush=True)
        time.sleep(2.1)


if __name__ == "__main__":
    main()
