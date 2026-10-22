# -*- coding: utf-8 -*-
"""
批量把微信聊天长截图 OCR 成文本（学习客服引导逻辑用）。

原理：调阿里云百炼 qwen-vl-max 视觉模型（OpenAI 兼容端点），逐张识图。
成本：约 2 分钱/张。
用法：
  1. 设置环境变量 DASHSCOPE_API_KEY（本项目已有）
  2. python ocr_chat_screenshots.py <图片目录> [输出目录]
  3. 结果：输出目录下每张图一个 .txt（同名），外加 all_in_one.txt 合并版

小步验证：先传 --limit 1 只跑一张确认效果，再全量。
"""
import base64
import json
import os
import sys
import time
import urllib.request

API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
MODEL = "qwen-vl-max"
KEY = os.environ.get("DASHSCOPE_API_KEY", "")

# 针对"微信聊天截图"优化的识别指令：区分说话人、保留口语原文
OCR_PROMPT = (
    "这是一张微信聊天长截图。请把聊天内容逐条转成文本，要求：\n"
    "1. 每条消息一行，格式：【昵称】内容；昵称用原文，看不清楚就用【客户】/【客服】代替；\n"
    "2. 完整保留口语原文，不要改写、不要润色、不要省略；\n"
    "3. 语音消息转出的文字也照录；图片/表情包写【图片】/【表情】；\n"
    "4. 系统消息（如对方撤回）写【系统】内容；\n"
    "5. 保持消息先后顺序，从上到下。\n"
    "只输出聊天文本本身，不要任何解释。"
)


def ocr_image(path: str) -> str:
    with open(path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("utf-8")
    payload = {
        "model": MODEL,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b64}"}},
                {"type": "text", "text": OCR_PROMPT},
            ],
        }],
        "temperature": 0,
    }
    req = urllib.request.Request(
        API_URL,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {KEY}"},
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data["choices"][0]["message"]["content"]


def main():
    if not KEY:
        print("错误：环境变量 DASHSCOPE_API_KEY 未设置")
        sys.exit(1)
    src_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    out_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(src_dir, "ocr_out")
    limit = None
    if "--limit" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--limit") + 1])

    os.makedirs(out_dir, exist_ok=True)
    files = sorted(f for f in os.listdir(src_dir) if f.lower().endswith((".png", ".jpg", ".jpeg", ".webp")))
    if limit:
        files = files[:limit]

    print(f"共 {len(files)} 张图 → OCR 到 {out_dir}")
    merged = []
    for i, name in enumerate(files, 1):
        path = os.path.join(src_dir, name)
        print(f"[{i}/{len(files)}] {name} ...", end=" ", flush=True)
        try:
            text = ocr_image(path)
            base = os.path.splitext(name)[0]
            with open(os.path.join(out_dir, base + ".txt"), "w", encoding="utf-8") as f:
                f.write(text)
            merged.append(f"===== {name} =====\n{text}\n")
            print(f"OK ({len(text)} 字)")
        except Exception as e:
            print(f"失败: {e}")
        time.sleep(0.5)  # 温柔一点

    with open(os.path.join(out_dir, "all_in_one.txt"), "w", encoding="utf-8") as f:
        f.write("\n\n".join(merged))
    print(f"\n完成。合并版：{os.path.join(out_dir, 'all_in_one.txt')}")


if __name__ == "__main__":
    main()
