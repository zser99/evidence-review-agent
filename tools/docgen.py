"""가상 증빙 문서 렌더러.

국가법령정보센터 공개 서식(교육비 납입증명서, 진료비 계산서·영수증)을 참고해 만든
'검증용 가상 증빙'을 PNG/JPG/PDF로 그린다. 같은 레이아웃 코드를 이미지(PIL)와
텍스트 레이어 PDF(PyMuPDF) 두 백엔드로 출력한다.
"""
from __future__ import annotations

import io
import os
import random
from dataclasses import dataclass, field
from pathlib import Path

import pymupdf as fitz
from PIL import Image, ImageDraw, ImageFilter, ImageFont

A4_W, A4_H = 595, 842
GRAY = (236, 238, 241)
BLACK = (20, 20, 20)
RED = (196, 30, 30)

_FONT_CANDIDATES = [
    (r"C:\Windows\Fonts\malgun.ttf", r"C:\Windows\Fonts\malgunbd.ttf"),
    ("/usr/share/fonts/truetype/nanum/NanumGothic.ttf", "/usr/share/fonts/truetype/nanum/NanumGothicBold.ttf"),
    ("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"),
    ("/System/Library/Fonts/AppleSDGothicNeo.ttc", "/System/Library/Fonts/AppleSDGothicNeo.ttc"),
]


def find_fonts() -> tuple[str, str]:
    env = os.environ.get("DOCGEN_FONT")
    if env:
        return env, os.environ.get("DOCGEN_FONT_BOLD", env)
    for regular, bold in _FONT_CANDIDATES:
        if Path(regular).exists():
            return regular, bold if Path(bold).exists() else regular
    raise RuntimeError("한글 글꼴을 찾지 못했습니다. DOCGEN_FONT 환경변수로 TTF 경로를 지정하세요.")


def won(n: int) -> str:
    return f"{n:,}"


def kdate(iso: str) -> str:
    y, m, d = iso.split("-")
    return f"{int(y)}년 {int(m)}월 {int(d)}일"


def dotdate(iso: str) -> str:
    return iso.replace("-", ".")


class PilCanvas:
    """A4 한 장을 이미지로 그린다(좌표 단위: pt, scale 배 확대)."""

    def __init__(self, scale: float = 2.0):
        self.scale = scale
        self.img = Image.new("RGB", (int(A4_W * scale), int(A4_H * scale)), "white")
        self.draw = ImageDraw.Draw(self.img)
        self.regular, self.bold = find_fonts()
        self._cache: dict = {}

    def _font(self, size: float, bold: bool):
        key = (round(size * self.scale), bold)
        if key not in self._cache:
            self._cache[key] = ImageFont.truetype(self.bold if bold else self.regular, key[0])
        return self._cache[key]

    def text(self, x, y, s, size=10.5, bold=False, anchor="lm", color=BLACK):
        self.draw.text((x * self.scale, y * self.scale), s, font=self._font(size, bold), fill=color, anchor=anchor)

    def line(self, x0, y0, x1, y1, w=0.8, color=BLACK):
        self.draw.line([x0 * self.scale, y0 * self.scale, x1 * self.scale, y1 * self.scale], fill=color,
                       width=max(1, round(w * self.scale)))

    def rect(self, x0, y0, x1, y1, w=0.8, fill=None, color=BLACK):
        self.draw.rectangle([x0 * self.scale, y0 * self.scale, x1 * self.scale, y1 * self.scale], outline=color,
                            fill=fill, width=max(1, round(w * self.scale)))

    def image(self) -> Image.Image:
        return self.img


