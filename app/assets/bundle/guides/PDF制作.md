# PDF 制作规范 v2.0

> **🚫 桌面端专属 — 手机做不到，命中就转告用户，禁止尝试、禁止报完成**
> 本规范依赖的工具链在手机上**全部不存在**：
> `python` / `PyMuPDF(fitz)` / `weasyprint`（渲染 PDF）、本地 OCR 服务。
> 手机工具链实测只有 toybox（无 python / node / perl / zip 等），**无法按本规范执行**。
> 正确回复示例：「做 PDF 要在桌面端跑（需要 python + weasyprint + 本地 OCR），
> 手机这边没有；要我现在把内容整理好，还是等你到桌面端再出 PDF？」
> （注意：这只是说"做 PDF"做不到；地图导航规范 `AI地图制作.md` 手机是能做的。）

> 基于多个 PDF 排版项目的踩坑总结，持续优化。
> **重大更新：新增第七章「铁律红线」，基于一次方案制作过程中 9 次严重错误的复盘。**

---

## 一、文件管理

### 1.1 版本控制
```bash
# 改前备份
cp target.pdf target_v1_orig.pdf

# 每次改动存新版本
cp target.pdf target_v2_xxx.pdf

# 定稿后统一删除版本文件
```

### 1.2 禁止行为
- ❌ 直接覆盖原文件
- ❌ 反复 overwrite 同一个文件
- ❌ PyMuPDF `insert_image` 多次叠加（用 `page.clean_contents()` 或从原版重新开始）

---

## 二、参数提取（禁止猜测）

### 2.1 图表参数
```python
# 从 PDF 提取嵌入图片
import fitz
doc = fitz.open('file.pdf')
for img in doc[page_num].get_images(full=True):
    base = doc.extract_image(img[0])
    with open(f'extracted_{base["width"]}x{base["height"]}.png', 'wb') as f:
        f.write(base['image'])
```

### 2.2 像素采样
```python
from PIL import Image
import numpy as np
arr = np.array(Image.open('extracted.png'))
# 采样柱颜色
px = arr[y, x]  # RGB
# 扫描柱宽度/间距
colored_cols = [x for x in range(w) if np.mean(arr[mid_y, x]) < 230]
```

### 2.3 文本样式
```python
# 从原 PDF 提取精确字体/大小/颜色
for span in page.get_text('dict')['spans']:
    print(f'font={span["font"]}, size={span["size"]}, color=#{span["color"]:06x}')
```

### 2.4 物理尺寸换算
```
1cm 在 PDF 中 = 28.35 points (1pt = 0.353mm)
图表数据单位 → 像素: data_range / (chart_height_px) * (1cm_in_px)
1cm_in_px at 100dpi = 39.4px, at 200dpi = 78.7px, at 600dpi = 236px
```

---

## 三、工具选择

| 场景 | 工具 | 原因 |
|------|------|------|
| 图表重绘 | matplotlib | 精确控制颜色/位置/比例 |
| PDF 原位文字替换（短文本） | PyMuPDF redaction | 保留原格式 |
| PDF 文字替换（长文本/需匹配字体） | PIL + STHeiti.ttf 渲染为图片再插入 | 字体/行距可控 |
| 页面重建（大面积改动） | weasyprint HTML | 矢量输出、字体匹配 |
| 视觉复核 | 视觉模型（`read_image`） | 每次改动后必做 |
| OCR 提取 | 本地 OCR 或视觉模型 | 精确定位图表文字 |

### 常用字体路径
```
STHeiti Regular: /System/Library/AssetsV2/.../STHEITI.ttf
PingFang SC: /System/Library/Fonts/PingFang.ttc
```

---

## 四、文本排版

### 4.1 中文折行
```python
def wrap_chinese(text, max_width, font, draw):
    """逐字累加测宽，填满像素宽度再换行"""
    lines = []
    current = ''
    for char in text:
        test = current + char
        w = draw.textbbox((0, 0), test, font=font)[2]
        if w > max_width and current:
            lines.append(current)
            current = char
        else:
            current = test
    if current:
        lines.append(current)
    return lines
```

