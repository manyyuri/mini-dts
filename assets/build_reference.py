"""
从 lessons/*.md 里抽取「速查卡」表格，生成一份可打印的速查手册。

为什么用抽取而不是手写：速查卡是每节课的压缩core，手抄一遍必然与课程漂移。
课程改了，跑一次本脚本，手册自动跟上。

用法：python3 assets/build_reference.py
"""

import glob
import html
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LESSONS = sorted(glob.glob(os.path.join(ROOT, "lessons", "*.md")))
OUT = os.path.join(ROOT, "reference", "mini-dts-cheatsheet.html")


def parse(path):
    text = open(path, encoding="utf-8").read()
    title = re.search(r"^# (.+)$", text, re.M).group(1).strip()
    # 取「速查卡」小节里的第一张表
    block = re.search(r"## 速查卡\s*(.*?)(?=\n## |\Z)", text, re.S)
    rows = []
    if block:
        for line in block.group(1).splitlines():
            line = line.strip()
            if not line.startswith("|"):
                continue
            cells = [c.strip() for c in line.strip("|").split("|")]
            if all(set(c) <= set("-: ") for c in cells):
                continue          # 分隔行
            rows.append(cells)
    return title, rows[1:] if rows else []      # 去掉表头


def main():
    sections = []
    for path in LESSONS:
        title, rows = parse(path)
        if not rows:
            continue
        body = "\n".join(
            f"      <tr><th>{html.escape(r[0])}</th><td>{html.escape(r[1])}</td></tr>"
            for r in rows if len(r) >= 2)
        num = os.path.basename(path).split("-")[0]
        sections.append(
            f'  <section>\n    <h2><span class="num">{num}</span>{html.escape(title)}</h2>\n'
            f'    <table>\n{body}\n    </table>\n  </section>')

    doc = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>mini-dts 速查手册</title>
<link rel="stylesheet" href="../assets/lesson.css">
</head>
<body>
<header>
  <h1>mini-dts 速查手册</h1>
  <p class="sub">每节一张卡：只留「记住这一句就够」的部分。生成自 <code>lessons/*.md</code>，
     改了课就重跑 <code>assets/build_reference.py</code>。</p>
</header>
<main>
{chr(10).join(sections)}
</main>
<footer>
  <p>配套课程：<code>CURRICULUM.md</code>（主线 12 节 + 加餐 3 节）</p>
</footer>
</body>
</html>
"""
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    open(OUT, "w", encoding="utf-8").write(doc)
    print("wrote", OUT, "sections", len(sections))


if __name__ == "__main__":
    main()
