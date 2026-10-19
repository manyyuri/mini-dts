"""
mini-oms 教学配图生成器（可复用组件）

设计约束：
- 无外部依赖（只用 Pillow），字体走 macOS 自带 PingFang SC
- 所有文本先量宽再排版，禁止硬编码假宽度（避免溢出/重叠）
- 输出 PNG（静态图）与 GIF（帧布局固定，只变高亮，避免布局跳动）

用法：
    python3 assets/diagrams.py            # 生成全部配图到 assets/img/
    python3 assets/diagrams.py panorama   # 只生成某一张
"""

import os
import sys

from PIL import Image, ImageDraw, ImageFont

FONT_PATH = "/System/Library/Fonts/PingFang.ttc"
REGULAR = 2      # PingFang SC Regular
MEDIUM = 5       # PingFang SC Medium
SEMIBOLD = 8     # PingFang SC Semibold

INK = (31, 41, 55)
MUTED = (107, 114, 128)
FAINT = (203, 213, 225)
BLUE = (37, 99, 235)
BLUE_SOFT = (219, 234, 254)
GREEN = (5, 150, 105)
GREEN_SOFT = (209, 250, 229)
AMBER = (180, 83, 9)
AMBER_SOFT = (254, 243, 199)
RED = (190, 18, 60)
RED_SOFT = (254, 226, 226)
PAPER = (252, 252, 251)
CARD = (255, 255, 255)

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "img")


def font(size, weight=REGULAR):
    return ImageFont.truetype(FONT_PATH, size, index=weight)


class Canvas:
    """极简绘图工具：只提供本课程需要的原语，宽度一律用真实文本度量。"""

    def __init__(self, width, height, bg=PAPER):
        self.img = Image.new("RGB", (width, height), bg)
        self.d = ImageDraw.Draw(self.img)

    # ── 基础 ─────────────────────────────────────────────
    def text(self, x, y, s, size=20, weight=REGULAR, fill=INK, anchor="lt"):
        self.d.text((x, y), s, font=font(size, weight), fill=fill, anchor=anchor)

    def width(self, s, size=20, weight=REGULAR):
        return self.d.textlength(s, font=font(size, weight))

    def wrap(self, s, max_width, size=20, weight=REGULAR):
        """按真实宽度折行：中文按字切，连续的英文/数字当一个整体（避免把 12:00:00 拆开）。"""
        import re

        tokens = re.findall(r"[A-Za-z0-9_./:+-]+|.", s)
        lines, cur = [], ""
        for tk in tokens:
            if self.width(cur + tk, size, weight) <= max_width:
                cur += tk
            else:
                if cur:
                    lines.append(cur)
                cur = tk
        if cur:
            lines.append(cur)
        return lines

    def card(self, x, y, w, h, title, lines=(), accent=BLUE, soft=BLUE_SOFT,
             title_size=22, body_size=18, badge=None):
        """圆角卡片：标题 + 若干正文行，正文自动折行，返回卡片底边 y。"""
        self.d.rounded_rectangle([x, y, x + w, y + h], radius=14, fill=CARD, outline=FAINT, width=2)
        self.d.rounded_rectangle([x, y, x + w, y + 6], radius=3, fill=accent)
        pad = 18
        ty = y + pad
        if badge:
            bw = self.width(badge, 15, MEDIUM) + 18
            self.d.rounded_rectangle([x + w - pad - bw, ty - 2, x + w - pad, ty + 22], radius=11, fill=soft)
            self.text(x + w - pad - bw / 2, ty + 10, badge, 15, MEDIUM, accent, anchor="mm")
        self.text(x + pad, ty, title, title_size, SEMIBOLD, INK)
        cy = ty + title_size + 12
        for line in lines:
            for piece in self.wrap(line, w - 2 * pad, body_size, REGULAR):
                self.text(x + pad, cy, piece, body_size, REGULAR, MUTED)
                cy += body_size + 8
        return y + h

    def title(self, s, sub=None, x=56, y=44):
        self.text(x, y, s, 40, SEMIBOLD, INK)
        if sub:
            self.text(x, y + 58, sub, 21, REGULAR, MUTED)

    # ── 连线 ─────────────────────────────────────────────
    def arrow(self, x1, y1, x2, y2, color=FAINT, label=None, label_size=16, width=3, dash=False):
        if dash:
            self._dashed(x1, y1, x2, y2, color, width)
        else:
            self.d.line([x1, y1, x2, y2], fill=color, width=width)
        self._head(x1, y1, x2, y2, color)
        if label:
            mx, my = (x1 + x2) / 2, (y1 + y2) / 2
            tw = self.width(label, label_size, MEDIUM) + 20
            self.d.rounded_rectangle([mx - tw / 2, my - 15, mx + tw / 2, my + 15], radius=8, fill=PAPER)
            self.text(mx, my, label, label_size, MEDIUM, MUTED, anchor="mm")

    def _dashed(self, x1, y1, x2, y2, color, width, seg=10):
        import math
        length = math.hypot(x2 - x1, y2 - y1)
        if length == 0:
            return
        ux, uy = (x2 - x1) / length, (y2 - y1) / length
        pos = 0
        while pos < length:
            end = min(pos + seg, length)
            self.d.line([x1 + ux * pos, y1 + uy * pos, x1 + ux * end, y1 + uy * end], fill=color, width=width)
            pos += seg * 2

    def _head(self, x1, y1, x2, y2, color, size=11):
        import math
        angle = math.atan2(y2 - y1, x2 - x1)
        for sign in (1, -1):
            self.d.polygon([
                (x2, y2),
                (x2 - size * math.cos(angle - sign * 0.42), y2 - size * math.sin(angle - sign * 0.42)),
                (x2 - size * math.cos(angle), y2 - size * math.sin(angle)),
            ], fill=color)

    def save(self, name):
        os.makedirs(OUT_DIR, exist_ok=True)
        path = os.path.join(OUT_DIR, name)
        self.img.save(path)
        print("wrote", path, Image.open(path).size)
        return path


