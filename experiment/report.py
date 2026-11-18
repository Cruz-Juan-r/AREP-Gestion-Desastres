"""Builds figures, a Markdown summary and a LaTeX table from results.json.

    python -m experiment.report results/
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

EDGE = "#2a78d6"     # categorical slot 1
CLOUD = "#eb6834"    # categorical slot 2
INK = "#2b2b2b"
MUTED = "#6b6b6b"
GRID = "#e6e6e6"
OUTAGE = "#d9d9d9"

plt.rcParams.update({
    "font.size": 10, "axes.edgecolor": MUTED, "axes.labelcolor": INK, "xtick.color": MUTED,
    "ytick.color": MUTED, "axes.spines.top": False, "axes.spines.right": False,
    "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.6, "legend.frameon": False,
})


def _fmt(v, unit="", nd=0):
    if v is None:
        return "--"
    # LaTeX: thin space as thousands separator (avoids reading 2.379 as a decimal)
    return f"{v:,.{nd}f}{unit}".replace(",", "\\,")


def fig_latency(r: dict, out: Path) -> None:
    fig, ax = plt.subplots(figsize=(7.0, 2.6))
    rows = [("Solo nube", r["cloud_only"], CLOUD, 0), ("Edge", r["edge"], EDGE, 1)]
    for label, res, color, y in rows:
        eps = res["alerts"]["per_episode"]
        det = [e for e in eps if e["detected"]]
        xs = [max(e["latency_ms"], 1) for e in det]
        # small deterministic vertical spread so identical latencies stay visible
        ys = [y + (0.14 if e["during_outage"] else -0.14) + ((i % 5) - 2) * 0.035
              for i, e in enumerate(det)]
        ax.scatter(xs, ys, s=36, color=color, edgecolor="white", linewidth=1.2, zorder=3)
        missed = len(eps) - len(det)
        p95 = res["alerts"]["latency_ms"]["p95"]
        txt = f"p95 = {p95:,.0f} ms".replace(",", " ")
        if missed:
            txt += f"  ·  {missed} episodios sin alerta"
        ax.text(1.0, y + 0.36, txt, transform=ax.get_yaxis_transform(), ha="right", va="center",
                fontsize=9, color=INK)
    ax.set_xscale("log")
    ax.set_yticks([0, 1], ["Solo nube", "Edge"])
    ax.set_ylim(-0.6, 1.6)
    ax.set_xlabel("Latencia de alerta: lectura anómala → alerta recibida en el municipio (ms, escala log)")
    ax.grid(axis="y", visible=False)
    ax.text(0.0, 1.02, "Arriba de cada fila: episodios durante la caída · abajo: con enlace",
            transform=ax.transAxes, fontsize=8, color=MUTED)
    fig.tight_layout()
    fig.savefig(out / "fig_latencia_alertas.png", dpi=200)
    plt.close(fig)


def fig_timeline(r: dict, out: Path) -> None:
    fig, (a1, a2) = plt.subplots(2, 1, figsize=(7.0, 4.4), sharex=True,
                                 gridspec_kw={"height_ratios": [3, 2]})
    for res, color, label in [(r["edge"], EDGE, "Edge (outbox + sincronización)"),
                              (r["cloud_only"], CLOUD, "Solo nube")]:
        tl = [p for p in res["timeline"] if "cloud_events" in p]
        if not tl:
            continue
        t0 = res["timeline"][0]["ts_ms"]
        xs = [(p["ts_ms"] - t0) / 1000 for p in tl]
        a1.plot(xs, [p["cloud_events"] for p in tl], color=color, linewidth=2, label=label)
        a1.text(xs[-1] + 1, tl[-1]["cloud_events"], label.split(" (")[0], color=INK, fontsize=9,
                va="center")
        if res["mode"] == "edge":
            pts = [p for p in tl if "edge_pending" in p]
            a2.plot([(p["ts_ms"] - t0) / 1000 for p in pts], [p["edge_pending"] for p in pts],
                    color=EDGE, linewidth=2)
        dn, up = res["outage_window_ms"]
        if dn and up:
            for ax in (a1, a2):
                ax.axvspan((dn - t0) / 1000, (up - t0) / 1000, color=OUTAGE, alpha=0.35, lw=0)
    dn, up = r["edge"]["outage_window_ms"]
    t0 = r["edge"]["timeline"][0]["ts_ms"]
    a1.text(((dn + up) / 2 - t0) / 1000, 0.95, "enlace caído", transform=a1.get_xaxis_transform(),
            ha="center", va="top", fontsize=8, color=MUTED)
    a1.set_ylabel("Lecturas en la nube")
    a1.legend(loc="upper left", fontsize=9)
    a2.set_ylabel("Pendientes en edge")
    a2.set_xlabel("Tiempo desde el inicio del escenario (s)")
    fig.tight_layout()
    fig.savefig(out / "fig_linea_tiempo.png", dpi=200)
    plt.close(fig)


def latex_table(r: dict, out: Path) -> None:
    c, e = r["cloud_only"], r["edge"]
    ca, ea = c["alerts"], e["alerts"]
    rows = [
        ("Lecturas generadas", _fmt(c["readings_sent"]), _fmt(e["readings_sent"])),
        ("Lecturas perdidas", f"{_fmt(c['readings_lost'])} ({_fmt(c['loss_pct'], ' \\%', 1)})",
         f"{_fmt(e['readings_lost'])} ({_fmt(e['loss_pct'], ' \\%', 1)})"),
        ("Episodios detectados (recall)", f"{ca['episodes_detected']}/{ca['episodes']} ({ca['recall']:.2f})",
         f"{ea['episodes_detected']}/{ea['episodes']} ({ea['recall']:.2f})"),
        ("Episodios detectados durante la caída", f"{ca['detected_during_outage']}/{ca['episodes_during_outage']}",
         f"{ea['detected_during_outage']}/{ea['episodes_during_outage']}"),
        ("Precisión de las alertas", _fmt(ca["precision"], nd=2), _fmt(ea["precision"], nd=2)),
        ("Latencia de alerta p50 (ms)", _fmt(ca["latency_ms"]["p50"]), _fmt(ea["latency_ms"]["p50"])),
        ("Latencia de alerta p95 (ms)", _fmt(ca["latency_ms"]["p95"]), _fmt(ea["latency_ms"]["p95"])),
        ("Duplicados almacenados en la nube", _fmt(c["duplicates_stored_in_cloud"]),
         _fmt(e["duplicates_stored_in_cloud"])),
        ("Reenvíos rechazados por idempotencia", "--", _fmt(e["duplicates_rejected_by_idempotency"])),
        ("Backlog máximo en edge (lecturas)", "--", _fmt(e["max_edge_backlog"])),
        ("Vaciado del backlog tras reconectar (s)", "--", _fmt(e["backlog_drain_s"], nd=1)),
    ]
    lines = ["\\begin{tabularx}{\\textwidth}{@{}Lrr@{}}", "\\toprule",
             "\\textbf{Métrica} & \\textbf{Solo nube} & \\textbf{Edge} \\\\", "\\midrule"]
    lines += [f"{a} & {b} & {c_} \\\\" for a, b, c_ in rows]
    lines += ["\\bottomrule", "\\end{tabularx}"]
    (out / "tabla_resultados.tex").write_text("\n".join(lines) + "\n")


def summary_md(r: dict, out: Path) -> None:
    c, e = r["cloud_only"], r["edge"]
    t, s = r.get("tamper_test"), r.get("stress")
    cfg = r["config"]
    md = [
        "# Resultados del experimento", "",
        f"Escenario: {cfg['duration']:.0f} s, 50 dispositivos a {cfg['rate_hz']} Hz, caída del enlace "
        f"entre {cfg['outage'][0]:.0f} s y {cfg['outage'][1]:.0f} s, latencia WAN {cfg['latency']:.0f}±"
        f"{cfg['jitter']:.0f} ms por sentido, pérdida de ACK inyectada {cfg['ack_loss']:.0%}.", "",
        "| Métrica | Solo nube | Edge |", "|---|---:|---:|",
        f"| Lecturas generadas | {c['readings_sent']} | {e['readings_sent']} |",
        f"| Lecturas perdidas | {c['readings_lost']} ({c['loss_pct']} %) | {e['readings_lost']} ({e['loss_pct']} %) |",
        f"| Recall | {c['alerts']['recall']} | {e['alerts']['recall']} |",
        f"| Detectados durante la caída | {c['alerts']['detected_during_outage']}/{c['alerts']['episodes_during_outage']} "
        f"| {e['alerts']['detected_during_outage']}/{e['alerts']['episodes_during_outage']} |",
        f"| Precisión | {c['alerts']['precision']} | {e['alerts']['precision']} |",
        f"| Latencia p50 / p95 (ms) | {c['alerts']['latency_ms']['p50']} / {c['alerts']['latency_ms']['p95']} "
        f"| {e['alerts']['latency_ms']['p50']} / {e['alerts']['latency_ms']['p95']} |",
        f"| Duplicados almacenados | {c['duplicates_stored_in_cloud']} | {e['duplicates_stored_in_cloud']} |",
        f"| Reenvíos rechazados por idempotencia | - | {e['duplicates_rejected_by_idempotency']} |",
        f"| ACK perdidos inyectados | - | {e['acks_dropped_injected']} |",
        f"| Backlog máximo / vaciado | - | {e['max_edge_backlog']} / {e['backlog_drain_s']} s |", "",
    ]
    if e.get("audit"):
        md.append(f"Auditoría: cadena válida = {e['audit']['valid']} ({e['audit']['entries']} entradas).")
    if t:
        md.append(f"Prueba de alteración: se modificó la entrada {t['tampered_seq']}; detectada = {t['detected']} "
                  f"(primera entrada inválida: {t['after']['first_invalid_seq']}).")
    if s:
        md += ["", "## Carga (solo edge, sin caída)", "",
               f"Tasa ofrecida {s['offered_rate_msg_s']} msg/s, procesadas {s['readings_processed']}/{s['readings_sent']}, "
               f"procesamiento p50/p95/p99 = {s['processing_ms_p50']}/{s['processing_ms_p95']}/{s['processing_ms_p99']} ms, "
               f"latencia de alerta p95 = {s['alert_latency_ms_p95']} ms, recall = {s['recall']}."]
    (out / "summary.md").write_text("\n".join(md) + "\n")


def build(out: Path) -> None:
    r = json.loads((out / "results.json").read_text())
    fig_latency(r, out)
    fig_timeline(r, out)
    latex_table(r, out)
    summary_md(r, out)
    print(f"Report written to {out}")


if __name__ == "__main__":
    build(Path(sys.argv[1] if len(sys.argv) > 1 else "results"))
