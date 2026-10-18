#!/system/bin/sh
# xlsx_export.sh —— 手机端「读库」：把库 xlsx 的总表导出为 TSV 文本，供检索去重
# 依赖：xlsx2tsv.awk（同目录）+ toybox(unzip/awk/wc)
# 用法：sh xlsx_export.sh <库.xlsx> <输出.tsv>
# 输出：12 列 TSV（序号 日期 类型 地区 城市 主体 标题 金额 截止 来源 链接 关键词组），UTF-8
set -e
export PATH=/system/bin:/system/xbin:$PATH
BASE=$(pwd)
abs() { case "$1" in /*) printf '%s' "$1" ;; *) printf '%s/%s' "$BASE" "$1" ;; esac }
DB=$(abs "$1")
OUT=$(abs "$2")
TOOL=$(cd "$(dirname "$0")" && pwd)

unzip -p "$DB" xl/worksheets/sheet1.xml | awk -f "$TOOL/xlsx2tsv.awk" > "$OUT"
n=$(wc -l < "$OUT" | tr -d ' ')
echo "exported=$n -> $OUT"
