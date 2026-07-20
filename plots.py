"""Regenerates the four report figures.

Inputs: results_multirun.csv (lists, 5 runs, 9 thread values),
        results_trees_multirun.csv (trees, 5 runs, 9 thread values),
        results.csv (one full 5-impl matrix, 4 thread values; 16K figure)
Outputs: fig1..fig4 PNGs.
"""
import csv, statistics
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

THREADS9 = [1, 2, 4, 8, 16, 24, 32, 48, 64]

def load_multirun(path, mean_col):
    d = {}
    with open(path) as f:
        for row in csv.DictReader(f):
            d[(int(row["run"]), row["impl"], int(row["threads"]), int(row["size"]), row["mix"])] = float(row[mean_col])
    return d, max(k[0] for k in d)

def cross_run(d, nruns, impl, mix, size=1024):
    means, stds = [], []
    for t in THREADS9:
        vals = [d[(r, impl, t, size, mix)] for r in range(1, nruns+1) if (r, impl, t, size, mix) in d]
        means.append(statistics.mean(vals))
        stds.append(statistics.stdev(vals) if len(vals) > 1 else 0.0)
    return means, stds

lists, NL = load_multirun("results_multirun.csv", "mean_ops_per_sec")
trees, NT = load_multirun("results_trees_multirun.csv", "mean_ops_per_sec")
STYLE = {"coarse": ("#888888","s","Coarse lock (baseline)"),
         "mvrlu": ("#d62728","o","MV-RLU (simplified)"),
         "vcas": ("#1f77b4","^","vCAS/Verlib (simplified)")}

fig, ax = plt.subplots(figsize=(7,4.4))
for impl,(c,m,lab) in STYLE.items():
    means, stds = cross_run(lists, NL, impl, "read_heavy")
    ax.errorbar(THREADS9, [x/1e6 for x in means], yerr=[x/1e6 for x in stds],
                marker=m, color=c, label=lab, capsize=3, linewidth=1.8, markersize=5)
ax.set_yscale("log"); ax.set_xscale("log", base=2)
ax.set_xticks(THREADS9); ax.set_xticklabels(map(str,THREADS9))
ax.set_xlabel("Threads"); ax.set_ylabel("Throughput (Mops/s)")
ax.set_title(f"Read-heavy (90/5/5), 1K elements - mean of {NL} independent runs", fontsize=10)
ax.grid(True, alpha=0.3); ax.legend(fontsize=8)
fig.tight_layout(); fig.savefig("fig1_read_heavy_scaling.png", dpi=200); plt.close(fig)

fig, ax = plt.subplots(figsize=(7,4.4))
allr = []
for r in range(1, NL+1):
    ratio = [lists[(r,"mvrlu",t,1024,"write_heavy")]/lists[(r,"vcas",t,1024,"write_heavy")] for t in THREADS9]
    allr.append(ratio); ax.plot(THREADS9, ratio, color="#bbbbbb", linewidth=1, zorder=1)
meanr = [statistics.mean(x) for x in zip(*allr)]
stdr  = [statistics.stdev(x) for x in zip(*allr)]
ax.errorbar(THREADS9, meanr, yerr=stdr, marker="o", color="#6a3d9a", capsize=4,
            linewidth=2.2, markersize=6, zorder=3, label=f"Mean of {NL} runs (+-std)")
ax.plot([], [], color="#bbbbbb", linewidth=1, label="Individual runs")
ax.axhline(1.0, color="black", linewidth=1, linestyle="--")
ax.set_xscale("log", base=2); ax.set_xticks(THREADS9); ax.set_xticklabels(map(str,THREADS9))
ax.set_xlabel("Threads"); ax.set_ylabel("Throughput ratio: MV-RLU / vCAS")
ax.set_title(f"Lists, write-heavy, 1K: MV-RLU/vCAS ratio across {NL} independent runs", fontsize=10)
ax.grid(True, alpha=0.3); ax.legend(fontsize=8)
fig.tight_layout(); fig.savefig("fig2_write_heavy_crossover.png", dpi=200); plt.close(fig)

run_b = {}
with open("results.csv") as f:
    for row in csv.DictReader(f):
        run_b[(row["impl"], int(row["threads"]), int(row["size"]), row["mix"])] = (float(row["mean"]), float(row["std"]))
T4 = [1,8,32,64]
fig, ax = plt.subplots(figsize=(6.5,4))
for impl,(c,m,lab) in STYLE.items():
    means = [run_b[(impl,t,16384,"read_heavy")][0]/1e6 for t in T4]
    stds  = [run_b[(impl,t,16384,"read_heavy")][1]/1e6 for t in T4]
    ax.errorbar(T4, means, yerr=stds, marker=m, color=c, label=lab, capsize=3, linewidth=1.8, markersize=6)
ax.set_xscale("log", base=2); ax.set_xticks(T4); ax.set_xticklabels(map(str,T4))
ax.set_xlabel("Threads"); ax.set_ylabel("Throughput (Mops/s)")
ax.set_title("Read-heavy, 16K elements: traversal dominates, mechanisms converge", fontsize=10)
ax.grid(True, alpha=0.3); ax.legend(fontsize=8)
fig.tight_layout(); fig.savefig("fig3_large_size_convergence.png", dpi=200); plt.close(fig)

fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(10,4.2))
TSTYLE = {"mvrlu-tree": ("#d62728","o","MV-RLU tree"), "vcas-tree": ("#1f77b4","^","vCAS tree")}
def panel(ax, mix, title):
    for impl,(c,m,lab) in TSTYLE.items():
        means, stds = cross_run(trees, NT, impl, mix)
        ax.errorbar(THREADS9, [x/1e6 for x in means], yerr=[x/1e6 for x in stds],
                    marker=m, color=c, label=lab, capsize=3, linewidth=1.8, markersize=5)
    ax.set_xscale("log", base=2); ax.set_xticks(THREADS9); ax.set_xticklabels(map(str,THREADS9))
    ax.set_xlabel("Threads"); ax.set_ylabel("Throughput (Mops/s)")
    ax.set_title(title, fontsize=10); ax.grid(True, alpha=0.3); ax.legend(fontsize=8)
panel(ax1, "write_heavy", f"Trees, write-heavy: vCAS pulls ahead up to ~2.1x\n(mean of {NT} runs)")
panel(ax2, "read_snap_short", f"Trees, frequent short snapshots: MV-RLU ahead up to ~1.5x\n(mean of {NT} runs)")
fig.tight_layout(); fig.savefig("fig4_trees_counter_asymmetry.png", dpi=200); plt.close(fig)
print("wrote fig1..fig4")