# ── 图 1：全景架构 ────────────────────────────────────────────
def panorama():
    c = Canvas(1720, 1060)
    c.title("mini-oms 全景：两个数据库 + 三条数据通道 + 一条控制线",
            "左源右目标，中间负责「搬过去 / 跟着变 / 对一遍」，下方控制面管任务的一生")

    # 左：源库
    c.d.rounded_rectangle([56, 200, 396, 620], radius=18, fill=CARD, outline=FAINT, width=2)
    c.text(76, 222, "源库 MySQL", 26, SEMIBOLD, INK)
    c.text(76, 260, "业务表 + binlog 流水账", 17, REGULAR, MUTED)
    c.d.rounded_rectangle([76, 300, 376, 386], radius=12, fill=BLUE_SOFT)
    c.text(96, 314, "orders / items …", 19, MEDIUM, INK)
    c.text(96, 346, "业务在读写的表", 16, REGULAR, MUTED)
    c.d.rounded_rectangle([76, 406, 376, 598], radius=12, fill=AMBER_SOFT)
    c.text(96, 420, "binlog 流水账", 19, MEDIUM, INK)
    c.text(96, 454, "每次改动记一笔：", 16, REGULAR, MUTED)
    c.text(96, 478, "事务号 / 哪行 / 改成啥", 16, REGULAR, MUTED)
    c.text(96, 524, "必须 ROW 格式", 16, MEDIUM, AMBER)
    c.text(96, 550, "才能拿到「行」级变更", 16, REGULAR, MUTED)

    # 中：三条通道
    lanes = [
        (200, "全量迁移", "mini-oms-full", GREEN, GREEN_SOFT,
         ["按主键把大表切成一份份", "并行搬运 + 限速", "搬到哪就记在哪（断点）"]),
        (380, "增量订阅", "mini-oms-store", BLUE, BLUE_SOFT,
         ["跟着 binlog 往下走", "一个事务一批，保持顺序", "断线自动重连"]),
        (560, "数据校验", "mini-oms-verifier", AMBER, AMBER_SOFT,
         ["用同样的切法算 CRC", "不一致就逐行比对", "只报告，不改数据"]),
    ]
    for y, name, module, accent, soft, lines in lanes:
        c.d.rounded_rectangle([470, y, 890, y + 150], radius=14, fill=CARD, outline=FAINT, width=2)
        c.d.rounded_rectangle([470, y, 890, y + 6], radius=3, fill=accent)
        c.text(490, y + 20, name, 24, SEMIBOLD, INK)
        c.text(650, y + 26, module, 16, MEDIUM, accent)
        cy = y + 62
        for line in lines:
            c.text(490, cy, line, 18, REGULAR, MUTED)
            cy += 28

    # 右：目标库
    c.d.rounded_rectangle([966, 200, 1306, 620], radius=18, fill=CARD, outline=FAINT, width=2)
    c.text(986, 222, "目标库 MySQL", 26, SEMIBOLD, INK)
    c.text(986, 260, "同名库 + 同名表", 17, REGULAR, MUTED)
    c.d.rounded_rectangle([986, 300, 1286, 382], radius=12, fill=GREEN_SOFT)
    c.text(1006, 314, "业务表（搬运结果）", 19, MEDIUM, INK)
    c.text(1006, 346, "写入是 UPSERT，可重复执行", 15, REGULAR, MUTED)
    c.d.rounded_rectangle([986, 402, 1286, 598], radius=12, fill=(241, 245, 249))
    c.text(1006, 416, "自用表（记账本）", 19, MEDIUM, INK)
    for i, line in enumerate(["oms_incr_checkpoint  增量进度",
                              "oms_full_checkpoint  全量进度",
                              "oms_dead_letter      失败存根",
                              "oms_ddl_event        结构变更",
                              "oms_schema_fingerprint 结构指纹"]):
        c.text(1006, 456 + i * 28, line, 16, REGULAR, MUTED)

    # 源 → 三条通道
    c.arrow(396, 340, 470, 268, GREEN, "分片读")
    c.arrow(396, 460, 470, 448, BLUE, "订阅")
    c.arrow(396, 566, 470, 628, AMBER, "回读")
    # 三条通道 → 目标（校验是回读目标，箭头反向）
    c.arrow(890, 268, 966, 336, GREEN, "UPSERT")
    c.arrow(890, 448, 966, 356, BLUE, "同事务")
    c.arrow(966, 512, 890, 628, AMBER, "回读")

    # 控制面
    c.d.rounded_rectangle([400, 740, 1320, 936], radius=18, fill=CARD, outline=FAINT, width=2)
    c.text(424, 760, "控制面 mini-oms-cm　＋　编排 mini-oms-starter", 24, SEMIBOLD, INK)
    cells = [
        ("任务账本", "oms_task：参数、状态、进度"),
        ("状态机", "全量 → 增量 → 完成"),
        ("任务租约", "同一任务只许一个节点跑"),
        ("启动恢复", "重启后接回「在跑」的任务"),
    ]
    for i, (name, desc) in enumerate(cells):
        x = 424 + i * 224
        c.d.rounded_rectangle([x, 806, x + 204, 904], radius=12, fill=(248, 250, 252), outline=FAINT, width=1)
        c.text(x + 16, 820, name, 19, SEMIBOLD, INK)
        for j, piece in enumerate(c.wrap(desc, 172, 15, REGULAR)):
            c.text(x + 16, 852 + j * 20, piece, 15, REGULAR, MUTED)

    # 控制面 → 通道（短虚线，避免穿图）
    c._dashed(700, 704, 700, 738, FAINT, 3)
    c.arrow(700, 726, 700, 738, FAINT)
    c.text(716, 722, "下发任务 / 收进度", 15, MEDIUM, MUTED)

    c.text(56, 972, "一句话：源库照常跑业务，mini-oms 在旁边把数据搬过去，并且一直跟着变，还能随时说清「搬到哪了」。",
           22, MEDIUM, INK)
    return c.save("fig1-panorama.png")


# ── 图 5：崩溃到追平的动画（GIF）────────────────────────────
def crash_animation():
    W, H = 1180, 620
    frames, captions = [], []

    def base(caption, note=None):
        c = Canvas(W, H)
        c.text(56, 40, "搬家搬到一半断电了，靠什么不丢不重？", 32, SEMIBOLD, INK)
        legend = [("蓝块", BLUE_SOFT, BLUE), ("灰块", (241, 245, 249), MUTED),
                  ("✓", PAPER, GREEN), ("绿块", GREEN_SOFT, GREEN)]
        labels = ["已搬完并记下断点", "还没搬", "重启后直接跳过", "增量补上的新写入"]
        lx = 56
        for (chip, fill, line), label in zip(legend, labels):
            c.d.rounded_rectangle([lx, 96, lx + 26, 116], radius=6, fill=fill, outline=line, width=2)
            if chip == "✓":
                c.d.line([lx + 6, 106, lx + 12, 112], fill=GREEN, width=3)
                c.d.line([lx + 12, 112, lx + 21, 100], fill=GREEN, width=3)
            lx += 34
            c.text(lx, 95, label, 19, REGULAR, MUTED)
            lx += c.width(label, 19, REGULAR) + 34
        # 两侧库
        c.d.rounded_rectangle([56, 150, 296, 250], radius=14, fill=CARD, outline=FAINT, width=2)
        c.text(76, 168, "源库", 22, SEMIBOLD, INK)
        c.text(76, 204, "20 个分片", 17, REGULAR, MUTED)
        c.d.rounded_rectangle([884, 150, 1124, 250], radius=14, fill=CARD, outline=FAINT, width=2)
        c.text(904, 168, "目标库", 22, SEMIBOLD, INK)
        c.text(904, 204, "只认真实写入", 17, REGULAR, MUTED)
        # 说明条
        c.d.rounded_rectangle([56, 480, 1124, 556], radius=14, fill=AMBER_SOFT)
        c.text(76, 500, caption, 21, MEDIUM, AMBER)
        if note:
            c.text(76, 528, note, 17, REGULAR, MUTED)
        return c

    def chunks(c, done, skipped=(), crashed=False):
        """20 个分片：10 列 × 2 行，位置固定，只有颜色变化（帧间不跳动）。"""
        for i in range(20):
            col, row = i % 10, i // 10
            x = 356 + col * 54
            y = 196 + row * 74
            if i < done:
                fill, outline, txt, tcolor = BLUE_SOFT, BLUE, str(i + 1), BLUE
            else:
                fill, outline, txt, tcolor = (241, 245, 249), FAINT, str(i + 1), MUTED
            c.d.rounded_rectangle([x, y, x + 44, y + 44], radius=10, fill=fill, outline=outline, width=2)
            c.text(x + 22, y + 22, txt, 17, MEDIUM, tcolor, anchor="mm")
            if i in skipped:
                c.d.line([x + 8, y + 22, x + 20, y + 34], fill=GREEN, width=4)
                c.d.line([x + 20, y + 34, x + 36, y + 10], fill=GREEN, width=4)
        if crashed:
            # 放在分片网格右侧的空白处，避免压住编号
            c.d.line([948, 296, 1060, 408], fill=RED, width=12)
            c.d.line([1060, 296, 948, 408], fill=RED, width=12)
            c.text(1004, 436, "进程挂了", 24, SEMIBOLD, RED, anchor="mm")

    # 帧 1：开始搬
    c = base("① 全量开始：按主键把大表切成 20 份，一份一份搬，每搬完一份就在目标库记一笔断点。")
    chunks(c, 4)
    frames.append(c.img)
    captions.append("frame1")

    # 帧 2：搬到一半
    c = base("② 搬到第 10 份时，数据已经写进目标库，断点也记到了第 10 份。")
    chunks(c, 10)
    frames.append(c.img)

    # 帧 3：崩溃
    c = base("③ 断电/重启：内存里的进度全没了。目标库上「已写的数据 + 已记的断点」还在。", 
             "注意：可能有一份数据写了但断点没记上 —— 这份会被重做一遍。")
    chunks(c, 10, crashed=True)
    frames.append(c.img)

    # 帧 4：重启跳过
    c = base("④ 重启接着搬：读出断点，前 10 份直接跳过（打勾），从第 11 份继续。",
             "即使重做那一份也没关系：写入是 UPSERT，同一行写两次结果一样。")
    chunks(c, 10, skipped=set(range(10)))
    frames.append(c.img)

    # 帧 5：全量完成
    c = base("⑤ 20 份全部搬完，全量结束。此时源库还在被业务写入，目标库已经落后了。")
    chunks(c, 20)
    frames.append(c.img)

    # 帧 6：增量追平
    c = base("⑥ 增量接手：从「全量开始前记下的位点」回放 binlog，把搬家期间和之后的修改补齐。",
             "追平后做一次 CRC 校验：两边逐片比对，结果一致才算迁移成功。")
    chunks(c, 20)
    for i in range(20):
        col, row = i % 10, i // 10
        x = 356 + col * 54
        y = 196 + row * 74
        c.d.rounded_rectangle([x, y, x + 44, y + 44], radius=10, fill=GREEN_SOFT, outline=GREEN, width=2)
        c.text(x + 22, y + 22, str(i + 1), 17, MEDIUM, GREEN, anchor="mm")
    c.arrow(296, 430, 884, 430, GREEN, "binlog 回放补差", label_size=17)
    c.text(590, 452, "补齐后两边一致 ✔", 20, SEMIBOLD, GREEN, anchor="mm")
    frames.append(c.img)

    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, "fig5-crash-recovery.gif")
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=[1500, 1500, 2000,
                                                                          1800, 1500, 2500, 2500],
                   loop=0, optimize=False)
    print("wrote", path, "frames", len(frames))
    return path




