# 高德/Google 压缩 polyline 解码 —— 纯算术实现（不依赖任何位运算函数，兼容 toybox awk）
# 用法: awk -f polydecode.awk poly.txt   每行一个编码串，输出 "lng,lat" 每点一行
BEGIN {
  T = "?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
  P = 1e6
}
{
  lat = 0; lng = 0; i = 1; n = length($0)
  res = 0; shift = 0; have = 0
  while (i <= n) {
    c = substr($0, i, 1); i++
    b = index(T, c) - 1
    if (b < 0) continue
    low = b % 32
    d = 1; for (k = 0; k < shift; k++) d = d * 2
    res = res + low * d
    shift = shift + 5
    if (b < 32) {
      if (res % 2 == 1) delta = -int(res / 2) - 1
      else delta = int(res / 2)
      if (have == 0) { lat = lat + delta; have = 1 } else { lng = lng + delta; have = 0 }
      res = 0; shift = 0
      if (have == 0) printf "%.6f,%.6f\n", lng / P, lat / P
    }
  }
}
