#!/system/bin/sh
# zipw.sh —— 纯 toybox 工具链把目录打包成 zip（deflate 压缩）
# 依赖：find sed sort wc head tail gzip printf mkdir rm cat
# 用法：sh zipw.sh <源目录> <输出.zip>
# 原理：gzip 尾部 8 字节 = CRC32(小端) + 未压缩长度(小端)，正是 zip 需要的字段；
#       gzip 去掉 10 字节头、去掉 8 字节尾，剩下的就是标准 raw deflate 流。
set -e
export PATH=/system/bin:/system/xbin:$PATH
BASE=$(pwd)
SRC=$(cd "$1" && pwd)
case "$2" in
  /*) OUT=$2 ;;
  *) OUT="$BASE/$2" ;;
esac
W="$OUT.work"
rm -rf "$W"
mkdir -p "$W"

DOStime=32768
DOSdate=23880

le16() { v=$(($1)); printf "\\x$(printf %02x $((v & 255)))\\x$(printf %02x $(((v >> 8) & 255)))"; }
le32() { v=$(($1)); printf "\\x$(printf %02x $((v & 255)))\\x$(printf %02x $(((v >> 8) & 255)))\\x$(printf %02x $(((v >> 16) & 255)))\\x$(printf %02x $(((v >> 24) & 255)))"; }

: > "$OUT"
: > "$W/cd"

cd "$SRC"
find . -type f | sed 's|^\./||' | LC_ALL=C sort > "$W/list"

n=0
off=0
while IFS= read -r name; do
  gz="$W/g.gz"
  gzip -c "$name" > "$gz"
  gzsz=$(wc -c < "$gz")
  cs=$((gzsz - 18))
  nl=${#name}

  printf 'PK\x03\x04' >> "$OUT"
  le16 20 >> "$OUT"
  le16 0 >> "$OUT"
  le16 8 >> "$OUT"
  le16 $DOStime >> "$OUT"
  le16 $DOSdate >> "$OUT"
  tail -c 8 "$gz" | head -c 4 >> "$OUT"
  le32 $cs >> "$OUT"
  tail -c 8 "$gz" | tail -c 4 >> "$OUT"
  le16 $nl >> "$OUT"
  le16 0 >> "$OUT"
  printf '%s' "$name" >> "$OUT"
  tail -c +11 "$gz" | head -c "$cs" >> "$OUT"

  printf 'PK\x01\x02' >> "$W/cd"
  le16 20 >> "$W/cd"
  le16 20 >> "$W/cd"
  le16 0 >> "$W/cd"
  le16 8 >> "$W/cd"
  le16 $DOStime >> "$W/cd"
  le16 $DOSdate >> "$W/cd"
  tail -c 8 "$gz" | head -c 4 >> "$W/cd"
  le32 $cs >> "$W/cd"
  tail -c 8 "$gz" | tail -c 4 >> "$W/cd"
  le16 $nl >> "$W/cd"
  le16 0 >> "$W/cd"
  le16 0 >> "$W/cd"
  le16 0 >> "$W/cd"
  le16 0 >> "$W/cd"
  le32 0 >> "$W/cd"
  le32 $off >> "$W/cd"
  printf '%s' "$name" >> "$W/cd"

  off=$((off + 30 + nl + cs))
  n=$((n + 1))
done < "$W/list"

cdsize=$(wc -c < "$W/cd")
cat "$W/cd" >> "$OUT"
printf 'PK\x05\x06' >> "$OUT"
le16 0 >> "$OUT"
le16 0 >> "$OUT"
le16 $n >> "$OUT"
le16 $n >> "$OUT"
le32 $cdsize >> "$OUT"
le32 $off >> "$OUT"
le16 0 >> "$OUT"

rm -rf "$W"
echo "OK entries=$n cd_size=$cdsize cd_offset=$off out_size=$(wc -c < "$OUT")"