class PdfCanvas:
    """A4 한 장을 텍스트 레이어가 있는 PDF로 그린다."""

    def __init__(self):
        self.doc = fitz.open()
        self.page = self.doc.new_page(width=A4_W, height=A4_H)
        self.regular, self.bold = find_fonts()
        self.page.insert_font(fontname="kr", fontfile=self.regular)
        self.page.insert_font(fontname="krb", fontfile=self.bold)
        self._fr = fitz.Font(fontfile=self.regular)
        self._fb = fitz.Font(fontfile=self.bold)

    @staticmethod
    def _c(color):
        return tuple(v / 255 for v in color)

    def text(self, x, y, s, size=10.5, bold=False, anchor="lm", color=BLACK):
        f = self._fb if bold else self._fr
        w = f.text_length(s, fontsize=size)
        if anchor[0] == "m":
            x -= w / 2
        elif anchor[0] == "r":
            x -= w
        self.page.insert_text((x, y + size * 0.36), s, fontname="krb" if bold else "kr", fontsize=size,
                              color=self._c(color))

    def line(self, x0, y0, x1, y1, w=0.8, color=BLACK):
        self.page.draw_line((x0, y0), (x1, y1), color=self._c(color), width=w)

    def rect(self, x0, y0, x1, y1, w=0.8, fill=None, color=BLACK):
        self.page.draw_rect(fitz.Rect(x0, y0, x1, y1), color=self._c(color), width=w,
                            fill=self._c(fill) if fill else None)

    def pdf_bytes(self) -> bytes:
        self.doc.subset_fonts()
        return self.doc.tobytes(deflate=True, garbage=4)


def table(c, x, y, widths, rows, row_h=24.0, label_cols=(), header_row=False, aligns=None, size=10.5):
    """격자 표를 그리고 다음 y 좌표를 돌려준다."""
    aligns = aligns or ["l"] * len(widths)
    for r, row in enumerate(rows):
        cx = x
        for i, cell in enumerate(row):
            w = widths[i]
            is_label = i in label_cols or (header_row and r == 0)
            c.rect(cx, y, cx + w, y + row_h, fill=GRAY if is_label else None)
            a = "m" if is_label else aligns[i]
            tx = cx + 10 if a == "l" else cx + w / 2 if a == "m" else cx + w - 10
            if cell:
                c.text(tx, y + row_h / 2, str(cell), size=size, bold=is_label, anchor=a + "m")
            cx += w
        y += row_h
    return y


def footer(c):
    c.text(A4_W / 2, A4_H - 34, "※ 본 문서는 시스템 검증용으로 만든 가상 증빙(SAMPLE)이며 실제 효력이 없습니다.", size=8,
           anchor="mm", color=(120, 120, 120))


def stamp(c, x, y, label="직인"):
    """붉은 사각 직인(가상)."""
    c.rect(x, y - 17, x + 34, y + 17, w=1.6, color=RED)
    c.text(x + 17, y, label, size=10, bold=True, anchor="mm", color=RED)


