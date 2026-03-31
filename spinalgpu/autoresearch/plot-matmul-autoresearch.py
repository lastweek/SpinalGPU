#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import os
import struct
import zlib
from pathlib import Path

try:
    import matplotlib.pyplot as plt  # type: ignore
except ImportError:  # pragma: no cover - exercised by runtime fallback instead
    plt = None


def load_rounds(path: Path) -> list[dict]:
    rows: list[dict] = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        rows.append(json.loads(line))
    rows.sort(key=lambda row: int(row["round"]))
    return rows


def build_best_so_far(rows: list[dict]) -> list[tuple[int, int, int]]:
    best_cuda: int | None = None
    best_tcgen: int | None = None
    output: list[tuple[int, int, int]] = []
    for row in rows:
        if row.get("decision") == "accepted":
            cuda = int(row["cuda_core_cycles"])
            tcgen = int(row["tcgen05_cycles"])
            if cuda >= 0:
                best_cuda = cuda if best_cuda is None else min(best_cuda, cuda)
            if tcgen >= 0:
                best_tcgen = tcgen if best_tcgen is None else min(best_tcgen, tcgen)
        output.append((int(row["round"]), best_cuda or -1, best_tcgen or -1))
    return output


def _chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def _set_pixel(buffer: bytearray, width: int, height: int, x: int, y: int, color: tuple[int, int, int]) -> None:
    if 0 <= x < width and 0 <= y < height:
        offset = (y * width + x) * 3
        buffer[offset:offset + 3] = bytes(color)


def _draw_line(
    buffer: bytearray,
    width: int,
    height: int,
    x0: int,
    y0: int,
    x1: int,
    y1: int,
    color: tuple[int, int, int],
) -> None:
    dx = abs(x1 - x0)
    dy = -abs(y1 - y0)
    step_x = 1 if x0 < x1 else -1
    step_y = 1 if y0 < y1 else -1
    error = dx + dy

    while True:
        for px in range(x0 - 1, x0 + 2):
            for py in range(y0 - 1, y0 + 2):
                _set_pixel(buffer, width, height, px, py, color)
        if x0 == x1 and y0 == y1:
            break
        doubled = error * 2
        if doubled >= dy:
            error += dy
            x0 += step_x
        if doubled <= dx:
            error += dx
            y0 += step_y


def render_fallback_png(series: list[tuple[int, int, int]], png_path: Path) -> None:
    width = 960
    height = 540
    margin_left = 72
    margin_right = 32
    margin_top = 32
    margin_bottom = 56
    plot_width = width - margin_left - margin_right
    plot_height = height - margin_top - margin_bottom
    background = (250, 250, 250)
    axis = (120, 120, 120)
    grid = (225, 225, 225)
    cuda_color = (29, 78, 216)
    tcgen_color = (220, 38, 38)

    buffer = bytearray(background * width * height)

    values = [value for _, cuda, tcgen in series for value in (cuda, tcgen) if value >= 0]
    if not values:
        values = [0, 1]
    min_value = min(values)
    max_value = max(values)
    if min_value == max_value:
        min_value -= 1
        max_value += 1

    max_round = max(round_index for round_index, _, _ in series)

    def map_x(round_index: int) -> int:
        if max_round == 0:
            return margin_left + (plot_width // 2)
        return margin_left + int((round_index / max_round) * plot_width)

    def map_y(value: int) -> int:
        normalized = (value - min_value) / float(max_value - min_value)
        return margin_top + plot_height - int(normalized * plot_height)

    for line_index in range(6):
        y = margin_top + int((line_index / 5.0) * plot_height)
        _draw_line(buffer, width, height, margin_left, y, width - margin_right, y, grid)

    _draw_line(buffer, width, height, margin_left, margin_top, margin_left, height - margin_bottom, axis)
    _draw_line(
        buffer,
        width,
        height,
        margin_left,
        height - margin_bottom,
        width - margin_right,
        height - margin_bottom,
        axis,
    )

    valid_cuda = [(map_x(round_index), map_y(cuda)) for round_index, cuda, _ in series if cuda >= 0]
    valid_tcgen = [(map_x(round_index), map_y(tcgen)) for round_index, _, tcgen in series if tcgen >= 0]

    for points, color in ((valid_cuda, cuda_color), (valid_tcgen, tcgen_color)):
        for index in range(1, len(points)):
            _draw_line(
                buffer,
                width,
                height,
                points[index - 1][0],
                points[index - 1][1],
                points[index][0],
                points[index][1],
                color,
            )

    raw_rows = []
    stride = width * 3
    for row in range(height):
        start = row * stride
        raw_rows.append(b"\x00" + bytes(buffer[start:start + stride]))

    png_data = b"".join(
        [
            b"\x89PNG\r\n\x1a\n",
            _chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)),
            _chunk(b"IDAT", zlib.compress(b"".join(raw_rows), level=9)),
            _chunk(b"IEND", b""),
        ]
    )
    png_path.write_bytes(png_data)


def render_png(series: list[tuple[int, int, int]], png_path: Path) -> None:
    if plt is None:
        render_fallback_png(series, png_path)
        return

    x_values = [round_index for round_index, _, _ in series]
    cuda_values = [cuda for _, cuda, _ in series]
    tcgen_values = [tcgen for _, _, tcgen in series]

    plt.style.use("seaborn-v0_8-whitegrid")
    fig, ax = plt.subplots(figsize=(9.5, 5.5))
    ax.plot(x_values, cuda_values, color="#1d4ed8", linewidth=2.8, marker="o", label="CUDA core")
    ax.plot(x_values, tcgen_values, color="#dc2626", linewidth=2.8, marker="o", label="tcgen05")
    ax.set_xlabel("Iteration")
    ax.set_ylabel("Best-so-far cycles")
    ax.set_title("Matmul autoresearch performance evolution (32x32x32)")
    ax.legend(loc="upper right")
    fig.tight_layout()
    fig.savefig(png_path, dpi=200)
    plt.close(fig)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--session", required=True)
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    log_root = Path(os.environ.get("SPINALGPU_AUTORESEARCH_LOG_ROOT", str(repo_root / "spinalgpu" / "autoresearch-logs")))
    session_dir = log_root / "sessions" / args.session
    rounds_path = session_dir / "rounds.jsonl"
    csv_path = session_dir / "perf_evolution.csv"
    png_path = session_dir / "perf_evolution.png"

    rows = load_rounds(rounds_path)
    series = build_best_so_far(rows)
    if not series:
        raise RuntimeError(f"no rounds found in {rounds_path}")

    csv_lines = ["round,best_cuda_core_cycles,best_tcgen05_cycles"]
    for round_index, cuda, tcgen in series:
        csv_lines.append(f"{round_index},{cuda},{tcgen}")

    csv_path.write_text("\n".join(csv_lines) + "\n")
    render_png(series, png_path)

    print(f"wrote {csv_path}")
    print(f"wrote {png_path}")


if __name__ == "__main__":
    main()