# ══════════════════════════════════════════════════════════════
# 课程通用图元：横向流水线 / 网格 / 时间轴 / 矩阵 / 漏斗
# ══════════════════════════════════════════════════════════════

class Lesson(Canvas):
    """课程图统一版式：标题 + 副标题 + 结论行，中间自由布局。"""

    def __init__(self, width=1600, height=900, title="", subtitle="", takeaway=None):
        super().__init__(width, height)
        self.title(title, subtitle)
        self._takeaway = takeaway

    def close(self, name):
        if self._takeaway:
            self.d.rounded_rectangle([56, self.img.height - 96, self.img.width - 56, self.img.height - 32],
                                     radius=12, fill=AMBER_SOFT)
            for i, piece in enumerate(self.wrap(self._takeaway, self.img.width - 180, 21, MEDIUM)):
                self.text(76, self.img.height - 82 + i * 26, piece, 21, MEDIUM, AMBER)
        return self.save(name)

    def pipeline(self, items, y, height=132, gap=64, x0=56, x1=None):
        """横向流水线：每个 item = (标题, [正文行], 颜色, 底色)。返回每段中心 x。"""
        x1 = x1 or self.img.width - 56
        n = len(items)
        w = (x1 - x0 - gap * (n - 1)) // n
        centers = []
        for i, (t, lines, accent, soft) in enumerate(items):
            x = x0 + i * (w + gap)
            self.d.rounded_rectangle([x, y, x + w, y + height], radius=14, fill=CARD, outline=FAINT, width=2)
            self.d.rounded_rectangle([x, y, x + w, y + 6], radius=3, fill=accent)
            self.text(x + 18, y + 18, t, 21, SEMIBOLD, INK)
            cy = y + 54
            for line in lines:
                for piece in self.wrap(line, w - 36, 16, REGULAR):
                    self.text(x + 18, cy, piece, 16, REGULAR, MUTED)
                    cy += 22
            centers.append((x + w / 2, x, w))
            if i < n - 1:
                self.arrow(x + w + 6, y + height / 2, x + w + gap - 6, y + height / 2, accent)
        return centers

    def chip(self, x, y, label, fill, color, size=15, pad=12, h=28):
        w = self.width(label, size, MEDIUM) + pad * 2
        self.d.rounded_rectangle([x, y, x + w, y + h], radius=h // 2, fill=fill)
        self.text(x + pad, y + h / 2, label, size, MEDIUM, color, anchor="lm")
        return w

    def note(self, x, y, w, text, accent=AMBER, soft=AMBER_SOFT, title=None):
        lines = self.wrap(text, w - 36, 18, REGULAR)
        h = 30 + (26 if title else 0) + len(lines) * 26
        self.d.rounded_rectangle([x, y, x + w, y + h], radius=12, fill=soft)
        cy = y + 14
        if title:
            self.text(x + 18, cy, title, 19, SEMIBOLD, accent)
            cy += 26
        for line in lines:
            self.text(x + 18, cy, line, 18, REGULAR, accent)
            cy += 26
        return y + h


# ── L2 数据契约 ────────────────────────────────────────────────
def l2_contract():
    c = Lesson(1600, 940, "L2 数据契约：三个通道共用一套「行」的写法",
               "全量、增量、校验都要描述「同一行数据」，如果各写一套，校验就永远对不上",
               takeaway="一句话：行先归一化成一个结构（RowRecord），再归一化成一段字节（RowCodec）"
                        "—— 谁都不许自己拼 SQL、自己格式化时间。")

    lanes = [("全量通道", ["从源库 SELECT 出来", "一堆 ResultSet 行"], GREEN, GREEN_SOFT),
             ("增量通道", ["从 binlog 反序列化", "一堆 Serializable[]"], BLUE, BLUE_SOFT),
             ("校验通道", ["两边各 SELECT 一次", "准备比对"], AMBER, AMBER_SOFT)]
    for i, (t, lines, accent, soft) in enumerate(lanes):
        y = 170 + i * 150
        c.d.rounded_rectangle([56, y, 396, y + 124], radius=14, fill=CARD, outline=FAINT, width=2)
        c.d.rounded_rectangle([56, y, 62, y + 124], radius=3, fill=accent)
        c.text(82, y + 18, t, 21, SEMIBOLD, INK)
        for j, line in enumerate(lines):
            c.text(82, y + 56 + j * 24, line, 16, REGULAR, MUTED)
        c.arrow(400, y + 62, 470, 400, accent)

    # 中间：RowRecord
    c.d.rounded_rectangle([470, 240, 900, 560], radius=16, fill=CARD, outline=BLUE, width=3)
    c.text(494, 262, "统一结构：一行 = RowRecord", 22, SEMIBOLD, BLUE)
    fields = [("schema / table", "它属于哪张表"),
              ("columns", "列名清单（顺序敏感）"),
              ("values", "对应的值（允许 NULL）"),
              ("op", "UPSERT 还是 DELETE"),
              ("txId", "来自哪个事务 / 哪个分片")]
    cy = 306
    for name, desc in fields:
        c.text(494, cy, name, 17, SEMIBOLD, INK)
        c.text(700, cy, desc, 16, REGULAR, MUTED)
        cy += 34
    c.text(494, 486, "列数 != 值个数 → 直接报错，不允许「将就」", 16, MEDIUM, RED)

    # 右：RowCodec
    c.d.rounded_rectangle([980, 240, 1330, 560], radius=16, fill=CARD, outline=AMBER, width=3)
    c.text(1004, 262, "统一编码：RowCodec", 22, SEMIBOLD, AMBER)
    for i, line in enumerate(["把一个值变成一段稳定的字节：",
                              "· 时间戳统一精度（不丢毫秒）",
                              "· 小数统一 scale（1.0 与 1.00 相同）",
                              "· 二进制按 hex，NULL 有专门标记",
                              "· 列顺序按定义顺序"]):
        c.text(1004, 306 + i * 30, line, 16, REGULAR, MUTED)
    c.arrow(904, 400, 976, 400, AMBER)

    # 用途
    c.d.rounded_rectangle([1370, 244, 1544, 344], radius=12, fill=GREEN_SOFT)
    c.text(1388, 262, "用途 1", 18, SEMIBOLD, GREEN)
    c.text(1388, 292, "写库参数", 16, REGULAR, MUTED)
    c.arrow(1334, 300, 1366, 300, GREEN)
    c.d.rounded_rectangle([1370, 456, 1544, 556], radius=12, fill=AMBER_SOFT)
    c.text(1388, 474, "用途 2", 18, SEMIBOLD, AMBER)
    c.text(1388, 504, "算 CRC", 16, REGULAR, MUTED)
    c.arrow(1334, 500, 1366, 500, AMBER)

    c.note(470, 600, 860,
           "校验报「不一致」时，第一嫌疑永远是编码口径不同：一边把 2026-01-01 12:00:00.000 写成 12:00:00，"
           "另一边带毫秒，CRC 就永远对不上。共用一套编码，等于把这类假差异从设计上消掉。",
           AMBER, AMBER_SOFT, "不做会怎样")
    c.note(1370, 600, 174,
           "同一行，两条路径产出必须逐字节相同。", GREEN, GREEN_SOFT, "验收标准")
    return c.close("fig-l2-contract.png")


# ── L3 主键切片 ────────────────────────────────────────────────
def l3_chunks():
    c = Lesson(1600, 900, "L3 主键即一切：一张表怎么被切成 N 片",
               "切片、断点、幂等、校验边界，四件事共用同一个东西 —— 主键上界",
               takeaway="一句话：每片记一个「主键上界」，跑完就记下这个上界；下次跑只要问「哪些上界已经记过了」。")

    # 主键有序轴
    c.text(56, 170, "① 主键是有序的：沿主键把表「切一刀」", 20, SEMIBOLD, INK)
    c.d.line([80, 250, 1520, 250], fill=FAINT, width=3)
    for i in range(9):
        x = 80 + i * 180
        c.d.line([x, 238, x, 262], fill=MUTED, width=2)
        c.text(x, 268, str(i * 1000), 15, REGULAR, MUTED, anchor="mt")
    c.arrow(80, 250, 1520, 250, BLUE)

    # 分片网格
    c.text(56, 330, "② 切成 8 片，每片记一个上界：不含左边界、包含右边界", 20, SEMIBOLD, INK)
    for i in range(8):
        x = 80 + i * 180
        c.d.rounded_rectangle([x, 380, x + 156, 470], radius=10, fill=GREEN_SOFT, outline=GREEN, width=2)
        c.text(x + 78, 396, f"分片 {i + 1}", 16, SEMIBOLD, GREEN, anchor="mt")
        c.text(x + 78, 424, f"({i * 1000}, {(i + 1) * 1000}]", 15, REGULAR, MUTED, anchor="mt")
        c.text(x + 78, 496, f"上界 {(i + 1) * 1000}", 14, REGULAR, MUTED, anchor="mt")

    # 四个用途
    c.text(56, 550, "③ 同一个上界，被四处复用", 20, SEMIBOLD, INK)
    uses = [("切片查询", "WHERE pk > 左 AND pk <= 右", GREEN, GREEN_SOFT),
            ("断点键", "跑完就记下这个上界，重启时跳过", BLUE, BLUE_SOFT),
            ("幂等键", "同一行在目标端覆盖，不新增", AMBER, AMBER_SOFT),
            ("校验分片", "用同样的切法算 CRC，才能逐片比", RED, RED_SOFT)]
    for i, (t, desc, accent, soft) in enumerate(uses):
        x = 56 + i * 372
        c.d.rounded_rectangle([x, 596, x + 344, 700], radius=12, fill=soft)
        c.text(x + 18, 610, t, 19, SEMIBOLD, accent)
        for j, piece in enumerate(c.wrap(desc, 308, 16, REGULAR)):
            c.text(x + 18, 644 + j * 22, piece, 16, REGULAR, MUTED)

    c.note(56, 726, 1488,
           "没有主键的表：四个用途全部失效 —— 无法可靠切片（会漏行/重复）、无法记断点、无法幂等、无法分片校验。"
           "所以本系统在建任务时就直接拒绝无主键表，而不是跑到一半出错。",
           RED, RED_SOFT, "不做会怎样")
    return c.close("fig-l3-chunks.png")


# ── L4 幂等写入与保序 ──────────────────────────────────────────
def l4_idempotent():
    c = Lesson(1600, 940, "L4 幂等写入与保序：同一行写几次都一样",
               "UPSERT 让「重做」变得安全；保序让「同一行先改后删」不被颠倒",
               takeaway="一句话：幂等解决「写几次」，保序解决「谁先谁后」——两者缺一，重放就会出错。")

    c.text(56, 168, "① 一个事务里的 6 条改动，按到达顺序排队", 20, SEMIBOLD, INK)
    seq = [("U orders", BLUE, BLUE_SOFT), ("U orders", BLUE, BLUE_SOFT), ("D orders", RED, RED_SOFT),
           ("U items", BLUE, BLUE_SOFT), ("U orders", BLUE, BLUE_SOFT), ("D items", RED, RED_SOFT)]
    for i, (label, accent, soft) in enumerate(seq):
        x = 56 + i * 250
        c.d.rounded_rectangle([x, 214, x + 214, 276], radius=10, fill=soft, outline=accent, width=2)
        c.text(x + 107, 236, label, 17, SEMIBOLD, accent, anchor="mt")
        c.text(x + 107, 280, f"第 {i + 1} 条", 14, REGULAR, MUTED, anchor="mt")

    c.text(56, 340, "② 只把「连续、同表、同操作」的合并成一条 SQL —— 顺序因此不会被打乱", 20, SEMIBOLD, INK)
    groups = [("UPSERT orders × 2", "一条 INSERT … VALUES (…),(…)\nON DUPLICATE KEY UPDATE", BLUE, BLUE_SOFT),
              ("DELETE orders × 1", "一条 DELETE … WHERE pk = ?", RED, RED_SOFT),
              ("UPSERT items × 1", "一条 INSERT … ON DUPLICATE KEY UPDATE", BLUE, BLUE_SOFT),
              ("DELETE items × 1", "一条 DELETE … WHERE pk = ?", RED, RED_SOFT)]
    for i, (t, desc, accent, soft) in enumerate(groups):
        x = 56 + i * 386
        c.d.rounded_rectangle([x, 392, x + 356, 494], radius=12, fill=soft, outline=accent, width=2)
        c.text(x + 16, 406, t, 17, SEMIBOLD, accent)
        cy = 438
        for para in desc.split("\n"):
            for piece in c.wrap(para, 324, 14, REGULAR):
                c.text(x + 16, cy, piece, 14, REGULAR, MUTED)
                cy += 19

    c.text(56, 528, "③ 为什么不能「把所有行合成一条 SQL」", 20, SEMIBOLD, INK)
    bad = [("颠倒顺序 = 结果不同",
            ["先改后删 → 行不存在", "先删后改 → 行被插回来"], RED, RED_SOFT),
           ("跨表混写 = 无法解释", ["orders 与 items 混在一句里", "出错时不知道是哪张表失败"], AMBER, AMBER_SOFT),
           ("无上限 = 撞包体上限", ["10 万行一句 INSERT", "超出 max_allowed_packet 直接失败"], RED, RED_SOFT)]
    for i, (t, lines, accent, soft) in enumerate(bad):
        x = 56 + i * 508
        c.d.rounded_rectangle([x, 574, x + 480, 698], radius=12, fill=soft)
        c.text(x + 18, 588, t, 18, SEMIBOLD, accent)
        for j, line in enumerate(lines):
            c.text(x + 18, 624 + j * 24, "· " + line, 15, REGULAR, MUTED)

    c.note(56, 724, 1488,
           "写入用的是 UPSERT（INSERT … ON DUPLICATE KEY UPDATE）：同一主键写 N 次，最终只有一行、值是最后一次的。"
           "这就是「重做安全」的全部来源 —— 也是断点可以记在数据之后、重放可以重复的原因。",
           GREEN, GREEN_SOFT, "核心")
    return c.close("fig-l4-idempotent.png")


# ── L5 全量通道 ────────────────────────────────────────────────
def l5_full():
    c = Lesson(1600, 900, "L5 全量通道：切片、并行、限速，以及那个「糟糕但安全」的窗口",
               "全量 = 把存量搬一遍；难点不在搬，而在「搬到一半崩了怎么接着搬」",
               takeaway="一句话：先写数据成功、再记断点。这个顺序让你最多「白干一片」，绝不会「漏一片」。")

    c.pipeline([("读主键", ["解析主键列与类型", "复合主键/无主键直接拒绝"], MUTED, (241, 245, 249)),
                ("扫上界", ["只查主键，不读数据", "得到 N 个分片上界"], BLUE, BLUE_SOFT),
                ("并行搬", ["每片一个连接", "令牌桶限速"], GREEN, GREEN_SOFT),
                ("写目标", ["UPSERT 分批写入", "单条 SQL 最多 500 行"], GREEN, GREEN_SOFT),
                ("记断点", ["成功后记上界", "断点在目标库"], AMBER, AMBER_SOFT)],
               y=180, height=150)

    # 崩溃窗口
    c.text(56, 380, "关键窗口：数据写了、断点还没记 —— 崩在这个缝里会怎样？", 20, SEMIBOLD, INK)
    c.d.rounded_rectangle([56, 426, 1544, 560], radius=14, fill=CARD, outline=RED, width=3)
    c.d.rounded_rectangle([80, 452, 420, 534], radius=10, fill=GREEN_SOFT, outline=GREEN, width=2)
    c.text(96, 466, "① 写目标库", 18, SEMIBOLD, GREEN)
    c.text(96, 496, "数据已提交，不可回滚", 15, REGULAR, MUTED)
    c.arrow(426, 492, 520, 492, MUTED)
    c.d.rounded_rectangle([520, 452, 700, 534], radius=10, fill=RED_SOFT, outline=RED, width=2)
    c.text(536, 466, "② 崩在这", 18, SEMIBOLD, RED)
    c.text(536, 496, "断电 / OOM / kill", 15, REGULAR, MUTED)
    c.arrow(706, 492, 800, 492, MUTED)
    c.d.rounded_rectangle([800, 452, 1140, 534], radius=10, fill=AMBER_SOFT, outline=AMBER, width=2)
    c.text(816, 466, "③ 断点没记上", 18, SEMIBOLD, AMBER)
    c.text(816, 496, "重启后这一片会被重做一遍", 15, REGULAR, MUTED)
    c.arrow(1146, 492, 1240, 492, MUTED)
    c.d.rounded_rectangle([1240, 452, 1520, 534], radius=10, fill=GREEN_SOFT, outline=GREEN, width=2)
    c.text(1256, 466, "④ 结果：安全", 18, SEMIBOLD, GREEN)
    c.text(1256, 496, "UPSERT → 覆盖，不产生重复", 15, REGULAR, MUTED)

    c.note(56, 590, 720,
           "如果反过来（先记断点、再写数据），崩在缝里就会「跳过一片没搬的数据」—— 这是永久丢失，"
           "而且任何对账手段都补不回来（源端不知道你漏了）。所以顺序不可交换。",
           RED, RED_SOFT, "顺序反过来会怎样")
    c.note(812, 590, 732,
           "并行只发生在「片与片之间」：片内是顺序读的，跨片没有顺序约束。"
           "所以并行度调大只会更快，不会让数据错乱。",
           BLUE, BLUE_SOFT, "为什么可以并行")
    return c.close("fig-l5-full.png")


# ── L6 binlog 事件 → 行 ────────────────────────────────────────
def l6_events():
    c = Lesson(1600, 940, "L6 增量通道：一串 binlog 事件，怎么变成一批「行」",
               "binlog 记的不是「行」，是「事件」；中间要经过事务聚合和语义翻译",
               takeaway="一句话：等到 XID（事务提交）才动手；UPDATE 改主键 → 拆成「删旧行 + 插新行」。")

    c.text(56, 170, "① 一个事务在 binlog 里长这样", 20, SEMIBOLD, INK)
    events = [("GTID", "事务身份证", BLUE, BLUE_SOFT),
              ("QUERY", "BEGIN / DDL", MUTED, (241, 245, 249)),
              ("TABLE_MAP", "表号 → 库.表", BLUE, BLUE_SOFT),
              ("UPDATE_ROWS", "改前镜像 + 改后镜像", GREEN, GREEN_SOFT),
              ("XID", "提交！可以动手了", AMBER, AMBER_SOFT)]
    for i, (t, desc, accent, soft) in enumerate(events):
        x = 56 + i * 300
        c.d.rounded_rectangle([x, 216, x + 264, 306], radius=12, fill=soft, outline=accent, width=2)
        c.text(x + 16, 228, t, 17, SEMIBOLD, accent)
        for j, piece in enumerate(c.wrap(desc, 232, 15, REGULAR)):
            c.text(x + 16, 260 + j * 20, piece, 15, REGULAR, MUTED)
        if i < len(events) - 1:
            c.arrow(x + 266, 261, x + 296, 261, FAINT)

    c.text(56, 340, "② 翻译成「行」的规则（这里是全部难点）", 20, SEMIBOLD, INK)
    rows = [("INSERT", "→ 1 条 UPSERT", "直接取改后值", GREEN, GREEN_SOFT),
            ("UPDATE（主键没变）", "→ 1 条 UPSERT", "取改后值，覆盖原行", GREEN, GREEN_SOFT),
            ("UPDATE（主键变了）", "→ 2 条：DELETE + UPSERT", "旧主键的行要删掉，否则留下孤儿行", RED, RED_SOFT),
            ("DELETE", "→ 1 条 DELETE", "按主键删，用改前镜像取主键", AMBER, AMBER_SOFT)]
    for i, (t, out, why, accent, soft) in enumerate(rows):
        y = 396 + i * 84
        c.d.rounded_rectangle([56, y, 420, y + 68], radius=10, fill=soft, outline=accent, width=2)
        c.text(74, y + 22, t, 17, SEMIBOLD, accent)
        c.text(444, y + 24, out, 17, SEMIBOLD, INK)
        c.text(760, y + 26, why, 15, REGULAR, MUTED)

    c.note(56, 748, 720,
           "主键变更是最容易漏的一条：如果按普通 UPDATE 处理成「改后值 UPSERT」，旧主键那行会永远留在目标库，"
           "校验时表现为「目标端多出源端没有的行」。",
           RED, RED_SOFT, "最容易出的事故")
    c.note(812, 748, 732,
           "为什么必须 binlog_row_image=FULL：改前镜像要拿来取主键（DELETE 用），"
           "只有 MINIMAL 时前镜像可能不含主键列，删除就无从下手。",
           AMBER, AMBER_SOFT, "环境要求的原因")
    return c.close("fig-l6-events.png")


# ── L7 校验 ───────────────────────────────────────────────────
def l7_verify():
    c = Lesson(1600, 860, "L7 校验通道：怎么证明「搬对了」，又怎么不被线上写入骗",
               "校验不读全表、不做排序，用「同样的切法 + CRC」逐片比对",
               takeaway="一句话：不一致先别下结论 —— 重读两端再逐行比，把「校验瞬间源在变」的假差异滤掉。")

    c.pipeline([("同样的切法", ["复用全量的分片上界", "同一 chunkSize"], AMBER, AMBER_SOFT),
                ("两边各算 CRC", ["源库读一片算一次", "目标库读一片算一次"], AMBER, AMBER_SOFT),
                ("逐片比较", ["相同 → 这片过", "不同 → 进下一步"], AMBER, AMBER_SOFT)],
               y=180, height=140)

    c.text(56, 360, "CRC 不同的两种原因：真差异 / 假差异", 20, SEMIBOLD, INK)
    c.d.rounded_rectangle([56, 404, 776, 610], radius=14, fill=CARD, outline=RED, width=2)
    c.text(76, 420, "假差异（校验瞬间源在写）", 19, SEMIBOLD, RED)
    for i, line in enumerate(["源库算 CRC 时读到一半，行被改了",
                              "目标库还没收到那条增量",
                              "→ 两边确实不同，但只是时间差"]):
        c.text(76, 460 + i * 26, "· " + line, 16, REGULAR, MUTED)
    c.text(76, 550, "处置：重读两端 → 逐行比对 → 仍不同才报", 16, MEDIUM, RED)

    c.d.rounded_rectangle([812, 404, 1544, 610], radius=14, fill=CARD, outline=GREEN, width=2)
    c.text(832, 420, "真差异（数据真的不对）", 19, SEMIBOLD, GREEN)
    for i, line in enumerate(["某一行写失败、进了死信表",
                              "主键变更被处理成了普通更新（留孤儿行）",
                              "有人手工改了目标库"]):
        c.text(832, 460 + i * 26, "· " + line, 16, REGULAR, MUTED)
    c.text(832, 550, "处置：记录到 oms_verify_diff，给出主键与两侧行内容", 16, MEDIUM, GREEN)

    c.note(56, 634, 1488,
           "校验的定位是「发现者」而不是「修复者」：它只报告哪些分片、哪些主键不一致，不做任何写入。"
           "把「修数据」交给业务决策，才不会出现「校验自己把数据改坏」。",
           BLUE, BLUE_SOFT, "边界")
    return c.close("fig-l7-verify.png")


# ── L8 GTID 与一致性位点 ──────────────────────────────────────
def l8_gtid():
    c = Lesson(1600, 820, "L8 位点与 GTID：为什么要记一个「起点」，而不是录下 binlog",
               "一致性位点记在全量之前；增量从它之后开始重放，全量期间的写入因此不会丢",
               takeaway="一句话：GTID 是源库自己维护的「我执行到哪了」，重连只要说「这之后的都给我」，不用自己存 binlog。")

    c.text(56, 180, "源库事务时间轴", 20, SEMIBOLD, INK)
    c.d.line([80, 300, 1520, 300], fill=MUTED, width=3)
    marks = [("T1", 180), ("T2", 340), ("T3", 500), ("T4", 700), ("T5", 900), ("T6", 1100)]
    for label, x in marks:
        c.d.line([x, 288, x, 312], fill=MUTED, width=2)
        c.text(x, 318, label, 16, MEDIUM, MUTED, anchor="mt")

    # 全量窗口
    c.d.rounded_rectangle([500, 210, 900, 280], radius=10, fill=BLUE_SOFT, outline=BLUE, width=2)
    c.text(516, 224, "全量搬家窗口（业务一直在写）", 17, SEMIBOLD, BLUE)
    c.text(516, 252, "T3 之后的所有改动都没出现在目标库", 15, REGULAR, MUTED)
    # 一致性位点
    c.d.line([500, 330, 500, 420], fill=AMBER, width=3)
    c.text(500, 428, "一致性位点 = T3", 17, SEMIBOLD, AMBER, anchor="mt")
    c.text(500, 458, "全量开始前记下 gtid_executed", 15, REGULAR, MUTED, anchor="mt")
    # 增量窗口
    c.d.rounded_rectangle([500, 500, 1520, 566], radius=10, fill=GREEN_SOFT, outline=GREEN, width=2)
    c.text(516, 514, "增量从 T3 之后开始重放：T4 / T5 / T6 被补上", 17, SEMIBOLD, GREEN)
    c.text(516, 540, "（T1~T3 也可能被重放一部分 —— 无所谓，UPSERT 幂等）", 15, REGULAR, MUTED)

    c.note(56, 606, 720,
           "自己缓存 binlog 要处理：文件滚动、过期清理、断点位置计算、进程重启丢失。"
           "而 gtid_executed 由源库维护，重连时直接把集合交给客户端即可。",
           AMBER, AMBER_SOFT, "为什么不缓存 binlog")
    c.note(812, 606, 732,
           "位点必须记在「全量开始之前」而不是「全量结束之后」：记在之后，全量期间的写入就永远补不回来了。",
           RED, RED_SOFT, "为什么是「之前」")
    return c.close("fig-l8-gtid.png")


# ── L9 数据+位点同事务 ────────────────────────────────────────
def l9_same_tx():
    c = Lesson(1600, 860, "L9 数据 + 位点同事务：不丢数据的最后一道保险",
               "位点表必须和业务表在同一个库、同一个事务里提交",
               takeaway="一句话：允许「重做」，不许「跳过」—— 位点永远不能比数据跑得快。")

    c.text(56, 170, "一个源事务的写入，在目标端是这样提交的", 20, SEMIBOLD, INK)
    c.d.rounded_rectangle([56, 216, 1544, 400], radius=14, fill=CARD, outline=GREEN, width=3)
    steps = [("BEGIN", MUTED), ("写业务表", GREEN), ("更新进度表", AMBER), ("COMMIT", GREEN)]
    for i, (t, accent) in enumerate(steps):
        x = 100 + i * 360
        c.d.rounded_rectangle([x, 260, x + 280, 348], radius=10, fill=CARD, outline=accent, width=2)
        c.text(x + 140, 288, t, 19, SEMIBOLD, accent, anchor="mt")
        if i < 3:
            c.arrow(x + 286, 304, x + 348, 304, MUTED)
    c.text(112, 232, "同一个目标库事务", 16, SEMIBOLD, GREEN)

    c.text(56, 430, "如果拆成两个事务，只有两种可能，都很难受", 20, SEMIBOLD, INK)
    left = [("先提交位点、后写数据", ["位点超前", "崩溃后从新位点继续", "→ 这段数据永久丢失"], RED, RED_SOFT)]
    right = [("先写数据、后提交位点", ["位点落后", "崩溃后重放这段", "→ 数据被覆盖一遍，仍正确"], GREEN, GREEN_SOFT)]
    for i, (t, lines, accent, soft) in enumerate(left + right):
        x = 56 + i * 756
        c.d.rounded_rectangle([x, 474, x + 720, 640], radius=14, fill=soft)
        c.text(x + 20, 490, t, 19, SEMIBOLD, accent)
        for j, line in enumerate(lines):
            c.text(x + 20, 530 + j * 26, "· " + line, 16, REGULAR, MUTED)
        c.text(x + 20, 610, "不可接受" if i == 0 else "可接受（幂等兜底）", 17, SEMIBOLD, accent)

    c.note(56, 664, 1488,
           "「数据与位点同一事务」的物理前提是：位点表住在目标库里。如果位点在控制面自己的库，"
           "就永远无法和业务写入共用一个事务 —— 这正是早期实现的一个隐患（全量断点放在控制面本地库里）。",
           AMBER, AMBER_SOFT, "为什么位点表在目标库")
    return c.close("fig-l9-same-tx.png")


# ── L10 三处断点 + 租约 ──────────────────────────────────────
def l10_checkpoints():
    c = Lesson(1600, 900, "L10 崩溃与接管：全系统一共三处「状态」，都在哪、谁读",
               "重启后能接着跑，靠的是把状态放在「别人也能看见」的地方，并且只让一个节点干活",
               takeaway="一句话：断点跟着数据走（目标库），任务归属跟着租约走（共享元数据库）—— 所以换台机器也能接上。")

    cards = [("① 全量断点", "目标库 oms_full_checkpoint",
              ["记录「哪个分片上界已搬完」", "谁接管都能读到"], GREEN, GREEN_SOFT),
             ("② 增量位点", "目标库 oms_incr_checkpoint",
              ["记录「回放到哪个事务」", "和业务数据同事务提交"], BLUE, BLUE_SOFT),
             ("③ 任务租约", "元数据库 oms_task_lease",
              ["记录「这个任务归谁跑」", "心跳续租，抢不到就不许跑"], AMBER, AMBER_SOFT)]
    for i, (t, where, lines, accent, soft) in enumerate(cards):
        x = 56 + i * 508
        c.d.rounded_rectangle([x, 200, x + 480, 420], radius=14, fill=CARD, outline=accent, width=3)
        c.d.rounded_rectangle([x, 200, x + 480, 206], radius=3, fill=accent)
        c.text(x + 20, 224, t, 22, SEMIBOLD, INK)
        c.text(x + 20, 262, where, 16, MEDIUM, accent)
        for j, line in enumerate(lines):
            c.text(x + 20, 306 + j * 26, "· " + line, 16, REGULAR, MUTED)
        c.text(x + 20, 372, "存在「别的节点也看得到」的地方", 15, REGULAR, accent)

    c.text(56, 460, "重启/接管时发生什么", 20, SEMIBOLD, INK)
    flow = [("扫描状态", ["找所有「在跑」的任务"], MUTED, (241, 245, 249)),
            ("抢租约", ["抢到才算我的", "抢不到就退出"], AMBER, AMBER_SOFT),
            ("读断点", ["全量？读分片断点", "增量？读事务位点"], BLUE, BLUE_SOFT),
            ("接着跑", ["跳过已完成的", "从缺口继续"], GREEN, GREEN_SOFT)]
    c.pipeline(flow, y=504, height=132)

    c.note(56, 680, 720,
           "如果断点存在控制面自己的本地磁盘：换台机器断点就没了，任务只能从头再来；"
           "如果不用租约：两个节点同时跑同一任务，位点会互相覆盖，甚至倒退。",
           RED, RED_SOFT, "两处最容易做错的地方")
    c.note(812, 680, 732,
           "暂停、出错、收尾都会释放租约。释放之后别的节点才能真正接管 —— 租约是「临时占有」，不是「永久拥有」。",
           BLUE, BLUE_SOFT, "租约的释放时机")
    return c.close("fig-l10-checkpoints.png")


# ── L12 改造影响矩阵 ─────────────────────────────────────────
def l12_matrix():
    c = Lesson(1600, 1180, "L12 改造演练：三个需求，各会牵动哪些模块",
               "判断改动范围，比会写代码更重要 —— 这张矩阵是「动一下会响哪里」的体检表",
               takeaway="一句话：先找「哪个模块拥有这个事实」，再看契约端口要不要变 —— 事实搬家永远是最贵的改动。")

    mods = ["full 全量", "store 增量", "connector 写入", "verifier 校验", "cm 控制面", "starter 编排"]
    tasks = ["加一个 Kafka 出口\n（数据除了写库还要发消息）",
             "支持改库名迁移\n（源 shop → 目标 shop_bak）",
             "拆成独立进程\n（组件间走 RPC/MQ）"]
    grid_x, grid_y = 620, 214
    cw, ch = 300, 112
    # 表头
    for j, t in enumerate(tasks):
        x = grid_x + j * (cw + 20)
        c.d.rounded_rectangle([x, 150, x + cw, 208], radius=10, fill=(241, 245, 249))
        for k, piece in enumerate(c.wrap(t.split("\n")[0], cw - 24, 15, SEMIBOLD)):
            c.text(x + 12, 158 + k * 18, piece, 15, SEMIBOLD, INK)
    for i, m in enumerate(mods):
        y = grid_y + i * (ch + 8)
        c.text(56, y + 44, m, 18, SEMIBOLD, INK)

    # 影响：0 无 / 1 小改 / 2 改契约 / 3 事实搬家
    cells = [[2, 2, 3],
             [0, 0, 3],
             [3, 2, 2],
             [1, 0, 2],
             [1, 3, 3],
             [2, 2, 3]]
    legend = {0: ("无改动", (241, 245, 249), MUTED),
              1: ("小改（参数/校验）", GREEN_SOFT, GREEN),
              2: ("改契约/加字段", AMBER_SOFT, AMBER),
              3: ("事实搬家（最贵）", RED_SOFT, RED)}
    for i, row in enumerate(cells):
        for j, v in enumerate(row):
            x = grid_x + j * (cw + 20)
            y = grid_y + i * (ch + 8)
            label, fill, color = legend[v]
            c.d.rounded_rectangle([x, y, x + cw, y + ch], radius=12, fill=fill)
            c.text(x + 16, y + 44, label, 16, SEMIBOLD, color)

    c.text(56, 960, "红 = 有「状态/事实」要换地方存放，先想清楚再动手", 17, MEDIUM, RED)
    c.text(56, 992, "黄 = 契约要加字段（比如 RowRecord 多一个目标库名）", 17, MEDIUM, AMBER)
    c.text(56, 1024, "绿 / 灰 = 局部改动，改完跑单测即可", 17, MEDIUM, GREEN)
    return c.close("fig-l12-matrix.png")


# ── E1 测试策略 ──────────────────────────────────────────────
def e1_tests():
    c = Lesson(1600, 880, "E1 这类系统怎么测：三层，各抓一类毛病",
               "「能跑通」和「崩了也对」是两种能力，得用不同层次的测试去证",
               takeaway="一句话：逻辑用 H2 双库测，崩溃用注入测，端到端用真 MySQL 测 —— 三层缺一层就会漏一类事故。")

    layers = [("单元 / 组装测试", "H2 两个内存库当源和目标",
               ["跑得快，秒级", "能测幂等、断点、同事务", "测不到真实 binlog 行为"], GREEN, GREEN_SOFT),
              ("崩溃注入测试", "在指定位置抛异常，模拟「写了但没记断点」",
               ["证明重做后仍一致", "证明不会重复/丢失", "测不到网络与重连"], AMBER, AMBER_SOFT),
              ("端到端测试", "两个真 MySQL 容器 + 真 binlog",
               ["测 DDL、断线、接管", "测重启后自动恢复", "慢，靠 Docker 门控"], BLUE, BLUE_SOFT)]
    for i, (t, sub, lines, accent, soft) in enumerate(layers):
        y = 180 + i * 170
        c.d.rounded_rectangle([56, y, 1544, y + 146], radius=14, fill=CARD, outline=accent, width=2)
        c.d.rounded_rectangle([56, y, 62, y + 146], radius=3, fill=accent)
        c.text(84, y + 16, t, 20, SEMIBOLD, INK)
        c.text(84, y + 48, sub, 16, MEDIUM, accent)
        for j, line in enumerate(lines):
            c.text(84, y + 84 + j * 22, "· " + line, 15, REGULAR, MUTED)

    c.note(56, 676, 1488,
           "「跳过测试」的典型借口是「没 Docker 跑不了端到端」。其实 90% 的逻辑（幂等、断点顺序、同事务）"
           "在 H2 上就能证；端到端只负责证「真实 binlog 的那部分」。分层不是形式，是为了让证明能在 5 秒内跑完。",
           BLUE, BLUE_SOFT, "别把测试写成仪式")
    return c.close("fig-e1-tests.png")


# ── E2 可观测 ────────────────────────────────────────────────
def e2_metrics():
    c = Lesson(1600, 800, "E2 线上视角：四个指标，一眼看出「真在跑」还是「假活着」",
               "最危险的状态不是报错，而是「状态显示在跑、实际早停了」",
               takeaway="一句话：状态是声明，指标才是事实 —— 状态 RUNNING 时，指标必须还在动。")

    cards = [("state", "现在处在哪一步", "INIT / FULL / INCR / PAUSED / ERROR", BLUE, BLUE_SOFT),
             ("rowsCopied", "全量搬了多少行", "长时间不涨 = 全量卡住", GREEN, GREEN_SOFT),
             ("incrCheckpointGtid", "增量回放到哪个事务", "不动 = 增量没在推进", AMBER, AMBER_SOFT),
             ("incrReconnects", "重连过几次", "偶发增长正常；持续暴涨要查网络", RED, RED_SOFT)]
    for i, (name, desc, watch, accent, soft) in enumerate(cards):
        x = 56 + i * 380
        c.d.rounded_rectangle([x, 180, x + 356, 366], radius=14, fill=CARD, outline=accent, width=2)
        c.text(x + 18, 196, name, 20, SEMIBOLD, INK)
        c.text(x + 18, 232, desc, 16, REGULAR, MUTED)
        for j, piece in enumerate(c.wrap(watch, 320, 15, MEDIUM)):
            c.text(x + 18, 272 + j * 22, piece, 15, MEDIUM, accent)

    c.text(56, 400, "两种「看起来都在跑」的状态", 20, SEMIBOLD, INK)
    c.d.rounded_rectangle([56, 444, 776, 640], radius=14, fill=GREEN_SOFT)
    c.text(76, 460, "健康", 19, SEMIBOLD, GREEN)
    for i, line in enumerate(["state = RUNNING_INCR", "incrCheckpointGtid 每几秒往前动",
                              "重连次数偶尔 +1"]):
        c.text(76, 498 + i * 26, "· " + line, 16, REGULAR, MUTED)
    c.d.rounded_rectangle([812, 444, 1544, 640], radius=14, fill=RED_SOFT)
    c.text(832, 460, "假活（最危险）", 19, SEMIBOLD, RED)
    for i, line in enumerate(["state = RUNNING_INCR", "incrCheckpointGtid 一直是同一个值",
                              "没有任何错误日志（异常被吞掉了）"]):
        c.text(832, 498 + i * 26, "· " + line, 16, REGULAR, MUTED)
    c.text(832, 588, "→ 判据：状态声明 + 指标不动 = 立刻当故障处理", 17, SEMIBOLD, RED)

    c.note(56, 664, 1488,
           "这类系统最典型的线上事故不是「崩了」，而是「线程早在半小时前就静默退出，状态还写着 RUNNING」。"
           "所以任何失败路径都必须显式落到状态上，宁可报红，也不要静默。",
           RED, RED_SOFT, "设计红线")
    return c.close("fig-e2-metrics.png")


# ── E3 建任务六道闸门 ────────────────────────────────────────
def e3_gates():
    c = Lesson(1600, 820, "E3 建任务时的六道闸门：把事故挡在开始之前",
               "每道闸门对应一类「跑到一半才会爆」的事故，能在建任务时拦住就别拖到半夜",
               takeaway="一句话：能在第 0 秒拒绝的，绝不留到第 N 小时 —— 迁移任务的失败成本随时间指数上升。")

    gates = [("环境预检", "log_bin / ROW / row_image=FULL / gtid_mode", "拿不到行级变更、没有断点依据"),
             ("库名一致", "源库名 = 目标库名 = 任务 schema", "静默写错库，跑完才发现"),
             ("主键校验", "源/目标主键必须一致且单列", "幂等键不成立，重做会错"),
             ("结构比对", "列名/类型/可空性逐项一致", "写入列错位，整批失败"),
             ("表可访问", "目标表存在、有权限", "跑到一半 1146/1142 报错"),
             ("结构指纹登记", "把源端结构钉成基线", "运行中被手工改结构无从发现")]
    for i, (t, how, why) in enumerate(gates):
        y = 176 + i * 96
        c.d.rounded_rectangle([56, y, 90, y + 76], radius=12, fill=BLUE_SOFT)
        c.text(73, y + 26, str(i + 1), 22, SEMIBOLD, BLUE, anchor="mt")
        c.text(110, y + 12, t, 19, SEMIBOLD, INK)
        c.text(110, y + 44, how, 15, REGULAR, MUTED)
        c.arrow(1000, y + 38, 1060, y + 38, FAINT)
        c.d.rounded_rectangle([1060, y + 6, 1544, y + 70], radius=10, fill=RED_SOFT)
        c.text(1076, y + 22, "不拦就会：" + why, 15, MEDIUM, RED)

    c.note(56, 748, 1488,
           "六道闸门全部发生在 createTask：只做元数据查询，不搬一行数据，所以很快。"
           "代价是「建任务时可能被拒绝」，收益是「不会有一个跑到 80% 才失败的任务」。",
           GREEN, GREEN_SOFT, "代价与收益")
    return c.close("fig-e3-gates.png")


ALL = {
    "panorama": panorama, "gif": crash_animation,
    "l2": l2_contract, "l3": l3_chunks, "l4": l4_idempotent, "l5": l5_full,
    "l6": l6_events, "l7": l7_verify, "l8": l8_gtid, "l9": l9_same_tx,
    "l10": l10_checkpoints, "l12": l12_matrix,
    "e1": e1_tests, "e2": e2_metrics, "e3": e3_gates,
}



# ── 异构 SQL：方言缝合线 ─────────────────────────────────────
def d1_dialect_seam():
    c = Lesson(1600, 980, "异构 SQL：把「方言」从写入逻辑里撕出来",
               "现在 SQL 是拼在写入端里的，加一个目标库就得改写入端；把方言做成一条缝合线，加库就只是加一个类",
               takeaway="一句话：Sink 只负责「事务 + 批次 + 位点」，SQL 长什么样交给 SqlDialect —— 新增一个目标库 = 新增一个方言类 + 一张类型映射表。")

    # 现状
    c.text(56, 168, "现状：方言焊在写入端", 20, SEMIBOLD, RED)
    c.d.rounded_rectangle([56, 204, 720, 470], radius=14, fill=CARD, outline=RED, width=2)
    c.text(76, 220, "MySqlSink", 19, SEMIBOLD, INK)
    c.text(76, 252, "一个类里同时装了四件事", 15, REGULAR, MUTED)
    for i, line in enumerate(["① 事务与提交顺序", "② 批次切分（谁跟谁能合并）",
                              "③ SQL 拼装（反引号 / ON DUPLICATE KEY UPDATE）",
                              "④ 位点推进"]):
        c.text(76, 288 + i * 26, line, 15, REGULAR, MUTED)
    c.text(76, 402, "→ 想加 PostgreSQL：得在① ② ④ 都在场的地方", 15, MEDIUM, RED)
    c.text(76, 428, "   改 ③，很容易把事务语义改坏", 15, MEDIUM, RED)

    # 目标
    c.text(776, 168, "目标：方言是一条可替换的缝合线", 20, SEMIBOLD, GREEN)
    c.d.rounded_rectangle([776, 204, 1544, 470], radius=14, fill=CARD, outline=GREEN, width=2)
    c.text(796, 220, "Sink（只做数据面职责）", 17, SEMIBOLD, INK)
    c.text(796, 250, "事务 · 批次 · 位点 · 死信", 15, REGULAR, MUTED)
    c.arrow(796, 296, 900, 296, GREEN)
    c.d.rounded_rectangle([910, 264, 1200, 340], radius=12, fill=GREEN_SOFT, outline=GREEN, width=2)
    c.text(926, 276, "SqlDialect（端口）", 16, SEMIBOLD, GREEN)
    c.text(926, 306, "quote / upsert / ddl / paging", 14, REGULAR, MUTED)
    c.arrow(1206, 296, 1256, 296, GREEN)
    for i, (t, sub, accent, soft) in enumerate([("MySQL", "`x`  ON DUPLICATE KEY UPDATE", BLUE, BLUE_SOFT),
                                               ("PostgreSQL", "\"x\"  ON CONFLICT … DO UPDATE", AMBER, AMBER_SOFT),
                                               ("Oracle", "\"X\"  MERGE INTO …", RED, RED_SOFT)]):
        y = 214 + i * 82
        c.d.rounded_rectangle([1266, y, 1524, y + 68], radius=10, fill=soft, outline=accent, width=2)
        c.text(1282, y + 10, t, 16, SEMIBOLD, accent)
        c.text(1282, y + 38, sub, 12, REGULAR, MUTED)

    # 缝合线上的四个职责
    c.text(56, 500, "缝合线上要固定的四件事（每件事都必须按目标库实现）", 20, SEMIBOLD, INK)
    duties = [("标识符引用", ["反引号 / 双引号 / 大小写折叠"], MUTED, (241, 245, 249)),
              ("幂等写法", ["ON DUPLICATE / ON CONFLICT", "MERGE INTO / 先删后插"], BLUE, BLUE_SOFT),
              ("自用表 DDL", ["自增 / 长文本 / upsert 语法"], AMBER, AMBER_SOFT),
              ("分页与边界", ["LIMIT / FETCH FIRST", "主键比较的隐式转换"], GREEN, GREEN_SOFT)]
    for i, (t, lines, accent, soft) in enumerate(duties):
        x = 56 + i * 380
        c.d.rounded_rectangle([x, 546, x + 356, 668], radius=12, fill=CARD, outline=accent, width=2)
        c.text(x + 18, 560, t, 18, SEMIBOLD, accent)
        for j, line in enumerate(lines):
            c.text(x + 18, 596 + j * 24, "· " + line, 15, REGULAR, MUTED)

    # 与已有设计的关系
    c.note(56, 700, 720,
           "RowRecord 已经是「与方言无关」的行模型（第 2 课），所以缝合线只需要画在「行 → SQL」这一层，"
           "全量、增量、校验三条通道都不用改。",
           GREEN, GREEN_SOFT, "为什么这条缝合线位置是最好的")
    c.note(812, 700, 732,
           "CRC 校验是最容易被异构打破的一环：同一个值在 MySQL 与 PG 里的字符串渲染不同，"
           "所以比较必须在 canonical 编码层做，不能依赖各库自己的 toString。",
           RED, RED_SOFT, "最容易被忽略的坑")
    return c.close("fig-d1-dialect-seam.png")


ALL["d1"] = d1_dialect_seam


if __name__ == "__main__":
    args = sys.argv[1:] or ["all"]
    names = list(ALL) if args == ["all"] else args
    for name in names:
        ALL[name]()