# ---------------------------------------------------------------------------
# 등록금 납입증명서
# ---------------------------------------------------------------------------
def draw_tuition_certificate(c, d: dict):
    v = d.get("variant", "A")
    title = d.get("title", "등록금 납입증명서")
    c.text(A4_W / 2, 92, title, size=24, bold=True, anchor="mm")
    c.line(190, 112, A4_W - 190, 112, w=1.2)
    c.text(60, 148, f"증명서번호 : 제 {d['certificate_no']} 호", size=10)
    x, y = 60, 166
    W = A4_W - 120
    sem = f"{d['year']}학년도 제{d['term']}학기"
    if v == "A":
        widths = [84, 154, 84, 153]
        rows = [
            ["성  명", d["student_name"], "생년월일", dotdate(d["birth"])],
            ["학교명", d["school_name"], "과  정", d["course"]],
            ["학  과", d["department"], "학  번", d["student_no"]],
            ["학년도/학기", sem, "학  년", f"{d['grade']}학년"],
        ]
        y = table(c, x, y, widths, rows, row_h=30, label_cols=(0, 2))
        amount_label, pay_label = "실납입액", "납입일자"
    else:
        widths = [130, W - 130]
        rows = [
            ["학생성명", d["student_name"]],
            ["대학명", d["school_name"]],
            ["과정구분", d["course"]],
            ["학과(전공)", d["department"]],
            ["등록학기", sem],
        ]
        y = table(c, x, y, widths, rows, row_h=28, label_cols=(0,))
        amount_label, pay_label = "실납입금액", "납부일자"

    y += 26
    c.text(x, y, "■ 납입 내역", size=11.5, bold=True)
    y += 14
    iw = [W * 0.5, W * 0.5]
    head = ["구  분", "금  액(원)"] if v == "A" else ["납입항목", "납입금액(원)"]
    rows = [head] + [[name, won(amt)] for name, amt in d["items"]]
    y = table(c, x, y, iw, rows, row_h=26, header_row=True, aligns=["m", "r"])
    sums = [["등록금 합계", won(d["tuition_total"])]]
    if d.get("show_scholarship", True):
        sums.append(["장학금(감면)", won(d["scholarship_amount"])])
    sums.append([amount_label, won(d["paid_amount"])])
    y = table(c, x, y, iw, sums, row_h=26, label_cols=(0,), aligns=["m", "r"])

    y += 14
    y = table(c, x, y, [84, 154, 84, 153], [[pay_label, kdate(d["payment_date"]), "납입은행", d.get("bank", "한빛은행")],
                                           ["용  도", d.get("purpose", "회사 제출용"), "발급부서", d.get("office", "재무팀")]],
              row_h=28, label_cols=(0, 2))

    y += 46
    c.text(A4_W / 2, y, d.get("statement", "위 학생은 상기 등록금을 납입하였음을 증명합니다."), size=12.5, anchor="mm")
    y += 62
    if d.get("issue_date"):
        c.text(A4_W / 2, y, f"발급일 : {kdate(d['issue_date'])}", size=12, anchor="mm")
    y += 54
    if d.get("issuer"):
        c.text(A4_W / 2 - 20, y, d["issuer"], size=17, bold=True, anchor="mm")
    if d.get("seal", True):
        c.text(A4_W / 2 + 118, y, "(직인)", size=11, anchor="mm")
        stamp(c, A4_W / 2 + 142, y)
    footer(c)


def draw_tuition_bill(c, d: dict):
    """등록금 고지서(납입 사실이 확인되지 않아 증빙으로 인정되지 않는 서류)."""
    c.text(A4_W / 2, 92, "등록금 고지서", size=24, bold=True, anchor="mm")
    c.line(190, 112, A4_W - 190, 112, w=1.2)
    x, y = 60, 150
    W = A4_W - 120
    sem = f"{d['year']}학년도 제{d['term']}학기"
    y = table(c, x, y, [84, 154, 84, 153], [
        ["성  명", d["student_name"], "학  번", d["student_no"]],
        ["학교명", d["school_name"], "학  과", d["department"]],
        ["학년도/학기", sem, "납부기한", kdate(d["payment_date"])],
    ], row_h=30, label_cols=(0, 2))
    y += 26
    c.text(x, y, "■ 고지 내역", size=11.5, bold=True)
    y += 14
    rows = [["구  분", "금  액(원)"]] + [[n, won(a)] for n, a in d["items"]] + [["납부하실 금액", won(d["paid_amount"])]]
    y = table(c, x, y, [W * 0.5, W * 0.5], rows, row_h=26, header_row=True, aligns=["m", "r"])
    y += 40
    c.text(A4_W / 2, y, "위 금액을 납부기한까지 지정 계좌로 납부하여 주시기 바랍니다.", size=12, anchor="mm")
    y += 30
    c.text(A4_W / 2, y, f"가상계좌 : 한빛은행 {d.get('account', '123-456-789012')}", size=11, anchor="mm")
    y += 80
    c.text(A4_W / 2, y, d["issuer"], size=17, bold=True, anchor="mm")
    footer(c)


