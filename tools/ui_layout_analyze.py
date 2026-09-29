#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
UI 布局结构分析器
========================
输入：uiautomator dump 的 XML（adb shell uiautomator dump /sdcard/ui.xml）
输出：
    1) 控制台层级树（含文本 / 类名 / bounds / 关键属性）
    2) 布局异常检测（越界、零尺寸、可点击无语义、重叠、层级过深）
    3) 统计摘要

可直接运行：
    python ui_layout_analyze.py --xml adb-current-ui.xml
"""

import argparse
import re
import sys
import xml.etree.ElementTree as ET


def _parse_bounds(s):
    m = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", s or "")
    if not m:
        return 0, 0, 0, 0
    return tuple(int(x) for x in m.groups())


def _short(cls):
    return cls.split(".")[-1] if cls else ""


def build_tree(xml_path):
    tree = ET.parse(xml_path)
    root = tree.getroot()

    def walk(elem, depth=0):
        b = _parse_bounds(elem.get("bounds"))
        node = {
            "depth": depth,
            "cls": elem.get("class", ""),
            "text": (elem.get("text") or "").strip(),
            "rid": elem.get("resource-id", ""),
            "desc": (elem.get("content-desc") or "").strip(),
            "clickable": elem.get("clickable") == "true",
            "scrollable": elem.get("scrollable") == "true",
            "enabled": elem.get("enabled") != "false",
            "bounds": b,
            "children": [],
        }
        for c in elem:
            node["children"].append(walk(c, depth + 1))
        return node

    return walk(root)


def is_meaningful(n):
    """有意义的节点：有文本 / 描述 / 可点击 / 可滚动 / 是常见容器控件。"""
    if n["text"] or n["desc"] or n["clickable"] or n["scrollable"]:
        return True
    cls = _short(n["cls"]).lower()
    if cls in ("textview", "button", "imagebutton", "imageview", "edittext",
              "checkbox", "switch", "floatingactionbutton", "materialbutton",
              "chip", "tabview", "viewpager", "recyclerview", "listview"):
        return True
    return False


def print_tree(n, prefix="", max_depth=99, only_meaningful=True):
    if n["depth"] > max_depth:
        return
    if only_meaningful and not is_meaningful(n):
        # 仍递归子节点，但自己不打印
        for c in n["children"]:
            print_tree(c, prefix, max_depth, only_meaningful)
        return

    x1, y1, x2, y2 = n["bounds"]
    w, h = x2 - x1, y2 - y1
    tag = _short(n["cls"])
    label = n["text"] or n["desc"] or (n["rid"].split("/")[-1] if n["rid"] else "")
    flags = []
    if n["clickable"]:
        flags.append("click")
    if n["scrollable"]:
        flags.append("scroll")
    if not n["enabled"]:
        flags.append("disabled")
    flag_str = f" [{','.join(flags)}]" if flags else ""
    print(f"{prefix}{tag} ({w}x{h} @ {x1},{y1}){flag_str}  {label[:40]}")

    child_prefix = prefix + "  "
    for c in n["children"]:
        print_tree(c, child_prefix, max_depth, only_meaningful)


def analyze(n, img_w, img_h, anomalies, depth=0):
    x1, y1, x2, y2 = n["bounds"]
    w, h = x2 - x1, y2 - y1

    # 1) 越界
    if x1 < -2 or y1 < -2 or x2 > img_w + 2 or y2 > img_h + 2:
        anomalies.append(("越界", f"节点超出屏幕：{_short(n['cls'])} bounds={n['bounds']} (屏幕 {img_w}x{img_h})",
                          n))
    # 2) 零/负尺寸
    if w <= 0 or h <= 0:
        anomalies.append(("零尺寸", f"节点尺寸异常：{_short(n['cls'])} {w}x{h}", n))
    # 3) 可点击但无语义
    if n["clickable"] and not n["text"] and not n["desc"] and not n["rid"]:
        anomalies.append(("可点击无语义", f"可点击但无 text/content-desc/resource-id：{_short(n['cls'])} bounds={n['bounds']}",
                          n))
    # 4) 层级过深
    if depth > 14:
        anomalies.append(("层级过深", f"嵌套深度 {depth}：{_short(n['cls'])}", n))

    for c in n["children"]:
        analyze(c, img_w, img_h, anomalies, depth + 1)


def find_overlaps(nodes, img_w, img_h):
    """检测可点击节点之间的明显重叠（同一父级、矩形相交面积 > 较小者 60%）。"""
    issues = []
    clickable = [n for n in nodes if n["clickable"] and n["bounds"][2] > n["bounds"][0]]
    for i in range(len(clickable)):
        for j in range(i + 1, len(clickable)):
            a, b = clickable[i]["bounds"], clickable[j]["bounds"]
            ix = max(0, min(a[2], b[2]) - max(a[0], b[0]))
            iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
            if ix <= 0 or iy <= 0:
                continue
            inter = ix * iy
            area_a = (a[2] - a[0]) * (a[3] - a[1])
            area_b = (b[2] - b[0]) * (b[3] - b[1])
            smaller = min(area_a, area_b)
            if smaller and inter / smaller > 0.6:
                issues.append(("可点击重叠",
                               f"{_short(clickable[i]['cls'])} 与 {_short(clickable[j]['cls'])} 重叠 {inter//1}px",
                               clickable[i]))
    return issues


def flatten(n, out):
    out.append(n)
    for c in n["children"]:
        flatten(c, out)


def main():
    ap = argparse.ArgumentParser(description="UI 布局结构分析器")
    ap.add_argument("--xml", default="adb-current-ui.xml")
    ap.add_argument("--screenshot", default=None, help="可选截图，用于推断屏幕尺寸")
    ap.add_argument("--max-depth", type=int, default=99)
    ap.add_argument("--all", action="store_true", help="打印全部节点（含无意义容器）")
    args = ap.parse_args()

    tree = build_tree(args.xml)
    flat = []
    flatten(tree, flat)

    # 屏幕尺寸：取根节点 bounds 或截图
    img_w, img_h = 1080, 2400
    if args.screenshot:
        try:
            from PIL import Image
            im = Image.open(args.screenshot)
            img_w, img_h = im.size
        except Exception:
            pass
    else:
        rb = tree["bounds"]
        if rb[2] > rb[0]:
            img_w, img_h = rb[2], rb[3]

    print(f"\n=== UI 布局结构分析 ===")
    print(f"XML 节点总数：{len(flat)}")
    print(f"屏幕尺寸：{img_w}x{img_h}\n")

    print("--- 层级树（仅有意义节点）---")
    print_tree(tree, max_depth=args.max_depth, only_meaningful=not args.all)
    print()

    anomalies = []
    analyze(tree, img_w, img_h, anomalies)
    overlaps = find_overlaps(flat, img_w, img_h)
    anomalies.extend(overlaps)

    print(f"--- 异常检测：共 {len(anomalies)} 项 ---")
    if not anomalies:
        print("  未发现明显布局异常。")
    else:
        for kind, msg, node in anomalies:
            print(f"  [{kind}] {msg}")
    print()

    # 文本清单
    texts = [(n["text"], n["desc"], _short(n["cls"])) for n in flat if n["text"] or n["desc"]]
    print(f"--- 可见文本 / 描述（{len(texts)} 项）---")
    for t, d, cls in texts:
        print(f"  {cls}: {t or d}")
    print()

    # 可点击控件清单
    clicks = [n for n in flat if n["clickable"]]
    print(f"--- 可点击控件（{len(clicks)} 项）---")
    for n in clicks:
        x1, y1, x2, y2 = n["bounds"]
        label = n["text"] or n["desc"] or n["rid"].split("/")[-1] or _short(n["cls"])
        print(f"  {_short(n['cls'])} @ ({x1},{y1})-({x2},{y2}): {label[:30]}")


if __name__ == "__main__":
    main()
