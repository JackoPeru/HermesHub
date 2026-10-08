"""Backend volti Character ID (solo worker server).

Priorita: insightface (detection + embedding ArcFace + landmarks) se installato
nel tools-venv; fallback OpenCV Haar (solo box, niente embedding); assente =
nessun volto rilevabile (pipeline onesta: asset marcati di conseguenza).

Niente download qui: i pesi buffalo_l vanno in
/opt/hermes/character-id/models/face (setup M3, script dedicato).
"""

from __future__ import annotations

from pathlib import Path

FACE_MODEL_DIR = "/opt/hermes/character-id/models/face"
FACE_MODEL_NAME = "buffalo_l"


def available_backends() -> list[str]:
    found = []
    try:
        import insightface  # noqa: F401
        import onnxruntime  # noqa: F401

        found.append("insightface")
    except ImportError:
        pass
    try:
        import cv2  # noqa: F401

        found.append("haar")
    except ImportError:
        pass
    return found


def detect_faces(path: Path, preferred: str = "insightface") -> list[dict]:
    """Ritorna [{bbox:(x1,y1,x2,y2), score, embedding|None, yaw|None, pitch|None}].

    Il fallback scatta solo su ECCEZIONE del backend (modello rotto, OOM...),
    mai su "zero volti": se insightface (accurato) non vede volti, haar (debole)
    aggiungerebbe solo falsi positivi.
    """
    backends = available_backends()
    if preferred in backends:
        order = [preferred] + [b for b in backends if b != preferred]
    else:
        order = backends
    for backend in order:
        try:
            if backend == "insightface":
                return _detect_insightface(path)
            return _detect_haar(path)
        except Exception:
            continue
    return []


def _detect_insightface(path: Path) -> list[dict]:
    from insightface.app import FaceAnalysis

    global _INSIGHT_APP
    try:
        _INSIGHT_APP
    except NameError:
        _INSIGHT_APP = None
    if _INSIGHT_APP is None:
        _INSIGHT_APP = FaceAnalysis(name=FACE_MODEL_NAME, root=FACE_MODEL_DIR)
        _INSIGHT_APP.prepare(ctx_id=-1)  # CPU: il worker analyze non tocca la GPU
    import cv2

    image = cv2.imread(str(path))
    if image is None:
        return []
    faces = _INSIGHT_APP.get(image)
    out = []
    for face in sorted(faces, key=lambda f: float(getattr(f, "det_score", 0.0)), reverse=True):
        box = [int(v) for v in face.bbox]
        embedding = face.get("embedding")
        yaw = pitch = None
        kps = face.get("kps")
        if kps is not None and len(kps) >= 5:
            # Euristica landmarks: asimmetria occhi -> yaw, asse verticale -> pitch.
            left_eye, right_eye, nose, mouth_l, mouth_r = (tuple(map(float, p)) for p in kps[:5])
            eye_dist = abs(right_eye[0] - left_eye[0]) or 1.0
            mid_eye_x = (left_eye[0] + right_eye[0]) / 2.0
            yaw = max(-90.0, min(90.0, (nose[0] - mid_eye_x) / eye_dist * 90.0))
            mouth_mid_y = (mouth_l[1] + mouth_r[1]) / 2.0
            eye_mid_y = (left_eye[1] + right_eye[1]) / 2.0
            face_h = abs(mouth_mid_y - eye_mid_y) or 1.0
            pitch = max(-90.0, min(90.0, (nose[1] - eye_mid_y) / face_h * 45.0))
        out.append(
            {
                "bbox": (box[0], box[1], box[2], box[3]),
                "score": float(getattr(face, "det_score", 0.0)),
                "embedding": [float(v) for v in embedding] if embedding is not None else None,
                "yaw": yaw,
                "pitch": pitch,
                "backend": "insightface",
            }
        )
    return out


def _detect_haar(path: Path) -> list[dict]:
    import cv2

    cascade_path = Path(cv2.data.haarcascades) / "haarcascade_frontalface_default.xml"
    cascade = cv2.CascadeClassifier(str(cascade_path))
    image = cv2.imread(str(path))
    if image is None or cascade.empty():
        return []
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    found = cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5, minSize=(64, 64))
    return [
        {
            "bbox": (int(x), int(y), int(x + w), int(y + h)),
            "score": 0.5,
            "embedding": None,
            "yaw": None,
            "pitch": None,
            "backend": "haar",
        }
        for (x, y, w, h) in found
    ]
