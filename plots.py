#!/usr/bin/env python3
"""Generates the four report figures from results.csv (SUMMARY rows of mpp.out).
Rerunnable: python3 plots.py  ->  fig1..fig4 PNGs alongside the CSV."""
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

df = pd.read_csv("results.csv")
COLORS = {"coarse": "#888888", "mvrlu": "#d62728", "vcas": "#1f77b4"}
LABELS = {"coarse": "Coarse lock (baseline)", "mvrlu": "MV-RLU (simplified)", "vcas": "vCAS/Verlib (simplified)"}
MARKERS = {"coarse": "s", "mvrlu": "o", "vcas": "^"}
THREADS = [1, 8, 32, 64]

def line(ax, sub, impl):
    d = sub[sub["impl"] == impl].sort_values("threads")
    ax.errorbar(d["threads"], d["mean"] / 1e6, yerr=d["std"] / 1e6,
                marker=MARKERS[impl], color=COLORS[impl], label=LABELS[impl],
                capsize=3, linewidth=1.8, markersize=6)

def style(ax, title, logy=False):
    ax.set_title(title, fontsize=11)
    ax.set_xlabel("Threads")
    ax.set_ylabel("Throughput (Mops/s)")
    ax.set_xscale("log", base=2)
    ax.set_xticks(THREADS); ax.set_xticklabels([str(t) for t in THREADS])
    if logy: ax.set_yscale("log")
    ax.grid(True, alpha=0.3)
    ax.legend(fontsize=8)

# Fig 1 -- headline: scaling, read_heavy, size 1024 (log y).
fig, ax = plt.subplots(figsize=(6, 4))
sub = df[(df["size"] == 1024) & (df["mix"] == "read_heavy")]
for impl in ["coarse", "mvrlu", "vcas"]: line(ax, sub, impl)
style(ax, "Read-heavy (90/5/5), 1K elements: versioning scales, the lock does not", logy=True)
ax.annotate("35.7x", xy=(64, 14.02), xytext=(20, 6),
            arrowprops=dict(arrowstyle="->", alpha=0.6), fontsize=10)
fig.tight_layout(); fig.savefig("fig1_read_heavy_scaling.png", dpi=200); plt.close(fig)

# Fig 2 -- lists, write_heavy 1024: MV-RLU/vCAS ratio across TWO runs.
# Run A = original (ascending prefill), Run B = current (shuffled prefill).
# The low-thread MV-RLU lead is robust across runs; the 64-thread sign is not.
import math
def ratio_with_err(sub, t):
    a = sub[(sub["impl"] == "mvrlu") & (sub["threads"] == t)].iloc[0]
    b = sub[(sub["impl"] == "vcas") & (sub["threads"] == t)].iloc[0]
    r = a["mean"] / b["mean"]
    rel = math.sqrt((a["std"]/a["mean"])**2 + (b["std"]/b["mean"])**2) / math.sqrt(3)
    return r, r * rel

# Run A summaries (mean, std) hardcoded from the first benchmark run:
RUN_A = { # threads: (mvrlu_mean, mvrlu_std, vcas_mean, vcas_std)
    1:  (262012, 113,   232101, 98),
    8:  (1592598, 19038, 1476175, 4329),
    32: (5091083, 46694, 4999108, 36494),
    64: (7237714, 89724, 7649148, 31379),
}

fig, ax = plt.subplots(figsize=(6.5, 4.2))
sub = df[(df["size"] == 1024) & (df["mix"] == "write_heavy")]
rB, eB = zip(*[ratio_with_err(sub, t) for t in THREADS])
rA, eA = [], []
for t in THREADS:
    am, asd, bm, bsd = RUN_A[t]
    r = am / bm
    rel = math.sqrt((asd/am)**2 + (bsd/bm)**2) / math.sqrt(3)
    rA.append(r); eA.append(r * rel)
ax.errorbar(THREADS, rA, yerr=eA, marker="s", color="#e08214", capsize=4,
            linewidth=1.6, markersize=6, label="Run A")
ax.errorbar(THREADS, rB, yerr=eB, marker="o", color="#6a3d9a", capsize=4,
            linewidth=1.6, markersize=6, label="Run B")
