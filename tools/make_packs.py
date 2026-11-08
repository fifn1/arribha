"""Turns readable packs (packs-src/*.json: English -> Arabic) into published packs:
English lines become short fingerprints (same hash as the app), plus index.json with
per-game fingerprints of distinctive lines used to recognise the game.
Usage: python -I make_packs.py <packs-src dir> <output dir>"""
import hashlib, json, re, sys, pathlib

def key(s): return re.sub(r"[^a-z0-9]+", " ", s.lower()).strip()
def h(s): return hashlib.sha1(key(s).encode()).hexdigest()[:12]

src, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
(out / "packs").mkdir(parents=True, exist_ok=True)
index = []
for f in sorted(src.glob("*.json")):
    j = json.loads(f.read_text(encoding="utf-8"))
    gid = f.stem
    pack = {"id": gid, "game": j["game"], "version": j.get("version", 1),
            "names": j.get("names", {}), "lines": {h(en): ar for en, ar in j["lines"].items()}}
    (out / "packs" / f"{gid}.json").write_text(json.dumps(pack, ensure_ascii=False), encoding="utf-8")
    fp = [h(en) for en in j["lines"] if len(key(en).split()) >= 3][:40]   # distinctive lines only
    index.append({"id": gid, "game": j["game"], "file": f"packs/{gid}.json", "fp": fp})
    print(gid, len(pack["lines"]), "lines,", len(fp), "fingerprints")
(out / "index.json").write_text(json.dumps(index, ensure_ascii=False), encoding="utf-8")
