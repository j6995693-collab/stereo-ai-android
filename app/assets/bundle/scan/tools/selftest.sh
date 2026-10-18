#!/system/bin/sh
# selftest.sh —— 每日扫描开工自检：工具链 / 库可读 / 目标目录可写 / zip 写入器可用
# 用法：sh selftest.sh <库.xlsx>
# 输出：每项一行 OK/FAIL，最后一行 SELFTEST=PASS 或 SELFTEST=FAIL
# 硬要求：SELFTEST=FAIL 时禁止开始扫描，先把 FAIL 项报给用户。
export PATH=/system/bin:/system/xbin:$PATH
TOOL=$(cd "$(dirname "$0")" && pwd)
LIB=$1
DIR=$(dirname "$LIB")
fail=0

chk() { # chk <描述> <退出码>
  if [ "$2" = "0" ]; then echo "OK   $1"; else echo "FAIL $1"; fail=1; fi
}

for t in awk sed grep unzip gzip sort wc head tail printf find cp date rm mkdir; do
  command -v "$t" > /dev/null 2>&1
  chk "工具 $t" $?
done

command -v date > /dev/null 2>&1 && [ -n "$(date +%Y-%m-%d)" ]
chk "date 可用" $?

[ -f "$LIB" ]; chk "库存在 $LIB" $?
[ -r "$LIB" ]; chk "库可读" $?

unzip -l "$LIB" > /dev/null 2>&1
chk "库是合法 zip" $?

unzip -p "$LIB" xl/worksheets/sheet1.xml > /dev/null 2>&1
chk "库含 sheet1.xml" $?

PROBE="$DIR/.wtest.$$"
( : > "$PROBE" ) 2>/dev/null
chk "目标目录可写 $DIR" $?
rm -f "$PROBE" 2>/dev/null

T="${STEREO_WORK_DIR:-/data/local/tmp}/selftest.$$"
rm -rf "$T"; mkdir -p "$T/d"
printf 'ok\n' > "$T/d/a.txt"
printf 'x' > "$T/d/b.txt"
sh "$TOOL/zipw.sh" "$T/d" "$T/t.zip" > /dev/null 2>&1
chk "zipw 打包" $?
unzip -t "$T/t.zip" > /dev/null 2>&1
chk "zipw 产物可解包" $?
rm -rf "$T"

if [ "$fail" = "0" ]; then echo "SELFTEST=PASS"; else echo "SELFTEST=FAIL"; fi
exit $fail