ax.axhline(1.0, color="black", linewidth=1, linestyle="--")
ax.set_xscale("log", base=2)
ax.set_xticks(THREADS); ax.set_xticklabels([str(t) for t in THREADS])
ax.set_xlabel("Threads")
ax.set_ylabel("Throughput ratio: MV-RLU / vCAS")
ax.set_title("Lists, write-heavy: MV-RLU's per-write advantage erodes with threads;\nthe residual 64-thread difference is NOT robust between runs", fontsize=10)
ax.grid(True, alpha=0.3); ax.legend(fontsize=8)
fig.tight_layout(); fig.savefig("fig2_write_heavy_crossover.png", dpi=200); plt.close(fig)

# Fig 5 -- TREES amplify BOTH counter bottlenecks (the E1 money shot).
fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(9.5, 4))
TREE_COLORS = {"mvrlu-tree": "#d62728", "vcas-tree": "#1f77b4"}
TREE_LABELS = {"mvrlu-tree": "MV-RLU tree", "vcas-tree": "vCAS tree"}
TREE_MARKERS = {"mvrlu-tree": "o", "vcas-tree": "^"}
def tline(ax, mix, impl):
    d = df[(df["size"] == 1024) & (df["mix"] == mix) & (df["impl"] == impl)].sort_values("threads")
    ax.errorbar(d["threads"], d["mean"] / 1e6, yerr=d["std"] / 1e6,
                marker=TREE_MARKERS[impl], color=TREE_COLORS[impl],
                label=TREE_LABELS[impl], capsize=3, linewidth=1.8, markersize=6)
for impl in ["mvrlu-tree", "vcas-tree"]: tline(ax1, "write_heavy", impl)
ax1.set_title("Trees, write-heavy: vCAS up to 2.1x\n(MV-RLU's clock is the bottleneck)", fontsize=10)
for impl in ["mvrlu-tree", "vcas-tree"]: tline(ax2, "read_snap_short", impl)
ax2.set_title("Trees, frequent short snapshots: MV-RLU 1.6x\n(vCAS's camera is the bottleneck)", fontsize=10)
for ax in (ax1, ax2):
    ax.set_xscale("log", base=2)
    ax.set_xticks(THREADS); ax.set_xticklabels([str(t) for t in THREADS])
    ax.set_xlabel("Threads"); ax.set_ylabel("Throughput (Mops/s)")
    ax.grid(True, alpha=0.3); ax.legend(fontsize=8)
fig.tight_layout(); fig.savefig("fig5_trees_counter_asymmetry.png", dpi=200); plt.close(fig)

# Fig 3 -- snapshot mixes at 64 threads, 1024: grouped bars mvrlu vs vcas.
fig, ax = plt.subplots(figsize=(6, 4))
mixes = ["read_heavy", "write_heavy", "read_snap_short", "write_snap_long"]
x = range(len(mixes)); w = 0.35
for i, impl in enumerate(["mvrlu", "vcas"]):
    vals = [df[(df["impl"] == impl) & (df["size"] == 1024) & (df["mix"] == m)
               & (df["threads"] == 64)]["mean"].iloc[0] / 1e6 for m in mixes]
    errs = [df[(df["impl"] == impl) & (df["size"] == 1024) & (df["mix"] == m)
               & (df["threads"] == 64)]["std"].iloc[0] / 1e6 for m in mixes]
    ax.bar([xi + (i - 0.5) * w for xi in x], vals, w, yerr=errs, capsize=3,
           color=COLORS[impl], label=LABELS[impl])
ax.set_xticks(list(x))
ax.set_xticklabels(["read\n90/5/5", "write\n10/45/45", "read+short snaps\n60/10/10/20", "write+long snaps\n30/30/30/10"], fontsize=8)
ax.set_ylabel("Throughput (Mops/s)")
ax.set_title("64 threads, 1K elements: who pays for which global counter", fontsize=11)
ax.grid(True, axis="y", alpha=0.3); ax.legend(fontsize=8)
fig.tight_layout(); fig.savefig("fig3_mix_comparison_64t.png", dpi=200); plt.close(fig)

# Fig 4 -- size effect: read_heavy at 16K (mechanisms converge).
fig, ax = plt.subplots(figsize=(6, 4))
sub = df[(df["size"] == 16384) & (df["mix"] == "read_heavy")]
for impl in ["coarse", "mvrlu", "vcas"]: line(ax, sub, impl)
style(ax, "Read-heavy, 16K elements: traversal dominates, mechanisms converge")
fig.tight_layout(); fig.savefig("fig4_large_size_convergence.png", dpi=200); plt.close(fig)

print("wrote fig1..fig5")
