#!/usr/bin/env python3

from __future__ import annotations

import re
from pathlib import Path

import matplotlib.pyplot as plt


REPO_ROOT = Path(__file__).resolve().parents[2]
SM_CONFIG_PATH = REPO_ROOT / "src/main/scala/spinalgpu/SmConfig.scala"
OUTPUT_PATH = REPO_ROOT / "docs/figures/subsm_partition_fp16_throughput.png"

H100_NVL_BASE_GHZ = 1.080
H100_NVL_BOOST_GHZ = 1.785

H100_NVL_SOURCE = "https://www.nvidia.com/content/dam/en-zz/Solutions/Data-Center/h100/PB-11773-001_v01.pdf"
B200_SOURCES = (
    "https://www.nvidia.com/en-us/data-center/dgx-b200/",
    "https://docs.nvidia.com/dgx/dgxb200-user-guide/introduction-to-dgxb200.html",
)


def parse_sm_defaults(path: Path) -> dict[str, int]:
    source = path.read_text()

    def read_default(name: str) -> int:
        pattern = rf"^\s*{re.escape(name)}:\s*Int\s*=\s*(\d+)"
        match = re.search(pattern, source, flags=re.MULTILINE)
        if match is None:
            raise RuntimeError(f"could not find SmConfig default for {name}")
        return int(match.group(1))

    return {
        "sub_sm_issue_width": read_default("subSmIssueWidth"),
        "fp16_scalar_latency": read_default("fp16ScalarLatency"),
        "fp16x2_latency": read_default("fp16x2Latency"),
    }


def main() -> None:
    config = parse_sm_defaults(SM_CONFIG_PATH)

    lanes = config["sub_sm_issue_width"]
    fp16_scalar_latency = config["fp16_scalar_latency"]
    fp16x2_latency = config["fp16x2_latency"]

    frequencies_ghz = [step / 100.0 for step in range(0, 301)]

    fp16_scalar_add_mul = [
        lanes * 1.0 / fp16_scalar_latency * freq for freq in frequencies_ghz
    ]
    fp16_scalar_fma = [
        lanes * 2.0 / fp16_scalar_latency * freq for freq in frequencies_ghz
    ]
    fp16_packed_f16x2_add_mul = [
        lanes * 2.0 / fp16x2_latency * freq for freq in frequencies_ghz
    ]

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)

    plt.style.use("seaborn-v0_8-whitegrid")
    fig, ax = plt.subplots(figsize=(9.5, 6.0))

    ax.plot(
        frequencies_ghz,
        fp16_scalar_add_mul,
        color="#1d4ed8",
        linewidth=2.8,
        label="FP16 scalar add/mul",
    )
    ax.plot(
        frequencies_ghz,
        fp16_scalar_fma,
        color="#ea580c",
        linewidth=2.8,
        label="FP16 scalar FMA",
    )
    ax.plot(
        frequencies_ghz,
        fp16_packed_f16x2_add_mul,
        color="#059669",
        linewidth=2.4,
        linestyle="--",
        dashes=(5, 3),
        label="FP16 packed f16x2 add/mul",
    )

    for freq, label, color in (
        (H100_NVL_BASE_GHZ, "NVIDIA H100 NVL base", "#7c3aed"),
        (H100_NVL_BOOST_GHZ, "NVIDIA H100 NVL boost", "#dc2626"),
    ):
        ax.axvline(freq, color=color, linestyle=":", linewidth=2.0)
        ax.text(
            freq + 0.02,
            0.96,
            label,
            transform=ax.get_xaxis_transform(),
            rotation=90,
            va="top",
            ha="left",
            color=color,
            fontsize=9,
        )

    ax.set_xlim(0.0, 3.0)
    ax.set_ylim(0.0, 50.0)
    ax.set_xlabel("Frequency (GHz)")
    ax.set_ylabel("Throughput (GFLOP/s per sub-SM partition)")
    ax.set_title("SpinalGPU sub-SM partition FP16 throughput vs frequency")
    ax.legend(loc="upper left")

    note = (
        "FP8 arithmetic FLOPs are 0 in the current SpinalGPU CUDA path; only packed "
        "FP8<->FP16x2 conversion ops exist.\n"
        "NVIDIA B200 omitted because no official public GPU clock was found in the cited "
        "sources.\n"
        f"H100 NVL source: {H100_NVL_SOURCE}\n"
        f"B200 sources: {B200_SOURCES[0]} and {B200_SOURCES[1]}"
    )
    fig.text(0.015, 0.01, note, ha="left", va="bottom", fontsize=8.5)

    fig.tight_layout(rect=(0.0, 0.12, 1.0, 1.0))
    fig.savefig(OUTPUT_PATH, dpi=200)
    plt.close(fig)

    print(f"wrote {OUTPUT_PATH.relative_to(REPO_ROOT)}")
    print(f"lanes per sub-SM partition: {lanes}")
    print(f"FP16 scalar add/mul at 1.0 GHz: {lanes * 1.0 / fp16_scalar_latency:.1f} GFLOP/s")
    print(f"FP16 scalar FMA at 1.0 GHz: {lanes * 2.0 / fp16_scalar_latency:.1f} GFLOP/s")
    print(f"FP16 packed f16x2 add/mul at 1.0 GHz: {lanes * 2.0 / fp16x2_latency:.1f} GFLOP/s")


if __name__ == "__main__":
    main()
