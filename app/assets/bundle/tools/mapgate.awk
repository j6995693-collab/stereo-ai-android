# mapgate.awk —— 地图成品坐标门（§二十二 假路线 + §二十八 国界）
# 纯 awk，不依赖位运算函数，兼容 toybox awk / macOS awk / mawk
# 用法: awk -v MINPTS=50 -f mapgate.awk 成品.html
#
# 判据全部来自对真成品的实测标定，不是猜的。
#
# 成品有三种数据形态，本门按**内容**分类（不靠行位置、不靠变量名）：
#   A 内联路线  var day1Trails=[{path:[[lng,lat],...],color:"#..",label:"🚗 .. 234km"}]
#   B 变量路线  const dayRoutes = { D1: [[lng,lat],...] };  path: dayRoutes[k].map(...)
#   C 标注点阵  var pois=[["🏁 南大门",lng,lat,"#666",0]] / ["类型","名","址",lng,lat,"#c"]
#   判定方法：看 [[ 块的第一个内层元素 —— [数字,数字] → 路线；"字符串" → 标注点阵
#
# 判据：
#   FAIL ① 明文分号坐标串 "lng,lat;lng,lat"（§二十二 明令禁止的顶替形态）
#        ② 越界点（§二十八：73<=经度<=136 且 3<=纬度<=55）—— 含经纬颠倒
#        ③ 道路段点数 < MINPTS 且起终点直线距离 >= MINSPAN km（§二十二 抽稀）
#   放行：索道🚡/小火车🚂/步行🚶/缆车🚠/渡轮⛴ 等**本来就是直线**的段，2 点即正确
#
# 2026-10-08 独立验证查出并修复的 6 个缺陷（回归样本见 /tmp/gate_test）：
#   C-1 切 [[ 块时首末括号被吃掉 → 每段点数少算 2，50/51 点合法长段被误杀
#   C-2 同一根因导致 2~3 点长道路反被放行（span 因 pc<2 未计算）
#   C-3 无折线文件的越界坐标走 N/A 分支 → 越界被吞 → exit 0
#   C-4 兜底正则第二字段限 {1,2} 位 → 经纬颠倒的 3 位经度抓不到（正是 §二十八 事故模式）
#       分号正则不容空格 → "a,b; c,d" 漏检
#   C-5 逐点循环里反复 substr 整个块 → O(n^2)（40k 点 4.89s）→ 改单趟 split
#   C-6 第三方压缩库（ECharts 等）里的 [[…]] 被当折线 → 抠出假坐标判越界、好文件被误杀
#       → 折线块必须每个元素都是坐标对才认（见下面 isroute 校验）
# 2026-10-08 第二轮独立验证又查出并修复的 3 个缺陷：
#   C-7 [[ 之间/坐标之间换行缩进（多行成品）时整段漏检 → 三处匹配都容忍空白
#   C-8 C-6 引入的 "if (pc<2) isroute=0" 使"空段/单点"分支变死代码、单点假路线漏报 → 改 pc<1
#   C-9 全量兜底扫描仍是 O(n^2)（95KB 0.07s，同坐标加 3MB 填充变 2.68s）→ 改 gsub+split 单趟
#       并新增低精度兜底网（1~3 位小数），堵住"块被判非路线 + 坐标只有 1 位小数"双漏越界的通道
#   C-10 纯整数数组（图表序列 `[[8,8,8],[6,6,6],[1,0,0]]`）被当折线判越界假 FAIL
#        → 整块一个小数点都没有就不算路线（真成品经纬度必带小数）
BEGIN { if (MINPTS == "") MINPTS = 50; if (MINSPAN == "") MINSPAN = 10 }
{ all = all $0 "\n" }
function hav_km(lat1, lng1, lat2, lng2,   p, dlat, dlng, a, c) {
  p = 3.14159265358979 / 180
  dlat = (lat2 - lat1) * p; dlng = (lng2 - lng1) * p
  a = sin(dlat / 2); a = a * a
  c = sin(dlng / 2); a = a + cos(lat1 * p) * cos(lat2 * p) * c * c
  if (a < 0) a = 0; if (a > 1) a = 1
  return 12742 * atan2(sqrt(a), sqrt(1 - a))
}
END {
  # ---------- C 标注点阵：["名称", lng, lat, ...] ----------
  t = all
  while (match(t, /\[[ \t]*"[^"]*"[ \t]*,[ \t]*-?[0-9]+(\.[0-9]+)?[ \t]*,[ \t]*-?[0-9]+(\.[0-9]+)?/)) {
    m = substr(t, RSTART, RLENGTH)
    sub(/^\[[ \t]*"[^"]*"[ \t]*,[ \t]*/, "", m)
    split(m, q, ",")
    marks++
    t = substr(t, RSTART + RLENGTH)
  }

  # ---------- A/B 折线：按 [[ 块内容分类 ----------
  # C-7：`[[` 两个括号之间、坐标之间都可能换行/缩进（成品允许多行格式，
  # CLAUDE_behaviors 也要求"每条记录独占一行"）。旧版写死 `match(t,/\[\[/)` +
  # `index(after,"]]")`，多行成品整段漏掉 → 真缺陷漏报。三处都要容忍空白。
  t = all; seg = 0
  while (match(t, /\[[ \t\r\n]*\[/)) {
    rs = RSTART; rl = RLENGTH
    after = substr(t, rs + rl)
    if (match(after, /\][ \t\r\n]*\]/)) e = RSTART + RLENGTH - 1
    else e = length(after)
    # 关键：补回被当成 [[ ]] 吃掉的首括号 —— 否则首点匹配不到
    block = "[" substr(after, 1, e)
    blen = e                         # 从 after 里吃掉的字符数
    gsub(/[\r\n\t]/, " ", block)     # 压平空白，下面按空格分隔解析
    isroute = (block ~ /^ *\[ *-?[0-9]/) ? 1 : 0
    # C-6：必须**每个元素都是坐标对**才算路线。
    # 否则第三方压缩库（ECharts 等，代码里有 [[ 和 ],[ ）会被当成折线，
    # 从代码里抠出"坐标"判越界 → 好文件被误杀（正是索道/小火车那类误判的病根）。
    # 元素允许 2 或 3 个数值字段（[lng,lat] 或 [lng,lat,alt]），多于 3 或含非数字才算代码垃圾。
    if (isroute) {
      np = split(block, PP, /\],[ \t]*\[/)
      pc = 0; hasdot = 0
      for (i = 1; i <= np; i++) {
        x = PP[i]; gsub(/[\[\]]/, "", x)
        if (x == "") continue
        if (x !~ /^ *-?[0-9]+(\.[0-9]+)? *, *-?[0-9]+(\.[0-9]+)?( *, *-?[0-9]+(\.[0-9]+)?)? *$/) { isroute = 0; break }
        if (x ~ /\./) hasdot = 1
        split(x, ab, ",")
        CL[pc] = ab[1] + 0; CT[pc] = ab[2] + 0; pc++
      }
      # C-10：整块坐标**一个小数点都没有**就不是路线 —— 真成品的经纬度必带小数，
      # 纯整数数组是图表序列之类（`[[8,8,8],[6,6,6],[1,0,0]]`），
      # 不挡掉会被判成"越界点"假 FAIL。
      if (!hasdot) isroute = 0
      # C-8：pc<2 不能把段丢掉 —— 单点/空段正是 §二十二 要判的"假路线"，
      # 丢掉它会让 verdict 里的"空段/单点"分支变成死代码、真缺陷直接漏报（已验证）。
      # 只有**一个坐标都没解出来**（纯垃圾）才不算路线。
      if (pc < 1) isroute = 0
    }
    if (isroute) {
      key = "?"
      if (rs > 1) {
        head = substr(t, 1, rs - 1)
        if (match(head, /[A-Za-z_][A-Za-z0-9_]*[ \t]*:[ \t]*$/)) {
          key = substr(head, RSTART, RLENGTH); gsub(/[ \t:]/, "", key)
        }
      }
      tail = substr(t, rs + rl + blen)
      cut = index(tail, "}"); if (cut == 0 || cut > 200) cut = 200
      win = substr(tail, 1, cut)
      label = ""
      if (match(win, /label: *"[^"]*"/)) {
        label = substr(win, RSTART, RLENGTH)
        sub(/label: *"/, "", label); sub(/"$/, "", label)
      }
      # 计点：已在上面 C-6 校验时解析进 CL/CT，这里直接取（O(n) 单趟）
      b = 0; flng = ""; flat = ""; llng = ""; llat = ""
      for (i = 0; i < pc; i++) {
        lng = CL[i]; lat = CT[i]
        if (i == 0) { flng = lng; flat = lat }
        llng = lng; llat = lat
        pts++
        if (lng < 73 || lng > 136 || lat < 3 || lat > 55) { bad++; b++ }
      }
      span = 0
      if (pc >= 2) span = hav_km(flat, flng, llat, llng)
      nonroad = (label ~ /🚡|🚠|🚂|🚶|🥾|⛴|🚢|索道|小火车|步行|栈道|缆车/) ? 1 : 0
      isroad = nonroad ? 0 : 1
      if (b > 0) isroad = 0
      seg++
      # 判据：越界 > 道路段抽稀(点数不足且里程够长) > 空段/单点 > 直线(非道路,可) > OK
      if (b > 0) v = "越界"
      else if (isroad && pc < 2) v = "空段/单点"
      else if (isroad && pc < MINPTS && span >= MINSPAN) v = "道路段抽稀"
      else if (pc <= 2) v = "直线(非道路,可)"
      else v = "OK"
      if (b > 0 || (isroad && pc < 2) || (isroad && pc < MINPTS && span >= MINSPAN)) badseg++
      nm = (label != "") ? label : key
      printf "SEG\t%d\t%d\t%s\t%s\t%.1fkm\t%s\n", seg, pc, isroad ? "道路" : "非道路", v, span, nm
    }
    t = substr(t, rs + rl + blen)
  }

  # ---------- 压缩编码串 path:"..." （合法来源，需另行解码校验） ----------
  t = all
  while (match(t, /path: *"[^"]+"/)) { enc++; t = substr(t, RSTART + RLENGTH) }

  # ---------- 全量坐标兜底扫描（格式无关的国界网） ----------
  # 两侧都允许 1~3 位整数 + >=4 位小数：坐标必是 5~6 位小数，
  # 这样既抓得住 5~6 位小数的坐标对，也抓得住**颠倒**的经纬度顺序
  # （颠倒正是 §二十八 记载的"定位点跑到非洲"事故模式，旧版第二字段限 1~2 位会漏）
  # C-9：改成 O(n) —— gsub 一次性在每个匹配前插换行（返回值就是匹配数），再 split 逐行判。
  # 旧写法 while(match)+substr 每轮拷贝整个剩余串，耗时 ∝ 匹配数 × 文件长度：
  # 同一份 5000 个坐标，95KB 是 0.07s，加 3MB 无坐标填充就变 2.68s（40×）。
  t = all
  raw = gsub(/[0-9]{1,3}\.[0-9]{4,} *, *[0-9]{1,3}\.[0-9]{4,}/, "\n&", t)
  nl = split(t, RAWL, "\n")
  for (i = 1; i <= nl; i++) {
    s = RAWL[i]
    if (s !~ /^[0-9]{1,3}\.[0-9]{4,} *, *[0-9]{1,3}\.[0-9]{4,}/) continue
    match(s, /^[0-9]{1,3}\.[0-9]{4,} *, *[0-9]{1,3}\.[0-9]{4,}/)
    m = substr(s, RSTART, RLENGTH)
    k = index(m, ",")
    glng = substr(m, 1, k - 1) + 0; glat = substr(m, k + 1) + 0
    if (glng < 73 || glng > 136 || glat < 3 || glat > 55) { rawbad++; if (rawbad <= 5) printf "BAD\t%s\n", m }
  }

  # ---------- 低精度兜底（1~3 位小数，同样 O(n)） ----------
  # 补 C-6 的副作用：若坐标写成 1 位小数且所在块被判为非路线，
  # 段级检查整段跳过、严格网又要求 >=4 位小数 → 越界点两道都抓不到（已验证的漏报通道）。
  # 这里只对**越界**判失败；界内低精度对属精度问题，不在国界门范围。
  t = all
  nlo = gsub(/[0-9]{1,3}\.[0-9]{1,3} *, *[0-9]{1,3}\.[0-9]{1,3}/, "\n&", t)
  nl2 = split(t, LOWL, "\n")
  for (i = 1; i <= nl2; i++) {
    s = LOWL[i]
    if (s !~ /^[0-9]{1,3}\.[0-9]{1,3} *, *[0-9]{1,3}\.[0-9]{1,3}/) continue
    match(s, /^[0-9]{1,3}\.[0-9]{1,3} *, *[0-9]{1,3}\.[0-9]{1,3}/)
    m = substr(s, RSTART, RLENGTH)
    k = index(m, ",")
    glng = substr(m, 1, k - 1) + 0; glat = substr(m, k + 1) + 0
    loose++
    if (glng < 73 || glng > 136 || glat < 3 || glat > 55) { loosebad++; if (loosebad <= 5) printf "BADLOW\t%s\n", m }
  }

  # ---------- 明文分号坐标串 ----------
  t = all
  while (match(t, /-?[0-9]+(\.[0-9]+)?[ \t]*,[ \t]*-?[0-9]+(\.[0-9]+)?[ \t]*;[ \t]*-?[0-9]+(\.[0-9]+)?[ \t]*,[ \t]*-?[0-9]+(\.[0-9]+)?/)) {
    semi++
    if (semi <= 3) { s = substr(t, RSTART, RLENGTH); gsub(/^[ \t]+|[ \t]+$/, "", s); printf "SEMI\t%s\n", s }
    t = substr(t, RSTART + RLENGTH)
  }

  printf "SUM\t折线段数\t%d\n", seg
  printf "SUM\t标注点数\t%d\n", marks
  printf "SUM\t编码串数\t%d\n", enc
  printf "SUM\t明文分号坐标串\t%d\n", semi
  printf "SUM\t坐标点总数\t%d\n", pts
  printf "SUM\t越界点数\t%d\n", bad
  printf "SUM\t未过门段数\t%d\n", badseg
  printf "SUM\t全量坐标对\t%d\n", raw
  printf "SUM\t全量越界数\t%d\n", rawbad
  printf "SUM\t低精度坐标对\t%d\n", loose
  printf "SUM\t低精度越界数\t%d\n", loosebad
  # 判定链：先看硬违规，再看"根本没有可判内容"；N/A 只在**确无越界**时才出，
  # 这样 mapgate.sh 把 N/A 当通过才是安全的（旧版 N/A 会吞掉 rawbad）
  if (semi > 0 || rawbad > 0 || loosebad > 0 || badseg > 0) print "SUM\t判定\tFAIL"
  else if (seg + enc + raw + loose == 0) print "SUM\t判定\tFAIL(未找到任何坐标)"
  else if (seg + enc == 0) printf "SUM\t判定\tN/A(无折线,仅%d个坐标对/标注点,均未越界)\n", raw + loose
  else print "SUM\t判定\tPASS"
}
