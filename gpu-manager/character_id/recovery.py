"""Recovery Character ID: scan job interrotti (reboot/crash) + cleanup lock stale."""

from __future__ import annotations

from .training import clear_lock, pid_alive, read_lock


def scan_interruptions(store) -> list[dict]:
    """Job attivi con worker morto -> failed/interrupted. Ritorna azioni fatte."""
    actions = []
    for character in store.list_characters():
        cid = character["id"]
        job = store.active_job(cid)
        if job is None:
            continue
        if pid_alive(int(job.get("pid", 0) or 0)):
            continue  # worker ancora vivo (es. manager riavviato, training vivo)
        store.update_job(job["id"], status="failed", progress=job.get("progress", 0.0),
                         detail="interrotto (reboot/crash)",
                         error="worker morto durante '%s': checkpoint validi conservati" % job.get("kind"))
        if store.get_row(cid) is not None:
            store.set_status(cid, "interrupted")
        actions.append({"character_id": cid, "job_id": job["id"], "kind": job.get("kind")})
    # Lock senza job attivo = stale.
    if read_lock(store.root) is not None:
        still_active = any(store.active_job(c["id"]) for c in store.list_characters())
        if not still_active:
            clear_lock(store.root)
            actions.append({"cleanup": "training.lock stale rimosso"})
    return actions
