# Word 制作规范

> **🚫 桌面端专属 — 手机做不到，命中就转告用户，禁止尝试、禁止报完成**
> 本规范依赖 `python` + `python-docx` 读写 .docx，手机上**没有 python**，
> 因此**无法生成或修改 .docx**（pptx 同理）。
> 正确回复示例：「做 Word 要在桌面端跑（需要 python-docx），手机这边没有；
> 要我现在把内容和排版要求整理好，还是等你到桌面端再出文件？」

> 基于一次表格重构事故提取的铁律。

---

## 一、前置规则：不读不准写

```
操作 docx 前必须用 python-docx 读取原文件：
  - 每个段落的字体名 (run.font.name)
  - 字号 (run.font.size)
  - 加粗/斜体
  - 段落样式 (paragraph.style.name)
  - 行距、缩进
打印出来，确认后再动手。
```

---

## 二、格式铁律

| # | 规则 | 说明 |
|---|------|------|
| **1** | **原文件格式照搬** | 字体、字号、加粗、行距、缩进 — 全部跟原文件一致。不「优化」、不「美化」。 |
| **2** | **fontTable 原封不动** | 禁止替换字体表。原文件声明了哪些字体就保留哪些，字体表必须包含文档实际使用的所有字体。 |
| **3** | **结构只做要求的改动** | 用户说「改成表格」→ 只改结构，不趁机加表头底色、隔行着色、红蓝标记、图例。 |
| **4** | **禁止擅自加装饰** | 深蓝表头、隔行色、颜色标记、图例说明 — 全部禁止，除非用户明确要求。 |
| **5** | **w:szCs 保持** | 原文件有 w:szCs 就必须保留，不能丢失。 |
| **6** | **styles.xml 不能膨胀** | 禁止把几KB的 styles.xml 替换成几百KB的模板。用原文件的样式。 |

---

## 三、事故案例（表格重构）

### 事故经过
Agent 将段落结构文档转为表格结构，同时：
- **fontTable 全部替换**：原文件 `等线、Times New Roman、宋体、仿宋_GB2312、等线 Light` → 变成 `Symbol、Times New Roman、Cambria、MS明朝、Calibri、MSゴシック、Courier、Arial`
- **宋体未声明却在用**：文档全文使用宋体，但字体表里根本没有宋体
- **styles.xml 从 31KB 膨胀到 349KB**
- **w:szCs 丢失**
- **行距和缩进全部丢失**

### 教训
**做 Word 前必须读原文件。读完照搬格式。做完立即读回验证。**

---

## 四、验证清单

每次输出 docx 后立即执行：

```python
import zipfile, re

z = zipfile.ZipFile('output.docx')
doc = z.read('word/document.xml').decode('utf-8')
ft = z.read('word/fontTable.xml').decode('utf-8')

# 1. 字体表是否包含文档使用的所有字体
fonts_in_use = set(re.findall(r'w:ascii="([^"]+)"', doc) + re.findall(r'w:eastAsia="([^"]+)"', doc))
fonts_declared = set(re.findall(r'w:name="([^"]+)"', ft))
missing = fonts_in_use - fonts_declared
assert not missing, f"字体表中缺失: {missing}"

# 2. 字号是否与原文件一致
sizes = re.findall(r'w:sz w:val="(\d+)"', doc)
print(f"字号: {set(sizes)}")

# 3. styles.xml 大小是否异常
st = z.read('word/styles.xml')
print(f"styles.xml: {len(st)} bytes (正常 < 50KB)")

# 4. 结构是否与原文件一致
print(f"表格数: {doc.count('<w:tbl>')}")

print("验证通过" if not missing else f"失败: 缺字体 {missing}")
```
