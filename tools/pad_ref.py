#!/usr/bin/env python3
"""Reference for relay envelope padding (docs/protocol-0.6.md section 20).

The outer wrapping {"e":..,"n":..,"d":..} gets a field "z" of zeros so that its base64 (the Nostr event's
content) is exactly one of a few sizes: 1024, 4096, 16384, 32768 characters, then steps of 16384.
Writes tests/pad-vectors.json and the Android test copy.
"""
import base64
import json
import os

FIXED = (1024, 4096, 16384, 32768)
STEP = 16384


def bucket(n):
    for b in FIXED:
        if n <= b:
            return b
    return (n + STEP - 1) // STEP * STEP


def b64len(n):
    return (n + 2) // 3 * 4


def pad(js):
    body = js.rstrip()
    assert body.endswith("}")
    base = len(body.encode()) + 7          # ,"z":"" adds 7 characters
    target = bucket(b64len(base)) // 4 * 3
    return body[:-1] + ',"z":"' + "0" * (target - base) + '"}'


def verify(v):
    for c in v["bucket"]:
        assert bucket(c["n"]) == c["bucket"], c
    for c in v["pad"]:
        js = wrapping(c["d"])
        out = pad(js)
        content = base64.b64encode(out.encode()).decode()
        assert len(content) == c["content"], c
        assert len(json.loads(out)["z"]) == c["zeros"], c
        assert len(content) == bucket(len(content))
        assert out.startswith(js[:-1]) and set(json.loads(out)) - {"z"} == set(json.loads(js)), "padding only adds z"


def wrapping(d_len):
    """The test wrapping: {"e":"AAA..(60)","n":"BBB..(16)","d":"CCC..(d_len)"}, compact."""
    return json.dumps({"e": "A" * 60, "n": "B" * 16, "d": "C" * d_len}, separators=(",", ":"))


def main():
    sizes = [0, 1, 100, 1017, 1018, 1024, 1025, 4089, 4090, 4096, 16384, 16385, 32768, 32769, 49152, 49153, 70000]
    v = {
        "fixed": list(FIXED),
        "step": STEP,
        "bucket": [{"n": n, "bucket": bucket(n)} for n in sizes],
        "pad": [],
    }
    # The exact edge: a wrapping whose base64 with an empty "z" is exactly 1024 characters, and one more.
    edge = 768 - 7 - len(wrapping(0).encode())
    for d in (0, 10, 600, 700, edge, edge + 1, 2900, 3000, 12000, 24000, 31000, 36000, 50000):
        out = pad(wrapping(d))
        v["pad"].append({"d": d, "zeros": len(json.loads(out)["z"]), "content": len(base64.b64encode(out.encode()))})
    verify(v)
    here = os.path.dirname(os.path.abspath(__file__))
    for path in (os.path.join(here, "..", "tests", "pad-vectors.json"),
                 os.path.join(here, "..", "android", "app", "src", "test", "resources", "pad-vectors.json")):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(v, f, indent=1, ensure_ascii=False)
            f.write("\n")
        with open(path, encoding="utf-8") as f:
            verify(json.load(f))
        print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    main()
