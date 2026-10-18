#!/system/bin/sh
# srccheck.sh —— 门1/门2 自动核对（手机纯 toybox，不用 python）
# 用法: sh srccheck.sh <新增.tsv> <原始源文件目录>
# 末行 SRCCHECK=PASS K/N 且 K==N 才允许进第3步；否则退出码 1
TSV="$1"
SRC="$2"
D=$(dirname "$0")

[ -f "$TSV" ] || { echo "FAIL: 找不到 $TSV"; exit 2; }
[ -d "$SRC" ] || { echo "FAIL: 找不到目录 $SRC"; exit 2; }

W="${TMPDIR:-/data/local/tmp}/srccheck.$$"
mkdir -p "$W" || exit 2
ls "$SRC" > "$W/list.txt" || { echo "FAIL: 列目录失败 $SRC"; rm -rf "$W"; exit 2; }

awk -v SRC="$SRC" -v LIST="$W/list.txt" -f "$D/srccheck.awk" "$TSV"
rc=$?
rm -rf "$W" 2>/dev/null
exit $rc
