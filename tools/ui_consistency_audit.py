#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
UI 风格统一性自动审核脚本
====================================
输入：
    1) adb 截图（PNG）
    2) uiautomator 层级 dump（XML）
输出：
    1) 控制台摘要
    2) JSON 报告（--out/audit_report.json）
    3) 标注图（--out/audit_overlay.png）

审核维度：
    - 颜色统一性（Color）：主背景/卡片色/强调色是否一致，突兀色块检测
    - 字体层级（Typography）：同级文字高度一致性、标题>正文层级
    - 间距一致性（Spacing）：行/块之间间隙是否统一、左右边距是否对齐
    - 组件样式（Components）：卡片圆角是否统一、按钮形状是否一致

可直接运行：
    python ui_consistency_audit.py --capture --out ./audit
或基于已有 adb 产物：
    python ui_consistency_audit.py --screenshot adb-audit-ui.png --xml adb-audit-ui.xml --out ./audit
"""

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import asdict, dataclass, field
from typing import List, Optional, Tuple

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

# --------------------------- 常量 ---------------------------

# 系统 UI 区域（顶部状态栏 / 底部手势条），分析时忽略这些节点
STATUS_BAR_H = 90          # px @ 1080p，自适应会按截图高度比例缩放
NAV_BAR_H = 70

# 颜色距离阈值（CIEDE2000 近似，LAB 空间）
COLOR_ANOMALY_THRESHOLD = 25.0   # 节点色与最近主题色差距超过此值 -> 异常
PRIMARY_SAT_MIN = 0.35           # 主色饱和度下限
ACCENT_LAB_THRESHOLD = 30.0      # 语义强调色允许的偏离

# 间距阈值：节点间垂直间隙偏离众数 > 此比例即异常
SPACING_DEVIATION_RATIO = 0.45

# 字号阈值：同级文本高度偏离中位数 > 此比例即异常
FONT_DEVIATION_RATIO = 0.40

# 最小有效节点面积
MIN_NODE_AREA = 400


def _sh(cmd: List[str]) -> bytes:
    """执行命令并返回 stdout。"""
    return subprocess.run(cmd, capture_output=True, check=True).stdout


# --------------------------- 数据模型 ---------------------------

@dataclass
class Node:
    index: str
    text: str
    resource_id: str
    cls: str
    package: str
    content_desc: str
    bounds: Tuple[int, int, int, int]  # x1, y1, x2, y2
    clickable: bool
    scrollable: bool
    enabled: bool
    children: List["Node"] = field(default_factory=list)

    @property
    def area(self) -> int:
        return max(0, (self.x2 - self.x1) * (self.y2 - self.y1))

    @property
    def cx(self) -> int:
        return (self.x1 + self.x2) // 2

    @property
    def cy(self) -> int:
        return (self.y1 + self.y2) // 2

    @property
    def width(self) -> int:
        return self.x2 - self.x1

    @property
    def height(self) -> int:
        return self.y2 - self.y1

    x1 = property(lambda s: s.bounds[0])
    y1 = property(lambda s: s.bounds[1])
    x2 = property(lambda s: s.bounds[2])
    y2 = property(lambda s: s.bounds[3])


@dataclass
class Issue:
    dimension: str
    severity: str   # error / warning / info
    message: str
    node: Optional[dict] = None
    suggestion: str = ""

    def to_dict(self):
        d = {"dimension": self.dimension, "severity": self.severity,
             "message": self.message, "suggestion": self.suggestion}
        if self.node:
            d["node"] = self.node
        return d


# --------------------------- 解析 uiautomator XML ---------------------------

def _parse_bounds(s: str) -> Tuple[int, int, int, int]:
    m = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", s)
    if not m:
        return 0, 0, 0, 0
    return tuple(int(x) for x in m.groups())


def _element_to_node(elem) -> Node:
    return Node(
        index=elem.get("index", ""),
        text=elem.get("text", "").strip(),
        resource_id=elem.get("resource-id", ""),
        cls=elem.get("class", ""),
        package=elem.get("package", ""),
        content_desc=elem.get("content-desc", "").strip(),
        bounds=_parse_bounds(elem.get("bounds", "")),
        clickable=elem.get("clickable") == "true",
        scrollable=elem.get("scrollable") == "true",
        enabled=elem.get("enabled") == "true",
    )


def parse_uiautomator(xml_path: str) -> List[Node]:
    """解析 uiautomator dump，返回所有节点（含中间容器）。"""
    import xml.etree.ElementTree as ET
    tree = ET.parse(xml_path)
    root = tree.getroot()

    def walk(elem, parent: Optional[Node] = None):
        node = _element_to_node(elem)
        if parent is not None:
            parent.children.append(node)
        for child in elem:
            walk(child, node)
        return node

    tree_root = walk(root)
    # 拍平成列表，便于后续处理
    flat = []

    def collect(n: Node):
        flat.append(n)
        for c in n.children:
            collect(c)

    collect(tree_root)
    return flat


# --------------------------- 预处理 ---------------------------

def filter_nodes(nodes: List[Node], img_h: int, img_w: int) -> List[Node]:
    """过滤系统栏、过小、不可见节点，返回有效节点。"""
    sb = int(STATUS_BAR_H * img_h / 2400)
    nb = int(NAV_BAR_H * img_h / 2400)
    valid = []
    for n in nodes:
        if n.area < MIN_NODE_AREA:
            continue
        if n.y2 <= sb:          # 顶部状态栏
            continue
        if n.y1 >= img_h - nb:  # 底部手势条
            continue
        # 排除全屏根节点
        if n.x1 == 0 and n.y1 == 0 and n.x2 == img_w and n.y2 == img_h:
            continue
        valid.append(n)
    return valid


def sample_node_color(node: Node, img_bgr: np.ndarray, margin: float = 0.15) -> np.ndarray:
    """采样节点中心区域颜色（BGR），避开边缘抗锯齿。"""
    h, w = img_bgr.shape[:2]
    x1 = max(0, int(node.x1 + node.width * margin))
    y1 = max(0, int(node.y1 + node.height * margin))
    x2 = min(w, int(node.x2 - node.width * margin))
    y2 = min(h, int(node.y2 - node.height * margin))
    if x2 <= x1 or y2 <= y1:
        x1, y1, x2, y2 = node.x1, node.y1, node.x2, node.y2
    patch = img_bgr[y1:y2, x1:x2]
    # 用中位数，比均值更抗文字/噪点
    return np.median(patch.reshape(-1, 3), axis=0).astype(np.uint8)


def bgr_to_lab(bgr: np.ndarray) -> np.ndarray:
    return cv2.cvtColor(bgr.reshape(1, 1, 3), cv2.COLOR_BGR2LAB).reshape(3)


def lab_distance(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.linalg.norm(a.astype(np.float32) - b.astype(np.float32)))


# --------------------------- 颜色分析 ---------------------------

def estimate_theme_palette(img_bgr: np.ndarray, n_colors: int = 6) -> Tuple[np.ndarray, dict]:
    """
    用 k-means 提取主题色盘。
    返回 (palette_bgr, meta)，其中 meta 包含每个颜色的标签。
    """
    # 降采样提速
    small = cv2.resize(img_bgr, (360, 800), interpolation=cv2.INTER_AREA)
    pixels = small.reshape(-1, 3).astype(np.float32)

    criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 20, 1.0)
    _, labels, centers = cv2.kmeans(pixels, n_colors, None, criteria, 5,
                                    cv2.KMEANS_PP_CENTERS)
    counts = np.bincount(labels.flatten(), minlength=n_colors)
    centers = centers.astype(np.uint8)

    # 按像素数排序
    order = np.argsort(-counts)
    palette_bgr = centers[order]

    # 简单分类
    labs = np.array([bgr_to_lab(c) for c in palette_bgr])
    # L 通道 0-255 范围内，越大越亮
    lightness = labs[:, 0]
    saturation = np.array([cv2.cvtColor(c.reshape(1, 1, 3), cv2.COLOR_BGR2HSV).reshape(3)[1]
                           for c in palette_bgr]) / 255.0

    meta = {}
    used = set()

    # 1) 背景色 = 像素最多、偏亮、饱和度低
    bg_candidates = [i for i in order if lightness[i] > 80 and saturation[i] < 0.25]
    bg = bg_candidates[0] if bg_candidates else order[0]
    meta["background"] = bg
    used.add(bg)

    # 2) 卡片/表面色 = 次多、亮、低饱和
    surface_candidates = [i for i in order if i not in used and lightness[i] > 100 and saturation[i] < 0.35]
    surface = surface_candidates[0] if surface_candidates else None
    if surface is not None:
        meta["surface"] = surface
        used.add(surface)

    # 3) 主强调色 = 饱和度高、亮度中等
    primary_candidates = [i for i in order if i not in used and saturation[i] > PRIMARY_SAT_MIN and 60 < lightness[i] < 220]
    primary = primary_candidates[0] if primary_candidates else None
    if primary is not None:
        meta["primary"] = primary
        used.add(primary)

    # 4) 文本/深色 = 亮度低
    text_candidates = [i for i in order if i not in used and lightness[i] < 100]
    if text_candidates:
        meta["text"] = text_candidates[0]
        used.add(text_candidates[0])

    return palette_bgr, meta


def analyze_color(nodes: List[Node], img_bgr: np.ndarray) -> dict:
    """颜色统一性分析。"""
    palette, meta = estimate_theme_palette(img_bgr)
    palette_lab = np.array([bgr_to_lab(c) for c in palette])

    # 给节点分配主题色
    node_assignments = []
    anomalies = []
    primary_count = 0

    for n in nodes:
        color_bgr = sample_node_color(n, img_bgr)
        color_lab = bgr_to_lab(color_bgr)
        dists = [lab_distance(color_lab, p) for p in palette_lab]
        nearest_idx = int(np.argmin(dists))
        dist = dists[nearest_idx]

        role = "unknown"
        if meta.get("primary") == nearest_idx:
            role = "primary"
            primary_count += 1
        elif meta.get("surface") == nearest_idx:
            role = "surface"
        elif meta.get("background") == nearest_idx:
            role = "background"
        elif meta.get("text") == nearest_idx:
            role = "text"

        node_assignments.append({
            "node": _node_summary(n),
            "color_bgr": color_bgr.tolist(),
            "nearest_role": role,
            "nearest_distance": round(dist, 2),
        })

        # 异常判定：与主题色差距大，且不是文本/主色/语义状态点
        if dist > COLOR_ANOMALY_THRESHOLD:
            if role == "primary":
                # 主色区域需要语义解释（按钮/标签），否则突兀
                if n.area > 4000 and not n.text and not n.clickable:
                    anomalies.append(Issue(
                        dimension="颜色",
                        severity="warning",
                        message=f"检测到无明显语义的大面积主色块：{n.cls}，可能破坏整体调性",
                        node=_node_summary(n),
                        suggestion="检查该色块是否为装饰性元素，建议合并到卡片语义或用图标替代"
                    ))
            elif role == "text":
                pass  # 文本颜色偏离多半是小面积图标/强调文字
            else:
                anomalies.append(Issue(
                    dimension="颜色",
                    severity="warning",
                    message=f"节点颜色与主题色盘偏离较大（d={dist:.1f}），可能为不和谐色块",
                    node=_node_summary(n),
                    suggestion="确认是否应使用 surface/background/primary 色域内的颜色"
                ))

    # 统计
    roles = [a["nearest_role"] for a in node_assignments if a["nearest_role"] != "unknown"]
    role_counts = {}
    for r in roles:
        role_counts[r] = role_counts.get(r, 0) + 1

    # 主色数量异常：强调色应少而精
    if primary_count > 5:
        anomalies.append(Issue(
            dimension="颜色",
            severity="warning",
            message=f"检测到 {primary_count} 个主色节点，强调色过多会削弱视觉层级",
            suggestion="将非必要强调元素降级为 surface/on-surface 中性色"
        ))

    # 颜色一致性得分
    anomaly_ratio = len(anomalies) / max(len(nodes), 1)
    score = max(0.0, round(1.0 - anomaly_ratio * 2, 2))

    palette_hex = ["#{:02x}{:02x}{:02x}".format(c[2], c[1], c[0]) for c in palette]
    return {
        "score": score,
        "palette_hex": palette_hex,
        "role_counts": role_counts,
        "assignments": node_assignments,
        "issues": [i.to_dict() for i in anomalies],
    }


# --------------------------- 字体/字号分析 ---------------------------

def analyze_typography(nodes: List[Node]) -> dict:
    """根据 bounds 高度估算字号层级。"""
    text_nodes = [n for n in nodes if n.text and n.height > 10]
    if not text_nodes:
        return {"score": 1.0, "levels": [], "issues": []}

    heights = sorted([n.height for n in text_nodes])
    median_h = float(np.median(heights))
    q1 = float(np.percentile(heights, 25))
    q3 = float(np.percentile(heights, 75))

    # 用简单阈值分层：标题 / 正文 / 辅助
    levels = {"title": [], "body": [], "caption": []}
    for n in text_nodes:
        if n.height >= median_h * 1.35:
            levels["title"].append(n)
        elif n.height <= median_h * 0.75:
            levels["caption"].append(n)
        else:
            levels["body"].append(n)

    issues = []
    # 同级文字高度应一致：按行聚合同级文本？简化：检查高度偏离中位数过大的孤立文字
    for n in text_nodes:
        deviation = abs(n.height - median_h) / max(median_h, 1)
        if deviation > FONT_DEVIATION_RATIO and n.height > 16:
            role = "标题" if n.height >= median_h * 1.35 else ("辅助" if n.height <= median_h * 0.75 else "正文")
            issues.append(Issue(
                dimension="字体",
                severity="info",
                message=f"文字节点高度（{n.height}px）明显偏离中位数（{int(median_h)}px），请确认是否属于预期的 {role} 层级",
                node=_node_summary(n),
                suggestion="同级文字建议使用相同字号，标题层级应明显大于正文"
            ).to_dict())

    # 字体层级得分：标题数量应明显少于正文
    title_ratio = len(levels["title"]) / max(len(text_nodes), 1)
    score = 1.0
    if title_ratio > 0.35:
        score -= 0.25
        issues.append(Issue(
            dimension="字体",
            severity="warning",
            message=f"标题类文字占比过高（{title_ratio:.0%}），信息层级会变平",
            suggestion="减少页面内同时存在的大字号标题，或把部分降为正文"
        ).to_dict())

    score = max(0.0, round(score - len([i for i in issues if i["severity"] == "warning"]) * 0.05, 2))

    return {
        "score": score,
        "height_stats": {"median": round(median_h, 1), "q1": round(q1, 1), "q3": round(q3, 1)},
        "levels": {k: [_node_summary(n) for n in v] for k, v in levels.items()},
        "issues": issues,
    }


# --------------------------- 间距分析 ---------------------------

def _group_rows(nodes: List[Node], overlap_threshold: float = 0.3) -> List[List[Node]]:
    """按垂直重叠将节点分组为行。"""
    if not nodes:
        return []
    sorted_nodes = sorted(nodes, key=lambda n: n.cy)
    rows = [[sorted_nodes[0]]]
    for n in sorted_nodes[1:]:
        last = rows[-1][-1]
        # 计算与上一行最后一个节点在 y 轴上的重叠比例
        overlap = max(0, min(n.y2, last.y2) - max(n.y1, last.y1))
        min_h = min(n.height, last.height)
        if min_h and overlap / min_h > overlap_threshold:
            rows[-1].append(n)
        else:
            rows.append([n])
    return rows


def analyze_spacing(nodes: List[Node], img_bgr: np.ndarray, img_w: int, img_h: int) -> dict:
    """分析主要卡片/模块之间的间距与左右边距一致性。"""
    cards = detect_cards_from_screenshot(img_bgr)
    if len(cards) < 2:
        cards = detect_major_cards_from_xml(nodes, img_w, img_h, min_area=30000)
    if len(cards) < 2:
        return {"score": 1.0, "card_count": len(cards), "issues": []}

    cards_sorted = sorted(cards, key=lambda c: c["bounds"][1])
    boxes = [c["bounds"] for c in cards_sorted]

    gaps = []
    gap_cards = []
    for i in range(1, len(boxes)):
        prev = boxes[i - 1]
        cur = boxes[i]
        # 底部 Tab/导航锚定区不参与
        if prev[1] > img_h * 0.75:
            continue
        g = max(0, cur[1] - prev[3])
        gaps.append(g)
        gap_cards.append((cards_sorted[i - 1], cards_sorted[i]))

    issues = []
    if gaps:
        median_gap = float(np.median(gaps))
        for i, g in enumerate(gaps):
            if median_gap and abs(g - median_gap) / median_gap > SPACING_DEVIATION_RATIO:
                c1, c2 = gap_cards[i]
                issues.append(Issue(
                    dimension="间距",
                    severity="warning",
                    message=f"卡片间隙 {g}px，偏离中位数 {int(median_gap)}px 较大",
                    node={"between_bounds": [c1["bounds"], c2["bounds"]]},
                    suggestion="主要模块间建议使用统一垂直间距（如 12dp/16dp/24dp）"
                ).to_dict())

    # 左右边距一致性：排除全宽组件后统计卡片左边界
    left_edges = [b[0] for b in boxes if b[0] > 10 and (b[2] - b[0]) < 0.95 * img_w and b[1] < img_h * 0.8]
    if len(left_edges) >= 2:
        left_mode = float(np.median(left_edges))
        for i, b in enumerate(boxes):
            if (b[2] - b[0]) < 0.95 * img_w and b[1] < img_h * 0.8:
                if abs(b[0] - left_mode) > 20:
                    issues.append(Issue(
                        dimension="间距",
                        severity="info",
                        message=f"第 {i + 1} 张卡片左边界 {b[0]}px 与卡片中位数 {int(left_mode)}px 不齐",
                        node={"bounds": list(b)},
                        suggestion="卡片建议统一左对齐，保持清晰的视觉纵线"
                    ).to_dict())

    score = max(0.0, round(1.0 - len(issues) * 0.15, 2))
    return {
        "score": score,
        "card_count": len(boxes),
        "median_gap_px": round(median_gap, 1) if gaps else 0,
        "gap_distribution_px": [int(g) for g in gaps],
        "left_margin_median_px": round(left_mode, 1) if left_edges else 0,
        "issues": issues,
    }


# --------------------------- 组件样式分析 ---------------------------

def _sample_region_median(img_bgr: np.ndarray, x1: int, y1: int, x2: int, y2: int,
                           margin: float = 0.18) -> np.ndarray:
    """取矩形中心区域（避开边缘抗锯齿）的颜色中位数，返回 BGR。"""
    h, w = img_bgr.shape[:2]
    ix1 = max(0, int(x1 + (x2 - x1) * margin))
    iy1 = max(0, int(y1 + (y2 - y1) * margin))
    ix2 = min(w, int(x2 - (x2 - x1) * margin))
    iy2 = min(h, int(y2 - (y2 - y1) * margin))
    if ix2 <= ix1 or iy2 <= iy1:
        ix1, iy1, ix2, iy2 = max(0, x1), max(0, y1), min(w, x2), min(h, y2)
    patch = img_bgr[iy1:iy2, ix1:ix2]
    return np.median(patch.reshape(-1, 3), axis=0).astype(np.uint8)


def _is_rounded_card_in_region(img_bgr: np.ndarray, x1: int, y1: int, x2: int, y2: int,
                                cut_threshold: float = 20.0) -> Tuple[bool, int]:
    """
    判断区域内是否为圆角卡片。

    原理（对 Glass You 半透明玻璃卡片更稳健）：
      圆角卡片的“角点”露出的是背景，而卡片内部是玻璃着色（tint）。
      因此 —— 角点探针色与卡片内部色差异大 => 该角被“切掉” => 圆角。
      不依赖全局背景色估计，所以对渐变背景也稳定。

    返回 (是否圆角, 估算圆角半径 px)。
    """
    h, w = img_bgr.shape[:2]
    x1, y1 = max(0, x1), max(0, y1)
    x2, y2 = min(w, x2), min(h, y2)
    if x2 - x1 < 40 or y2 - y1 < 40:
        return False, 0

    interior_lab = bgr_to_lab(_sample_region_median(img_bgr, x1, y1, x2, y2, 0.18))

    probe = max(6, min(x2 - x1, y2 - y1) // 20)
    corners = [
        (x1, y1), (x2 - probe, y1), (x1, y2 - probe), (x2 - probe, y2 - probe)
    ]
    cut_count = 0
    for cx, cy in corners:
        patch = img_bgr[cy:cy + probe, cx:cx + probe]
        if patch.size == 0:
            continue
        corner_bgr = np.median(patch.reshape(-1, 3), axis=0).astype(np.uint8)
        if lab_distance(bgr_to_lab(corner_bgr), interior_lab) > cut_threshold:
            cut_count += 1

    rounded = cut_count >= 3

    r = 0
    if rounded:
        # 从左上角沿对角线向内扫描，找到“背景 -> 玻璃”的切换点 = 半径
        diag_max = min(x2 - x1, y2 - y1) // 2
        corner_bgr = np.median(img_bgr[y1:y1 + probe, x1:x1 + probe].reshape(-1, 3), axis=0).astype(np.uint8)
        corner_lab = bgr_to_lab(corner_bgr)
        for t in range(2, diag_max, 2):
            px, py = x1 + t, y1 + t
            if px >= x2 - 2 or py >= y2 - 2:
                break
            if lab_distance(bgr_to_lab(img_bgr[py, px]), corner_lab) > cut_threshold:
                r = t
                break
    return rounded, int(r)


def detect_cards_from_screenshot(img_bgr: np.ndarray, min_area: int = 15000) -> List[dict]:
    """
    基于颜色分割检测截图中的卡片/模块区域。
    对 Glass You 这类半透明玻璃卡片比 Canny 更稳定：
    k-means 提取 surface 色 -> 膨胀闭合 -> 连通域 -> 合并邻近碎片 -> 过滤背景。
    """
    h, w = img_bgr.shape[:2]
    # 降采样加速 k-means
    small = cv2.resize(img_bgr, (360, 800), interpolation=cv2.INTER_AREA)
    pixels = small.reshape(-1, 3).astype(np.float32)
    criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 20, 1.0)
    _, labels, centers = cv2.kmeans(pixels, 5, None, criteria, 5, cv2.KMEANS_PP_CENTERS)
    centers = centers.astype(np.uint8)
    counts = np.bincount(labels.flatten(), minlength=5)
    order = np.argsort(-counts)

    # 选择 surface 色：亮度高且饱和度低、像素数达到一定占比（排除纯白文字小簇）
    candidates = []
    for i in range(len(centers)):
        lab = cv2.cvtColor(centers[i].reshape(1, 1, 3), cv2.COLOR_BGR2LAB).reshape(3)
        hsv = cv2.cvtColor(centers[i].reshape(1, 1, 3), cv2.COLOR_BGR2HSV).reshape(3)
        sat = hsv[1] / 255.0
        if lab[0] > 110 and sat < 0.25 and counts[i] > 0.03 * pixels.shape[0]:
            candidates.append((lab[0], i))
    if candidates:
        surface_idx = max(candidates, key=lambda x: x[0])[1]
    else:
        # 兜底：选像素数第二多且亮度高的簇
        surface_idx = order[0]
        for i in order[1:]:
            lab = cv2.cvtColor(centers[i].reshape(1, 1, 3), cv2.COLOR_BGR2LAB).reshape(3)
            if lab[0] > 100:
                surface_idx = i
                break

    mask = (labels.reshape(small.shape[:2]) == surface_idx).astype(np.uint8) * 255
    mask_up = cv2.resize(mask, (w, h), interpolation=cv2.INTER_NEAREST)

    # 膨胀把文字/图标造成的孔洞补上，让同一张卡片连通
    kernel = np.ones((21, 21), np.uint8)
    mask_up = cv2.morphologyEx(mask_up, cv2.MORPH_CLOSE, kernel)

    num, _, stats, _ = cv2.connectedComponentsWithStats(mask_up, 8)

    total_area = h * w
    components = []
    for i in range(1, num):
        x, y, cw, ch, area = [int(v) for v in stats[i]]
        x2, y2 = x + cw, y + ch
        # 过滤：过小 / 过大 / 过扁 / 系统栏 / 背景
        if area < min_area:
            continue
        if area > 0.45 * total_area:
            continue
        if cw < 120 or ch < 60 or cw / max(ch, 1) > 12 or ch / max(cw, 1) > 12:
            continue
        if y2 < STATUS_BAR_H * h / 2400:
            continue
        if y > h * 0.9:
            continue
        # 排除全宽背景条 / 过大块
        if cw > 0.9 * w and area > 0.12 * total_area:
            continue
        if area > 0.25 * total_area:
            continue
        components.append({"bounds": (x, y, x2, y2), "area": area})

    # 合并被文字/图标拆碎的邻近组件（同一卡片的碎片）
    components.sort(key=lambda c: c["bounds"][1])
    merged = []
    for c in components:
        x1, y1, x2, y2 = c["bounds"]
        c_area = (x2 - x1) * (y2 - y1)
        merged_with = None
        best_ratio = 0.0
        for i, m in enumerate(merged):
            mx1, my1, mx2, my2 = m["bounds"]
            m_area = (mx2 - mx1) * (my2 - my1)
            # 计算相交区域
            inter_w = max(0, min(x2, mx2) - max(x1, mx1))
            inter_h = max(0, min(y2, my2) - max(y1, my1))
            inter = inter_w * inter_h
            union = c_area + m_area - inter
            # 条件1：明显被包含（小框 50% 以上在大框内）
            containment = inter / max(c_area, m_area, 1) > 0.5
            # 条件2：邻近且水平重叠
            h_overlap = inter_w
            min_w = min(x2 - x1, mx2 - mx1)
            gap = min(abs(y1 - my2), abs(y2 - my1))
            near = min_w and h_overlap / min_w > 0.20 and gap < 60
            if containment or near:
                ratio = inter / union if union else 1.0
                if ratio > best_ratio:
                    best_ratio = ratio
                    merged_with = i
        if merged_with is None:
            merged.append(c)
        else:
            mx1, my1, mx2, my2 = merged[merged_with]["bounds"]
            nx1, ny1, nx2, ny2 = min(x1, mx1), min(y1, my1), max(x2, mx2), max(y2, my2)
            merged[merged_with]["bounds"] = (nx1, ny1, nx2, ny2)
            merged[merged_with]["area"] = (nx2 - nx1) * (ny2 - ny1)

    # 第二轮：按面积 NMS 去掉仍高度重叠 / 过大的块
    merged = sorted(merged, key=lambda c: c["area"], reverse=True)
    final = []
    for c in merged:
        x1, y1, x2, y2 = c["bounds"]
        cw, ch = x2 - x1, y2 - y1
        area = cw * ch
        # 过滤过大的背景残留
        if cw > 0.9 * w and area > 0.12 * total_area:
            continue
        if area > 0.3 * total_area or ch > 0.8 * h:
            continue
        keep = True
        for k in final:
            kx1, ky1, kx2, ky2 = k["bounds"]
            inter = max(0, min(x2, kx2) - max(x1, kx1)) * max(0, min(y2, ky2) - max(y1, ky1))
            union = (x2 - x1) * (y2 - y1) + (kx2 - kx1) * (ky2 - ky1) - inter
            if union and inter / union > 0.5:
                keep = False
                break
        if keep:
            final.append(c)

    return final


def detect_major_cards_from_xml(nodes: List[Node], img_w: int, img_h: int,
                                min_area: int = 40000) -> List[dict]:
    """
    从 uiautomator 层级中识别出可能是卡片的容器节点。
    相比 CV，XML 容器对玻璃/半透明卡片更稳定。
    """
    candidates = []
    for n in nodes:
        if n.area < min_area:
            continue
        if n.width < 120 or n.height < 80:
            continue
        ar = n.width / max(n.height, 1)
        if ar < 0.4 or ar > 8:
            continue
        # 排除全屏根
        if n.width >= 0.95 * img_w and n.height >= 0.95 * img_h:
            continue
        # 排除细长线
        if n.height < 40 and n.width / max(n.height, 1) > 12:
            continue
        # 有内容才像是卡片/模块
        has_content = len(n.children) >= 2 or bool(n.text) or n.clickable
        if not has_content:
            continue
        candidates.append(n)

    # NMS：按面积降序，剔除被大框高度包含的
    candidates.sort(key=lambda x: x.area, reverse=True)
    kept = []
    for c in candidates:
        x1, y1, x2, y2 = c.x1, c.y1, c.x2, c.y2
        is_child = False
        for k in kept:
            kx1, ky1, kx2, ky2 = k.x1, k.y1, k.x2, k.y2
            inter = max(0, min(x2, kx2) - max(x1, kx1)) * max(0, min(y2, ky2) - max(y1, ky1))
            union = (x2 - x1) * (y2 - y1) + (kx2 - kx1) * (ky2 - ky1) - inter
            if union and inter / union > 0.5:
                is_child = True
                break
        if not is_child:
            kept.append(c)

    return [{"bounds": (n.x1, n.y1, n.x2, n.y2), "area": n.area,
             "has_children": bool(n.children)} for n in kept]


def analyze_components(nodes: List[Node], img_bgr: np.ndarray) -> dict:
    """组件样式统一性：卡片圆角、按钮形状等。"""
    img_h, img_w = img_bgr.shape[:2]
    cards = detect_cards_from_screenshot(img_bgr)
    if len(cards) < 2:
        # CV 没检出时回退到 XML 容器
        cards = detect_major_cards_from_xml(nodes, img_w, img_h)
    card_reports = []
    radii = []
    for c in cards:
        x1, y1, x2, y2 = c["bounds"]
        # 为了检测圆角，把卡片边界轻微外扩，让圆角的“切角”背景区落入探针范围
        margin = max(15, min(x2 - x1, y2 - y1) // 25)
        rounded, r = _is_rounded_card_in_region(
            img_bgr,
            max(0, x1 - margin), max(0, y1 - margin),
            min(img_w, x2 + margin), min(img_h, y2 + margin),
        )
        radii.append(r)
        card_reports.append({
            "bounds": [x1, y1, x2, y2],
            "rounded": rounded,
            "estimated_radius_px": r,
        })

    issues = []
    if card_reports:
        rounded_n = sum(1 for c in card_reports if c["rounded"])
        sharp_n = len(card_reports) - rounded_n
        # 圆角风格一致性：同一设计语言下，卡片应统一为圆角或直角
        # （玻璃卡片的绝对圆角半径从截图估算误差较大，仅作参考，不据此告警）
        if len(card_reports) >= 2 and min(rounded_n, sharp_n) > 0:
            issues.append(Issue(
                dimension="组件样式",
                severity="warning",
                message=f"卡片圆角风格不统一：{rounded_n} 张圆角 / {sharp_n} 张直角，混合风格会削弱整体调性",
                suggestion="统一所有卡片为同一圆角语言（如 Glass You 统一圆角，或统一直角）"
            ).to_dict())

    # 按钮一致性：clickable 叶子节点中，高度/形状差异
    btns = [n for n in nodes if n.clickable and n.area > 1500 and n.height > 36]
    if btns:
        widths = [n.width for n in btns]
        heights = [n.height for n in btns]
        if heights:
            h_mean = float(np.mean(heights))
            h_std = float(np.std(heights))
            if h_mean and h_std / h_mean > 0.3:
                issues.append(Issue(
                    dimension="组件样式",
                    severity="info",
                    message=f"可点击按钮高度差异较大（均值 {int(h_mean)}px，标准差 {int(h_std)}px）",
                    suggestion="统一按钮高度（如 40dp/48dp），避免视觉上零碎"
                ).to_dict())

    score = max(0.0, round(1.0 - len(issues) * 0.1, 2))
    return {
        "score": score,
        "cards": card_reports,
        "button_count": len(btns),
        "radius_note": "estimated_radius_px 为基于截图对角线的粗略估算，半透明玻璃卡片误差较大，仅作参考；圆角统一性以 rounded 布尔值（角点是否被背景切掉）为准",
        "issues": issues,
    }


# --------------------------- 工具函数 ---------------------------

def _node_summary(n: Node) -> dict:
    """用于报告的节点摘要。"""
    d = {
        "text": n.text[:40] if n.text else (n.content_desc[:40] if n.content_desc else ""),
        "class": n.cls.split(".")[-1] if n.cls else "",
        "bounds": [n.x1, n.y1, n.x2, n.y2],
        "clickable": n.clickable,
    }
    if n.resource_id:
        d["resource_id"] = n.resource_id
    return d


def draw_overlay(img_bgr: np.ndarray, nodes: List[Node], reports: dict, out_path: str) -> None:
    """在截图上绘制问题标注。"""
    img = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
    pil_img = Image.fromarray(img)
    draw = ImageDraw.Draw(pil_img, "RGBA")

    # 尝试加载字体，失败则用默认
    try:
        font = ImageFont.truetype("arial.ttf", 24)
        small = ImageFont.truetype("arial.ttf", 18)
    except Exception:
        font = ImageFont.load_default()
        small = font

    # 绘制所有有效节点外框（半透明）
    for n in nodes:
        if n.area < MIN_NODE_AREA:
            continue
        draw.rectangle([n.x1, n.y1, n.x2, n.y2], outline=(120, 120, 120, 80), width=1)

    # 绘制问题高亮
    colors = {
        "error": (239, 68, 68, 180),
        "warning": (245, 158, 11, 180),
        "info": (59, 130, 246, 180),
    }

    y_offset = 10
    for dim in ("颜色", "字体", "间距", "组件样式"):
        rpt = reports.get(dim, {})
        for issue in rpt.get("issues", []):
            node_info = issue.get("node")
            color = colors.get(issue.get("severity"), (128, 128, 128, 180))
            if node_info and "bounds" in node_info and len(node_info["bounds"]) == 4:
                x1, y1, x2, y2 = node_info["bounds"]
                draw.rectangle([x1, y1, x2, y2], outline=color, width=3)
                label = f"{issue['severity']}: {issue['message'][:24]}"
                draw.text((x1 + 4, y1 - 22), label, font=small, fill=color)

    # 顶部信息栏
    summary = f"UI Audit | 颜色:{reports.get('颜色', {}).get('score',0)} 字体:{reports.get('字体', {}).get('score',0)} 间距:{reports.get('间距', {}).get('score',0)} 组件:{reports.get('组件样式', {}).get('score',0)}"
    draw.rectangle([0, 0, pil_img.width, 38], fill=(0, 0, 0, 160))
    draw.text((10, 6), summary, font=font, fill=(255, 255, 255, 255))

    pil_img.save(out_path)


def adb_capture_screenshot(out_png: str) -> None:
    out_dir = os.path.dirname(os.path.abspath(out_png))
    os.makedirs(out_dir, exist_ok=True)
    data = _sh(["adb", "exec-out", "screencap", "-p"])
    with open(out_png, "wb") as f:
        f.write(data)


def adb_capture_uiautomator(out_xml: str) -> None:
    out_dir = os.path.dirname(os.path.abspath(out_xml))
    os.makedirs(out_dir, exist_ok=True)
    _sh(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"])
    data = _sh(["adb", "exec-out", "cat", "/sdcard/ui.xml"])
    with open(out_xml, "wb") as f:
        f.write(data)


# --------------------------- 主流程 ---------------------------

def main(argv=None):
    parser = argparse.ArgumentParser(description="UI 风格统一性自动审核")
    parser.add_argument("--capture", action="store_true",
                        help="自动通过 adb 抓取截图和 uiautomator dump")
    parser.add_argument("--screenshot", default="adb-audit-ui.png", help="截图路径")
    parser.add_argument("--xml", default="adb-audit-ui.xml", help="层级 dump 路径")
    parser.add_argument("--out", default="./audit", help="输出目录")
    args = parser.parse_args(argv)

    if args.capture:
        print("[adb] 抓取当前页面截图...")
        adb_capture_screenshot(args.screenshot)
        print(f"  -> {os.path.abspath(args.screenshot)}")
        print("[adb] 抓取 UI 层级 dump...")
        adb_capture_uiautomator(args.xml)
        print(f"  -> {os.path.abspath(args.xml)}")

    if not os.path.exists(args.screenshot):
        print(f"[错误] 截图不存在：{args.screenshot}", file=sys.stderr)
        return 1
    if not os.path.exists(args.xml):
        print(f"[错误] UI dump 不存在：{args.xml}", file=sys.stderr)
        return 1

    os.makedirs(args.out, exist_ok=True)

    print("\n=== UI 风格统一性自动审核 ===\n")

    # 用 PIL 读取，避免 cv2.imread 在非 ASCII 路径上失败
    try:
        pil_img = Image.open(args.screenshot).convert("RGB")
    except Exception as e:
        print(f"[错误] 无法读取截图：{e}", file=sys.stderr)
        return 1
    img_bgr = cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)
    img_h, img_w = img_bgr.shape[:2]

    nodes = parse_uiautomator(args.xml)
    nodes = filter_nodes(nodes, img_h, img_w)
    print(f"解析到 {len(nodes)} 个有效节点（已过滤系统栏与过小节点）")
    print(f"截图尺寸：{img_w}x{img_h}\n")

    color_report = analyze_color(nodes, img_bgr)
    typo_report = analyze_typography(nodes)
    spacing_report = analyze_spacing(nodes, img_bgr, img_w, img_h)
    comp_report = analyze_components(nodes, img_bgr)

    reports = {
        "颜色": color_report,
        "字体": typo_report,
        "间距": spacing_report,
        "组件样式": comp_report,
    }

    # 控制台摘要
    overall_score = round(np.mean([r["score"] for r in reports.values()]), 2)
    print(f"综合统一性得分：{overall_score:.2f} / 1.00\n")
    for dim, rpt in reports.items():
        print(f"【{dim}】得分 {rpt['score']:.2f}")
        if rpt.get("issues"):
            for iss in rpt["issues"]:
                print(f"  [{iss['severity']}] {iss['message']}")
                if iss.get("suggestion"):
                    print(f"      建议：{iss['suggestion']}")
        print()

    # 汇总报告
    summary = {
        "screenshot": os.path.abspath(args.screenshot),
        "uiautomator_xml": os.path.abspath(args.xml),
        "image_size": {"width": img_w, "height": img_h},
        "overall_score": overall_score,
        "dimensions": reports,
    }

    json_path = os.path.join(args.out, "audit_report.json")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    print(f"JSON 报告已保存：{json_path}")

    overlay_path = os.path.join(args.out, "audit_overlay.png")
    draw_overlay(img_bgr, nodes, reports, overlay_path)
    print(f"标注截图已保存：{overlay_path}")

    # 输出格式说明
    print("\n输出格式说明：")
    print("  - audit_report.json：各维度得分、统计值、问题列表（含节点 bounds、建议）")
    print("  - audit_overlay.png：在原图上叠加节点外框与问题高亮框")
    print("  - 控制台：可直接阅读的综合摘要")
    return 0


if __name__ == "__main__":
    sys.exit(main())