### 4.2 行距计算
```
从原 PDF 量出基线间距 → 换算为渲染 DPI 下的 spacing 值
spacing_px = (baseline_gap_pt - font_size_pt) * DPI / 72
```

### 4.3 PIL 文本渲染模板
```python
from PIL import Image, ImageDraw, ImageFont
DPI = 600  # 4K 分辨率
font = ImageFont.truetype(FONT_PATH, int(font_size_pt * DPI / 72))
color = (r, g, b)  # 从原 PDF span 提取

img_w = int(textbox_width_pt * DPI / 72)
wrapped = wrap_chinese(text, img_w - 16, font, draw)
bbox = ImageDraw.Draw(test_img).multiline_textbbox((8, 0), wrapped, font=font, spacing=spacing_px)
img_h = bbox[3] - bbox[1] + 16
img = Image.new('RGBA', (img_w, img_h), (255, 255, 255, 0))
ImageDraw.Draw(img).multiline_text((8, 8), wrapped, font=font, fill=color, spacing=spacing_px)

# 插入 PDF
img_h_pts = img_h / DPI * 72
page.insert_image(fitz.Rect(x, y, x + w_pt, y + img_h_pts), filename='tmp.png')
```

---

## 五、流程规范

### 5.1 执行前
1. 确认操作对象：哪个文件、哪个元素
2. 备份原文件
3. 提取原参数（OCR + 像素采样 + 视觉模型确认）

### 5.2 执行中
1. 一条指令只改一个东西
2. 改完立即生成视觉快照

### 5.3 执行后（OCR优先复核）
```bash
# 1. 本地 OCR 精确提取（优先，免费 MPS 加速）
cd <你的 OCR 工具目录> && \
<你的 python> run_mps.py \
  --model-dir ./model_hf \
  --image "output.png" \
  --prompt "<image>Extract all text with bounding boxes." \
  --output ./output
# 读取结果: ./output/result.md

# 2. 视觉复核（视觉模型，OCR 无法判断时使用）
read_image "output.png" "确认修改项是否正确？"

# 3. 像素对比
python -c "compare original vs modified pixel diff"
# 文本检查
python -c "check text content preservation"
```

---

## 六、踩坑清单

| 坑 | 原因 | 正确做法 |
|----|------|----------|
| PyMuPDF insert_image 堆叠 | 每次 insert 追加而非替换 | clean_contents 或从原版重来 |
| matplotlib 颜色猜错 | 凭印象取值 | 像素采样取精确 RGB |
| textwrap.fill 断句乱 | 按字符数断，不管语义 | 自写逐字测宽折行 |
| spacing=6 行距太紧 | 没从原 PDF 量基线间距 | 量 → 换算 → 设置 |
| 字体 'china-s' 不匹配 | PyMuPDF 内置字体 | 用原 PDF 的 STHeiti.ttf |
| 菱形下移反复调 | 没算物理尺寸 | DPI × 数据范围换算 |

---

## 七、铁律红线（方案制作血泪复盘）

### 7.1 执行纪律

| # | 铁律 | 错误案例 | 正确做法 |
|---|------|----------|----------|
| 1 | **Read 不行立刻换工具** | Read 返回 PDF 无法渲染，直接放弃报告用户 | 立即用 PyMuPDF fitz 提取文本+图片，再用视觉模型描述图表 |
| 2 | **做图前必须调用 dataviz skill** | 直接写 matplotlib，导致双 Y 轴违规 | `Skill("dataviz")` → 读 palette + anti-patterns → 再写代码 |
| 3 | **改参数后必须像素实测** | bar_w 0.52→0.30，但 xlim 也缩了，相对宽度 26%→27% 完全没变 | 改后用 PIL 逐列扫描 bar 宽度，算百分比，不只看代码 |
| 4 | **只修被指出的问题，不动其他** | 用户说行距溢出→擅自缩图表尺寸→推倒 weasyprint 换 PyMuPDF | 用 `diff` 对比改前改后，确认只动了指定元素 |
| 5 | **排版溢出优先调 CSS，不动构建方案** | 结论超出打印线→把整个构建从 weasyprint 换成 PyMuPDF | 先调 CSS margin/line-height/font-size，不够再考虑其他 |
| 6 | **所有文件操作在工作目录内** | PDF 写到项目目录之外 | 必须写到当前项目工作目录下 |

