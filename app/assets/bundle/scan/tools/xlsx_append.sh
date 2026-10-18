#!/system/bin/sh
# xlsx_append.sh —— 手机端「追加入库」：把今日新增 TSV 追加进库 xlsx 并自证
# 依赖：zipw.sh + append.awk（同目录）+ toybox(unzip/gzip/awk/sed/grep/wc/printf/date)
# 用法：sh xlsx_append.sh <库.xlsx> <新增.tsv> <输出.xlsx> <YYYY-MM-DD>
# 说明：TSV 为制表符分隔 12 列（序号 日期 类型 地区 城市 主体 标题 金额 截止 来源 链接 关键词组），
#       第13/14 列（源文件/原文摘录）可有可无，本脚本只取前 12 列。
set -e
export PATH=/system/bin:/system/xbin:$PATH
BASE=$(pwd)
abs() { case "$1" in /*) printf '%s' "$1" ;; *) printf '%s/%s' "$BASE" "$1" ;; esac }
DB=$(abs "$1")
NEW=$(abs "$2")
OUT=$(abs "$3")
DATE=$4
TOOL=$(cd "$(dirname "$0")" && pwd)
TIME=$(date +%H:%M 2>/dev/null || printf '00:00')

W="${STEREO_WORK_DIR:-$(dirname "$OUT")}/.xlsxwork.$$"
trap 'rm -rf "$W"' EXIT INT TERM
rm -rf "$W"
mkdir -p "$W/parts"
unzip -q "$DB" -d "$W/parts"

rows_of() { grep -o '</row>' "$1" | wc -l | tr -d ' '; }

n_all=$(wc -l < "$NEW" | tr -d ' ')
n_gg=$(awk -F'\t' '$3=="公告"' "$NEW" | wc -l | tr -d ' ')
n_yx=$(awk -F'\t' '$3=="意向"' "$NEW" | wc -l | tr -d ' ')
n_zb=$(awk -F'\t' '$3=="结果"' "$NEW" | wc -l | tr -d ' ')
n_wb=$(awk -F'\t' '$3=="其他"' "$NEW" | wc -l | tr -d ' ')

sn_of() {
  case "$1" in
    "公告") printf '2' ;;
    "意向") printf '3' ;;
    "结果") printf '4' ;;
    "其他") printf '5' ;;
    *) printf '' ;;
  esac
}
ex_of() { # 指定分表现有数据行数
  f="$W/parts/xl/worksheets/sheet$1.xml"
  [ -f "$f" ] || { printf '0'; return; }
  printf '%s' $(( $(rows_of "$f") - 3 ))
}

tot_gg=$(( $(ex_of 2) + n_gg ))
tot_yx=$(( $(ex_of 3) + n_yx ))
tot_zb=$(( $(ex_of 4) + n_zb ))
tot_wb=$(( $(ex_of 5) + n_wb ))

for sn in 1 2 3 4 5; do
  f="$W/parts/xl/worksheets/sheet$sn.xml"
  [ -f "$f" ] || continue
  if [ "$sn" = "1" ]; then
    sub="$NEW"
    mode=all
  else
    tp=""
    case "$sn" in
      2) tp="公告" ;;
      3) tp="意向" ;;
      4) tp="结果" ;;
      5) tp="其他" ;;
    esac
    sub="$W/f$sn.tsv"
    awk -F'\t' -v t="$tp" '$3==t' "$NEW" > "$sub"
    mode=one
  fi
  [ "$(wc -l < "$sub" | tr -d ' ')" -gt 0 ] || continue
  awk -F'\t' -v NEW="$sub" -v DATE="$DATE" -v TIME="$TIME" -v STATS_MODE="$mode" \
      -v N_GG="$tot_gg" -v N_YX="$tot_yx" -v N_ZB="$tot_zb" -v N_WB="$tot_wb" \
      -f "$TOOL/append.awk" "$f" > "$W/out$sn.xml"
  mv "$W/out$sn.xml" "$f"
done

sh "$TOOL/zipw.sh" "$W/parts" "$W/result.xlsx" > /dev/null
mv "$W/result.xlsx" "$OUT"

# ── 门3 回读自证：解交付件本体，逐条确认主体在库 ──
unzip -t "$OUT" > "$W/t.txt" 2>&1 || true
grep -q "No errors detected" "$W/t.txt" || { echo "FAIL: zip 完整性未通过"; exit 1; }

unzip -p "$OUT" xl/worksheets/sheet1.xml > "$W/chk.xml"
awk -F'\t' '{ if ($6 != "") print $6 }' "$NEW" > "$W/units.txt"
tot=$(wc -l < "$W/units.txt" | tr -d ' ')
k=0
while IFS= read -r u; do
  if grep -qF -- "$u" "$W/chk.xml"; then
    k=$((k + 1))
  else
    echo "MISS: $u"
  fi
done < "$W/units.txt"

rows=$(rows_of "$W/chk.xml")
echo "verified=$k/$tot  rows: $(( rows - 3 ))"
if [ "$k" -lt "$tot" ]; then
  echo "FAIL: 有记录未进库，禁止交付"
  exit 1
fi
echo "OK: $OUT"