# ---------------------------------------------------------------------------
# 가족관계증명서
# ---------------------------------------------------------------------------
def draw_family_certificate(c, d: dict):
    c.text(A4_W / 2, 92, "가족관계증명서", size=24, bold=True, anchor="mm")
    c.line(200, 112, A4_W - 200, 112, w=1.2)
    x, y = 60, 150
    W = A4_W - 120
    y = table(c, x, y, [100, W - 100], [["등록기준지", d.get("base_address", "서울특별시 중구 세종대로 110")]], row_h=30,
              label_cols=(0,))
    y += 22
    widths = [70, 100, 110, 135, 60]
    rows = [["구분", "성명", "출생연월일", "주민등록번호", "성별"]]
    for m in d["members"]:
        rows.append([m["relation"], m["name"], kdate(m["birth"]).replace(" ", ""), m["rrn"], m["gender"]])
    y = table(c, x, y, widths, rows, row_h=30, header_row=True, aligns=["m", "m", "m", "m", "m"])
    y += 52
    c.text(A4_W / 2, y, "위 가족관계증명서는 가족관계등록부의 기록사항과 틀림없음을 증명합니다.", size=11.5, anchor="mm")
    y += 60
    c.text(A4_W / 2, y, f"발급일 : {kdate(d['issue_date'])}", size=12, anchor="mm")
    y += 52
    c.text(A4_W / 2 - 20, y, "법원행정처 전산정보중앙관리소 전산운영책임관", size=13, bold=True, anchor="mm")
    stamp(c, A4_W / 2 + 160, y)
    footer(c)


# ---------------------------------------------------------------------------
# 진료비 계산서·영수증
# ---------------------------------------------------------------------------
def draw_medical_receipt(c, d: dict):
    title = d.get("title", "진료비 계산서·영수증")
    c.text(A4_W / 2, 84, title, size=22, bold=True, anchor="mm")
    c.line(170, 103, A4_W - 170, 103, w=1.2)
    x, y = 50, 128
    W = A4_W - 100
    w4 = [88, 160, 88, W - 336]
    y = table(c, x, y, w4, [
        ["환자등록번호", d["patient_no"], "환자성명", d["patient_name"]],
        ["진료기간", d["treatment_period"], "진료과목", d["department"]],
        ["진료내용", d["treatment"], "영수증번호", d["receipt_no"]],
    ], row_h=28, label_cols=(0, 2), size=10)
    y += 18
    widths = [W * 0.31, W * 0.23, W * 0.23, W * 0.23]
    rows = [["항  목", "급여 본인부담금", "급여 공단부담금", "비급여"]]
    for name, own, ins, non in d["lines"]:
        rows.append([name, won(own), won(ins), won(non)])
    rows.append(["합  계", won(sum(l[1] for l in d["lines"])), won(sum(l[2] for l in d["lines"])),
                 won(sum(l[3] for l in d["lines"]))])
    y = table(c, x, y, widths, rows, row_h=24, header_row=True, aligns=["m", "r", "r", "r"], size=10)
    y += 18
    y = table(c, x, y, w4, [
        ["진료비 총액", won(d["total"]), "공단부담 총액", won(d["insurer_total"])],
        ["환자부담 총액", won(d["patient_total"]), "납부한 금액", won(d["paid_amount"])],
        ["수납일", kdate(d["receipt_date"]), "결제수단", d.get("pay_method", "신용카드")],
    ], row_h=28, label_cols=(0, 2), aligns=["l", "r", "l", "r"], size=10)
    y += 18
    y = table(c, x, y, w4, [
        ["요양기관 종류", d.get("institution_kind", ""), "사업자등록번호", d["business_no"]],
        ["상  호", d["hospital_name"], "대표자", d.get("representative", "")],
    ], row_h=28, label_cols=(0, 2), size=10)
    y += 44
    c.text(A4_W / 2, y, "위 금액을 정히 영수하였습니다.", size=12, anchor="mm")
    y += 40
    c.text(A4_W / 2, y, f"발행일 : {kdate(d['receipt_date'])}", size=11.5, anchor="mm")
    y += 44
    c.text(A4_W / 2 - 20, y, d["hospital_name"], size=16, bold=True, anchor="mm")
    stamp(c, A4_W / 2 + 130, y)
    footer(c)


