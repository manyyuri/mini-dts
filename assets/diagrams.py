"""
mini-dts 教学配图生成器（可复用组件）

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
        """按真实宽度折行（中文按字符切，够用且可预测）。"""
        lines, cur = [], ""
        for ch in s:
            if self.width(cur + ch, size, weight) <= max_width:
                cur += ch
            else:
                lines.append(cur)
                cur = ch
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
    c.title("mini-dts 全景：两个数据库 + 三条数据通道 + 一条控制线",
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
        (200, "全量迁移", "mini-dts-full", GREEN, GREEN_SOFT,
         ["按主键把大表切成一份份", "并行搬运 + 限速", "搬到哪就记在哪（断点）"]),
        (380, "增量订阅", "mini-dts-store", BLUE, BLUE_SOFT,
         ["跟着 binlog 往下走", "一个事务一批，保持顺序", "断线自动重连"]),
        (560, "数据校验", "mini-dts-verifier", AMBER, AMBER_SOFT,
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
    for i, line in enumerate(["dts_incr_checkpoint  增量进度",
                              "dts_full_checkpoint  全量进度",
                              "dts_dead_letter      失败存根",
                              "dts_ddl_event        结构变更",
                              "dts_schema_fingerprint 结构指纹"]):
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
    c.text(424, 760, "控制面 mini-dts-cm　＋　编排 mini-dts-starter", 24, SEMIBOLD, INK)
    cells = [
        ("任务账本", "dts_task：参数、状态、进度"),
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

    c.text(56, 972, "一句话：源库照常跑业务，mini-dts 在旁边把数据搬过去，并且一直跟着变，还能随时说清「搬到哪了」。",
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


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    if which in ("all", "panorama"):
        panorama()
    if which in ("all", "gif"):
        crash_animation()
