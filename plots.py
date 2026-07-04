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

# Fig 2 -- the crossover: write_heavy 1024, mvrlu vs vcas only (linear y).
fig, ax = plt.subplots(figsize=(6, 4))
sub = df[(df["size"] == 1024) & (df["mix"] == "write_heavy")]
for impl in ["mvrlu", "vcas"]: line(ax, sub, impl)
style(ax, "Write-heavy (10/45/45), 1K elements: clock-contention crossover")
ax.axvspan(32, 64, color="orange", alpha=0.12)
ax.annotate("crossover:\nMV-RLU writers hammer the global clock,\nvCAS writers never touch the camera",
            xy=(45, 6.4), fontsize=8, ha="center")
fig.tight_layout(); fig.savefig("fig2_write_heavy_crossover.png", dpi=200); plt.close(fig)

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

print("wrote fig1..fig4")