def draw_card_slip(c, d: dict):
    """카드 매출전표(의료비 증빙으로 인정되지 않는 서류)."""
    c.text(A4_W / 2, 110, "신용카드 매출전표", size=22, bold=True, anchor="mm")
    x, y = 150, 160
    y = table(c, x, y, [110, 185], [
        ["가맹점명", d["hospital_name"]],
        ["사업자등록번호", d["business_no"]],
        ["거래일시", dotdate(d["receipt_date"]) + " 14:22"],
        ["카드번호", "5412-34**-****-1234"],
        ["승인번호", "30418822"],
        ["거래금액", won(d["paid_amount"])],
    ], row_h=30, label_cols=(0,))
    footer(c)


DRAWERS = {
    "tuition_certificate": draw_tuition_certificate,
    "tuition_bill": draw_tuition_bill,
    "family_certificate": draw_family_certificate,
    "medical_receipt": draw_medical_receipt,
    "card_slip": draw_card_slip,
}


# ---------------------------------------------------------------------------
# 출력
# ---------------------------------------------------------------------------
def degrade(img: Image.Image, level: str, rng: random.Random) -> Image.Image:
    """스캔·촬영 품질 저하를 흉내 낸다. level: light | heavy"""
    if level == "light":
        angle, blur, noise, q = rng.uniform(-0.7, 0.7), 0.6, 6, 70
    else:
        angle, blur, noise, q = rng.choice([-1, 1]) * rng.uniform(1.2, 2.0), 1.9, 18, 28
    out = img.rotate(angle, resample=Image.BICUBIC, expand=False, fillcolor="white")
    if level == "heavy":
        w, h = out.size
        out = out.resize((int(w * 0.5), int(h * 0.5)), Image.BILINEAR).resize((w, h), Image.BILINEAR)
    out = out.filter(ImageFilter.GaussianBlur(blur))
    px = out.load()
    w, h = out.size
    nrng = random.Random(rng.random())
    for _ in range(int(w * h * (0.004 if level == "light" else 0.02))):
        xx, yy = nrng.randrange(w), nrng.randrange(h)
        r, g, b = px[xx, yy]
        dv = nrng.randint(-noise * 6, noise)
        px[xx, yy] = (max(0, min(255, r + dv)), max(0, min(255, g + dv)), max(0, min(255, b + dv)))
    buf = io.BytesIO()
    out.save(buf, "JPEG", quality=q)
    return Image.open(io.BytesIO(buf.getvalue())).convert("RGB")


def render(kind: str, data: dict, fmt: str = "png", quality: str | None = None, seed: int = 0) -> bytes:
    """fmt: png | jpg | pdf(텍스트 레이어) | scanpdf(이미지 PDF)"""
    draw = DRAWERS[kind]
    if fmt == "pdf":
        c = PdfCanvas()
        draw(c, data)
        return c.pdf_bytes()
    c = PilCanvas()
    draw(c, data)
    img = c.image()
    if quality:
        img = degrade(img, quality, random.Random(seed))
    buf = io.BytesIO()
    if fmt == "png":
        img.save(buf, "PNG", optimize=True)
    elif fmt == "jpg":
        img.save(buf, "JPEG", quality=88)
    elif fmt == "scanpdf":
        jb = io.BytesIO()
        img.save(jb, "JPEG", quality=85)
        doc = fitz.open()
        page = doc.new_page(width=A4_W, height=A4_H)
        page.insert_image(page.rect, stream=jb.getvalue())
        return doc.tobytes(deflate=True)
    else:
        raise ValueError(fmt)
    return buf.getvalue()
