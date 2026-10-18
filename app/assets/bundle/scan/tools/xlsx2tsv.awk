# 把 sheet XML 的第4行起数据行导出为 TSV（12列），并把 &#NNN; 实体还原成 UTF-8 原文
# 用法: awk -f xlsx2tsv.awk < sheet1.xml > 库.tsv
NR == 1 { xml = $0 }
END {
  n = split(xml, P, /<\/row>/)
  for (k = 4; k <= n - 1; k++) {
    for (c = 1; c <= 12; c++) col[c] = ""
    s = P[k]
    sub(/^[^>]*>/, "", s)
    while (length(s) > 0) {
      i = index(s, "</c>")
      if (i == 0) break
      cell = substr(s, 1, i - 1)
      s = substr(s, i + 4)
      if (cell !~ /r="[A-Z]+[0-9]+"/) continue
      a = cell
      sub(/.*r="/, "", a)
      sub(/".*/, "", a)
      letter = a
      gsub(/[0-9]/, "", letter)
      ci = index("ABCDEFGHIJKL", letter)
      if (ci == 0) continue
      if (cell ~ /t="inlineStr"/) {
        v = cell
        sub(/.*<t>/, "", v)
        sub(/<\/t>.*/, "", v)
        col[ci] = decode(v)
      } else if (cell ~ /<v>/) {
        v = cell
        sub(/.*<v>/, "", v)
        sub(/<\/v>.*/, "", v)
        col[ci] = v
      }
    }
    line = col[1]
    for (c = 2; c <= 12; c++) line = line "\t" col[c]
    print line
  }
}
function decode(x,   out, i, j, cp) {
  out = ""
  while ((i = index(x, "&#")) > 0) {
    out = out substr(x, 1, i - 1)
    x = substr(x, i + 2)
    j = index(x, ";")
    if (j == 0) { out = out "&#"; continue }
    cp = substr(x, 1, j - 1) + 0
    x = substr(x, j + 1)
    out = out utf8(cp)
  }
  out = out x
  gsub(/&lt;/, "<", out)
  gsub(/&gt;/, ">", out)
  gsub(/&amp;/, "\\&", out)
  return out
}
function utf8(cp) {
  if (cp < 128) return sprintf("%c", cp)
  if (cp < 2048) return sprintf("%c%c", 192 + int(cp / 64), 128 + (cp % 64))
  if (cp < 65536) return sprintf("%c%c%c", 224 + int(cp / 4096), 128 + int(cp / 64) % 64, 128 + (cp % 64))
  return sprintf("%c%c%c%c", 240 + int(cp / 262144), 128 + int(cp / 4096) % 64, 128 + int(cp / 64) % 64, 128 + (cp % 64))
}