### 7.2 weasyprint 专坑

| 坑 | 原因 | 正确做法 |
|----|------|----------|
| **base64 图片全部叠加在同一坐标** | weasyprint 对 `data:image/png;base64,...` 的定位有 bug | 用 `<img src="绝对路径.png">` 文件引用，不用 base64 |
| 绝对路径 vs 相对路径 | weasyprint 解析相对路径不稳定 | 用 Python 变量拼接绝对路径：`f'{OUT}/chart.png'` |
| 页脚与正文重叠 | `position: absolute; bottom: 6mm` 不参与流式布局 | 确保正文末行距页面底部 ≥ 20pt（约 7mm），给页脚留空间 |

### 7.3 matplotlib 图表校验

每次生成 matplotlib 图表后，必须执行：

```python
# 1. 像素测柱宽/间距
from PIL import Image; import numpy as np
arr = np.array(Image.open('chart.png'))
h, w, _ = arr.shape
row = arr[h-60, :, :3]  # 近底部扫描
blue = (row[:,2] > 200) & (row[:,0] < 100)
cols = np.where(blue)[0]
breaks = np.where(np.diff(cols) > 5)[0]
starts = [cols[0]] + [cols[b+1] for b in breaks]
ends = [cols[b] for b in breaks] + [cols[-1]]
for i, (s, e) in enumerate(zip(starts, ends)):
    print(f'Bar {i}: {(e-s)/w*100:.1f}% width')

# 2. 像素验证注释无背景
top = arr[y0:y1, x0:x1, :3]
green_pct = ((top[:,:,1] > 240) & (top[:,:,0] < 245)).mean() * 100
assert green_pct < 1.0, f"注释有 {green_pct:.1f}% 绿色背景！"

# 3. 像素验证蓝色柱内无白色文字
interior = arr[bar_y0:bar_y1, bar_x0+20:bar_x1-20, :3]
white_pct = ((interior > 240).all(axis=2)).mean() * 100
assert white_pct < 1.0, f"柱内有 {white_pct:.1f}% 白色文字！"
```

### 7.4 PyMuPDF 排版避坑

| 坑 | 原因 | 正确做法 |
|----|------|----------|
| `insert_textbox` 静默截断文本 | `china-s` 字体实际高度 = fontsize × 1.8，不是 × 1.2 | 文本框高度 ≥ fontsize × 2（加余量） |
| `insert_textbox` 返回 `rc` | rc > 0 = 剩余空间，rc < 0 = **溢出** | 每次调用后检查 `if rc < -5: print(f'OVERFLOW')` |
| 文本提取乱码 | `china-s` 字体无 Unicode 映射，`get_text()` 无法解码 | 视觉核验用视觉模型，文本核验用像素对比 |
| 图表插入体积大 | `insert_image` 嵌入原始分辨率 PNG | 插入前先 resize PNG 到目标尺寸 |

### 7.5 视觉复核局限性

视觉模型可能误判以下情况：
- 柱宽变化（难以感知相对比例）
- 注释有无背景色（可能把文字颜色当背景）
- 图表元素是否在正确的绝对位置

**因此视觉模型结果必须用像素实测佐证**，不可单独采信。像素采样方法见 §7.3。

### 7.6 交付前自检清单

```markdown
□ Read 提取了所有文本、字体、图片
□ dataviz skill 已调用、色板已验证
□ 图表参数（柱宽/间距/位置/注释）像素实测通过
□ weasyprint 图片用文件路径不用 base64
□ PyMuPDF 文本框高度 ≥ fontsize × 2，无 rc < 0
□ 每页底部正文距页面底部 ≥ 20pt
□ 所有文件在 <项目工作目录>/ 内
□ 视觉复核 + 像素数据双确认
□ 改后 diff 确认只动了指定内容
