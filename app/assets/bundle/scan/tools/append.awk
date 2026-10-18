# 单表 xlsx sheet XML 追加行（行样式从本表第4/5行自动继承）
# 用法: awk -F'\t' -v NEW=新增.tsv -v DATE=YYYY-MM-DD -v TIME=HH:MM \
#            -v STATS_MODE=all|one -v N_GG= -v N_YX= -v N_ZB= -v N_WB= \
#            -f append.awk sheet.xml > out.xml
BEGIN {
  FS = "\t"
  nnew = 0
  if (NEW != "") {
    while ((getline line < NEW) > 0) {
      if (line == "") continue
      nnew++
      split(line, f, "\t")
      for (i = 1; i <= 12; i++) V[nnew, i] = f[i]
    }
    close(NEW)
  }
}
NR == 1 { xml = $0 }
END {
  n = split(xml, P, /<\/row>/)
  lastrow = n - 1

  # 列样式快照：第4行=奇数行模板，第5行=偶数行模板
  for (k = 4; k <= 5; k++) {
    s = P[k]
    m = 0
    while (match(s, /s="[0-9]+"/)) {
      m++
      ST[k, m] = substr(s, RSTART + 3, RLENGTH - 4) + 0
      s = substr(s, RSTART + RLENGTH)
    }
  }

  # 金额(第8列)样式：直接按已有个案的「奇偶 + 值域档」取样，不做归类猜测
  #   hi=≥100万(红)  mid=10~100万(橙)  lo=<10万  str=非数字(如 N/A)
  t = xml
  while (match(t, /<c r="H[0-9]+" s="[0-9]+" t="n"><v>[0-9.]+<\/v>/)) {
    seg = substr(t, RSTART, RLENGTH)
    a = seg; sub(/.*r="H/, "", a); sub(/".*/, "", a); rn = a + 0
    b = seg; sub(/.*s="/, "", b); sub(/".*/, "", b); st = b + 0
    c = seg; sub(/.*<v>/, "", c); sub(/<\/v>.*/, "", c); val = c + 0
    par = (rn - 4) % 2
    bk = (val >= 100) ? "hi" : ((val >= 10) ? "mid" : "lo")
    kk = par "-" bk
    if (!(kk in BS)) BS[kk] = st
    t = substr(t, RSTART + RLENGTH)
  }
  t = xml
  while (match(t, /<c r="H[0-9]+" s="[0-9]+" t="inlineStr">/)) {
    seg = substr(t, RSTART, RLENGTH)
    a = seg; sub(/.*r="H/, "", a); sub(/".*/, "", a); rn = a + 0
    b = seg; sub(/.*s="/, "", b); sub(/".*/, "", b); st = b + 0
    par = (rn - 4) % 2
    if (!(par in SS)) SS[par] = st
    t = substr(t, RSTART + RLENGTH)
  }

  # 输出原有行；第2行统计文本按当日数据重写
  for (k = 1; k <= n - 1; k++) {
    out = P[k]
    if (k == 2) {
      nstats = lastrow - 3 + nnew
      if (STATS_MODE == "all")
        txt = "扫描日期：" DATE "  |  共 " nstats " 条 | 公告 " (N_GG + 0) " 条 | 意向 " (N_YX + 0) " 条 | 结果 " (N_ZB + 0) " 条 | 其他 " (N_WB + 0) " 条  |  更新：" DATE " " TIME
      else
        txt = "扫描日期：" DATE "  |  共 " nstats " 条  |  更新：" DATE " " TIME
      sub(/<t>[^<]*<\/t>/, "<t>" txt "</t>", out)
    }
    printf "%s</row>", out
  }

  # 新行：序号接续，样式对齐奇偶模板，金额按值域上色
  for (i = 1; i <= nnew; i++) {
    r = lastrow + i
    base = ((r - 4) % 2 == 0) ? 4 : 5
    printf "<row r=\"%d\">", r
    par = base - 4
    for (c = 1; c <= 12; c++) {
      col = substr("ABCDEFGHIJKL", c, 1)
      v = V[i, c]
      st = ST[base, c]
      if (c == 1) {
        printf "<c r=\"%s%d\" s=\"%d\" t=\"n\"><v>%d</v></c>", col, r, st, r - 3
      } else if (c == 8 && v ~ /^[0-9]+(\.[0-9]+)?$/) {
        num = v + 0
        bk = (num >= 100) ? "hi" : ((num >= 10) ? "mid" : "lo")
        kk = par "-" bk
        bs = (kk in BS) ? BS[kk] : ST[base, 2]
        printf "<c r=\"%s%d\" s=\"%d\" t=\"n\"><v>%s</v></c>", col, r, bs, v
      } else {
        bs = (c == 8) ? ((par in SS) ? SS[par] : ST[base, 2]) : st
        printf "<c r=\"%s%d\" s=\"%d\" t=\"inlineStr\"><is><t>%s</t></is></c>", col, r, bs, esc(v)
      }
    }
    printf "</row>"
  }

  tail = P[n]
  newlast = lastrow + nnew
  gsub(/<dimension ref="A1:L[0-9]+"/, "<dimension ref=\"A1:L" newlast "\"", tail)
  gsub(/<autoFilter ref="A3:L[0-9]+"/, "<autoFilter ref=\"A3:L" newlast "\"", tail)
  printf "%s", tail
}
function esc(x) {
  gsub(/&/, "\\&amp;", x)
  gsub(/</, "\\&lt;", x)
  gsub(/>/, "\\&gt;", x)
  return x
}
