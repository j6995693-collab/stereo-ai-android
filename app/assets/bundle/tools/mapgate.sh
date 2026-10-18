#!/system/bin/sh
# mapgate.sh —— 地图成品坐标门（手机本机可执行，纯 toybox）
# 用法: sh mapgate.sh <成品.html> [最小点数,默认50]
# 退出码: 0=PASS  1=FAIL  2=用法错误
export PATH=/system/bin:/system/xbin:$PATH
TOOL=$(dirname "$0")
G="$TOOL/mapgate.awk"
F="$1"
MIN="${2:-50}"

if [ -z "$F" ] || [ ! -f "$F" ]; then
  echo "用法: sh mapgate.sh <成品.html> [最小点数]"
  echo "FAIL: 找不到文件 $F"
  exit 2
fi
if [ ! -f "$G" ]; then
  echo "FAIL: 缺少 $G"
  exit 2
fi

out=$(awk -v MINPTS="$MIN" -f "$G" "$F" 2>&1)
rc=$?
printf '%s\n' "$out"
if [ $rc -ne 0 ]; then
  echo "FAIL: awk 执行出错（rc=$rc），坐标门未通过"
  exit 1
fi

verdict=$(printf '%s\n' "$out" | awk -F'\t' '/^SUM\t判定/{print $3}')
echo "----"
echo "MAPGATE=$verdict  ($F)"
case "$verdict" in
  PASS) exit 0 ;;
  N/A*) exit 0 ;;
  *)    exit 1 ;;
esac
