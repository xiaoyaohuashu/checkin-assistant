#!/usr/bin/env python3
"""生成 app/src/main/resources/com/checkinassistant/domain/china_region.bin（v2.2.26）。

数据来源：OpenStreetMap 行政边界，© OpenStreetMap contributors，ODbL 许可
  （https://www.openstreetmap.org/copyright）。经 Nominatim 下载，2026-11-14：
  https://nominatim.openstreetmap.org/lookup?osm_ids=R<编号>&format=geojson&polygon_geojson=1
    R270056   中国（含香港、澳门，含 12 海里领海，不含台澎金马）
    R913110   香港
    R1867188  澳门
  原始文件不进仓库（3.7 MB），存放在 /root/checkin-refactor-verify/geo-v2226/，丢了按上面的地址重新下载。
  OSM 数据会随编辑变化，重新下载后的结果可能和这次略有不同。

处理：每个环用 Douglas-Peucker 简化到 0.0005 度（约 50 米），坐标存成 1e-6 度的整数。

输出格式（大端）：
  int 'CNRG'、int 版本=1
  3 个区域，依次是 中国、香港、澳门：
    int 环数
    每个环：int 点数 n；int minLat, maxLat, minLng, maxLng；n 对 (int lat, int lng)

用法：python3 scripts/build_china_region.py <放三个 osm_*.json 的目录> <输出 bin>
脚本会打印三个输入文件和输出文件的 sha256，记在 docs/fix-v2.2.26/详细报告.txt 里。
"""
import hashlib
import json
import math
import struct
import sys

RELATIONS = (270056, 913110, 1867188)
TOLERANCE = 0.0005


def load(path):
    g = json.load(open(path, encoding="utf-8"))["features"][0]["geometry"]
    return g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]


def simplify_line(pts, tol):
    n = len(pts)
    if n < 5:
        return pts
    keep = [False] * n
    keep[0] = keep[-1] = True
    stack = [(0, n - 1)]
    while stack:
        a, b = stack.pop()
        if b <= a + 1:
            continue
        ax, ay = pts[a]
        bx, by = pts[b]
        dx, dy = bx - ax, by - ay
        length2 = dx * dx + dy * dy
        worst, worst_i = -1.0, -1
        for i in range(a + 1, b):
            px, py = pts[i]
            if length2 == 0:
                d = math.hypot(px - ax, py - ay)
            else:
                t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / length2))
                d = math.hypot(px - ax - t * dx, py - ay - t * dy)
            if d > worst:
                worst, worst_i = d, i
        if worst > tol:
            keep[worst_i] = True
            stack.append((a, worst_i))
            stack.append((worst_i, b))
    return [p for p, k in zip(pts, keep) if k]


def rings_of(polys, tol):
    """所有多边形的所有环（外环和洞一视同仁：判断时按奇偶规则跨环累加）。"""
    out = []
    for poly in polys:
        for ring in poly:
            pts = [(p[0], p[1]) for p in ring]
            mid = len(pts) // 2
            # 闭合环首尾同点：从中间切成两段分别简化
            s = simplify_line(pts[: mid + 1], tol) + simplify_line(pts[mid:], tol)[1:]
            if len(s) >= 4:
                out.append(s)
    return out


def sha256(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def main():
    src, dst = sys.argv[1], sys.argv[2]
    buf = bytearray(struct.pack(">4si", b"CNRG", 1))
    for rel in RELATIONS:
        path = f"{src}/osm_{rel}.json"
        rings = rings_of(load(path), TOLERANCE)
        buf += struct.pack(">i", len(rings))
        for ring in rings:
            lat = [round(p[1] * 1_000_000) for p in ring]
            lng = [round(p[0] * 1_000_000) for p in ring]
            buf += struct.pack(">i4i", len(ring), min(lat), max(lat), min(lng), max(lng))
            for a, o in zip(lat, lng):
                buf += struct.pack(">2i", a, o)
        print(f"R{rel}: {len(rings)} 个环, {sum(len(r) for r in rings)} 个点, 输入 sha256 {sha256(path)}")
    open(dst, "wb").write(bytes(buf))
    print(f"输出 {dst}: {len(buf)} 字节, sha256 {sha256(dst)}")


if __name__ == "__main__":
    main()
