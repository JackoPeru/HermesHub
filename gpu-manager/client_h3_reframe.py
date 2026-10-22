#!/usr/bin/env python3
"""Hermes GPU manager job client (H3 Ref2VA reframe test).
Usage: python3 _h3_reframe_submit.py [seconds]

Pipeline sanzionata post-lockdown: l'agente NON parla col manager (niente
chiave, niente yaml). Sottomette il preset a laya (/laya/jobs, ruolo agent)
e segue lo stato sempre via laya. Chiave agent = HERMES_GATEWAY_API_KEY.
"""
import json, os, sys, time, urllib.request

KEY = os.environ.get("HERMES_GATEWAY_API_KEY", "")
BASE = os.environ.get("HERMES_LAYA_URL", "http://127.0.0.1:8645")
if not KEY:
    print("HERMES_GATEWAY_API_KEY mancante: lo script gira come agente.", file=sys.stderr)
    sys.exit(2)

def get(p):
    r = urllib.request.Request(BASE + p, headers={'Authorization': 'Bearer ' + KEY})
    return json.load(urllib.request.urlopen(r, timeout=60))

def post(p, body):
    r = urllib.request.Request(BASE + p, data=json.dumps(body).encode(),
                               headers={'Authorization': 'Bearer ' + KEY, 'Content-Type': 'application/json'})
    try:
        return json.load(urllib.request.urlopen(r, timeout=120))
    except urllib.error.HTTPError as e:
        return {'http': e.code, 'body': e.read().decode()[:500]}

PROMPT = (
    "Recreate the reference video as a native vertical 9:16 cinematic composition. "
    "Use <Video 1> as the primary reference for subjects, actions, environments, "
    "materials, camera movement, and temporal progression. "
    "Recompose and reconstruct the scene for portrait framing rather than cropping "
    "the original widescreen footage. "
    "Use <Audio 1> as the reference soundtrack, keeping voice and ambience temporally "
    "aligned with the visuals. "
    "Preserve physical realism, visual consistency, and the original sequence of events. "
    "Keep a continuous single-take camera movement without cuts, transitions, sudden "
    "jumps, or unrelated shots. "
    "Maintain the identity and appearance of objects and characters. "
    "Avoid visual distortions, geometry changes, flickering, and inconsistent object placement."
)

def main():
    secs = int(sys.argv[1]) if len(sys.argv) > 1 else 5
    duration_limit = min(secs, 15)  # ref2va trained range ~124-362 frames; ref video max 15s
    body = {
        "preset": "journey_video_reframe916",
        "parameters": {
            "prompt": PROMPT,
            "input_video": "/opt/hermes/runtimes/comfyui/app/input/h3_reframe_test.mp4",
            "width": 768,
            "height": 1344,
            "duration": duration_limit,
            "steps": 22,
            "seed": 42,
        },
    }
    out = post('/laya/jobs', body)
    print(json.dumps(out))
    jid = out.get('job_id')
    if not jid:
        sys.exit(1)
    # poll loop with explicit waits; safe up to ~40 min
    t0 = time.time()
    last = None
    while time.time() - t0 < 2400:
        time.sleep(20)  # generous polling interval, sleep as a real wait
        s = get(f'/laya/jobs?job_id={jid}')
        status = s.get('status')
        progress = s.get('progress')
        line = f"t+{int(time.time()-t0)}s status={status} progress={progress} err={s.get('error','')[:200]}"
        if line != last:
            print(line, flush=True)
            last = line
        if status in ('done', 'failed', 'canceled'):
            break
    s = get(f'/laya/jobs?job_id={jid}')
    print("FINAL:", json.dumps({k: s.get(k) for k in ('status', 'progress', 'error', 'result_paths', 'duration_s')}, ensure_ascii=False, indent=1))

if __name__ == '__main__':
    main()