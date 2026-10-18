# srccheck.awk —— 门1/门2 自动核对：TSV 字段能不能在原文存档里逐项找到
# 纯 awk，toybox / macOS awk / mawk 通用，不用 python
# 用法: awk -v SRC=<原始源文件目录> -v LIST=<文件名清单> -f srccheck.awk 新增.tsv
#
# 配套约定：原始源文件目录里该条的存档文件名必须以 <序号补零两位>_ 开头
#   （与 TASK_TEMPLATE.md 第2步 "<序号>_<主体简称>" 命名一致）
#
# 三项判据（缺一即该条 FAIL）：
#   A 主体全称出现在原文
#   B 金额：原文含 金额(万元)x10000 元 或 金额+"万元"
#   C 截止日期：原文含 YYYY年M月D日 或 YYYY年MM月DD日 或 YYYY-MM-DD
# N/A 的 B/C 项跳过（门2 允许取不到写 N/A，但不许写错数字）
#
# 末行 SRCCHECK=PASS K/N，K<N 时退出码 1

BEGIN {
  FS = "\t"                      # 新增 TSV 是制表符分隔，绝不能按空白切
  if (SRC == "") { print "FAIL: -v SRC= 未给"; exit 2 }
  if (LIST == "") { print "FAIL: -v LIST= 未给"; exit 2 }
  n = 0
  while ((getline fn < LIST) > 0) {
    if (fn == "") continue
    n++; FL[n] = fn
  }
  close(LIST)
}
{
  if (NF < 9) next
  if ($1 !~ /^[0-9]+$/) next
  N++
  idx  = $1 + 0
  unit = $6; amt = $8; dt = $9
  pre  = sprintf("%02d_", idx)

  path = ""
  for (i = 1; i <= n; i++) if (index(FL[i], pre) == 1) { path = SRC "/" FL[i]; break }

  miss = ""
  if (path == "") {
    miss = miss " 存档缺失(" pre "*)"
    printf "ROW\t%d\t%s\tFAIL\t%s\n", idx, unit, miss
    next
  }

  buf = ""
  while ((getline line < path) > 0) buf = buf line "\n"
  close(path)

  # 归一：去空白与千分位逗号，全角括号/逗号转半角
  norm = buf
  gsub(/[ \t\r\n]/, "", norm)
  gsub(/　/, "", norm)
  gsub(/,/, "", norm)            # 千分位逗号：2,499,300.00 与 2499300 是同一个数
  gsub(/，/, ",", norm)
  gsub(/（/, "(", norm)
  gsub(/）/, ")", norm)

  # A 主体
  u = unit; gsub(/[ \t]/, "", u)
  if (index(norm, u) == 0) miss = miss " 主体不在原文"

  # B 金额
  if (amt != "" && amt != "N/A") {
    yuan = sprintf("%.0f", amt * 10000)
    ok = (index(norm, yuan) > 0)
    if (!ok) ok = (index(norm, amt "万元") > 0)
    if (!ok) ok = (index(norm, amt "(万元)") > 0)
    if (!ok) miss = miss " 金额对不上(" amt "万/" yuan "元)"
  }

  # C 截止日期
  if (dt != "" && dt != "N/A" && dt ~ /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/) {
    split(dt, d, "-")
    p1 = sprintf("%s年%d月%d日", d[1], d[2] + 0, d[3] + 0)
    p2 = sprintf("%s年%02d月%02d日", d[1], d[2] + 0, d[3] + 0)
    ok = (index(norm, p1) > 0 || index(norm, p2) > 0 || index(norm, dt) > 0)
    if (!ok) miss = miss " 日期对不上(" dt ")"
  }

  if (miss == "") { K++; printf "ROW\t%d\t%s\tOK\t\n", idx, unit }
  else            { printf "ROW\t%d\t%s\tFAIL\t%s\n", idx, unit, miss }
}
END {
  printf "SRCCHECK=%s %d/%d\n", (K == N && N > 0) ? "PASS" : "FAIL", K, N
  if (K != N || N == 0) exit 1
}
