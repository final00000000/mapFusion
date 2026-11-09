"""Inventory public/protected JVM symbols from the exact Gradle-resolved SDKs.

No vendor code is executed. AAR classes.jar and libs/*.jar are inspected directly.
The output is a review backlog, never an inferred wrapper coverage percentage.
Usage: python tools/official_api_inventory.py
Run :app:exportOfficialSdkArtifacts first. Python standard library only.
"""
import argparse
import csv
import hashlib
import io
import json
import struct
import zipfile
from collections import Counter
from pathlib import Path

PREFIXES = (
    "com/amap/api/maps/", "com/amap/api/location/", "com/amap/api/fence/",
    "com/amap/api/services/", "com/amap/api/navi/", "com/amap/api/trace/",
    "com/baidu/mapapi/", "com/baidu/location/", "com/baidu/navisdk/adapter/",
    "com/baidu/mapframework/walknavi/", "com/baidu/mapframework/bikenavi/",
    "com/baidu/lbsapi/", "com/baidu/walknavi/", "com/baidu/bikenavi/",
)
ARTIFACTS = {
    "3dmap-location-search", "navi-3dmap-location-search", "BaiduMapSDK_Map",
    "BaiduMapSDK_Map-AllNavi", "BaiduMapSDK_Search", "BaiduMapSDK_Location_All",
    "base", "bwnavi", "carnavi",
}


class ClassFile:
    def __init__(self, data):
        self.data = io.BytesIO(data)
        if self.read(4) != b"\xca\xfe\xba\xbe":
            raise ValueError("Invalid class file")
        self.read(4)
        self.pool = [None] * self.u2()
        index = 1
        while index < len(self.pool):
            tag = self.u1()
            if tag == 1:
                self.pool[index] = self.read(self.u2()).decode("utf-8", errors="replace")
            elif tag in (7, 8, 16, 19, 20):
                self.pool[index] = self.u2()
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
                self.read(4)
            elif tag in (5, 6):
                self.read(8)
                index += 1
            elif tag == 15:
                self.read(3)
            else:
                raise ValueError(f"Unknown constant-pool tag: {tag}")
            index += 1

    def read(self, size):
        result = self.data.read(size)
        if len(result) != size:
            raise ValueError("Truncated class file")
        return result

    def u1(self):
        return self.read(1)[0]

    def u2(self):
        return struct.unpack(">H", self.read(2))[0]

    def u4(self):
        return struct.unpack(">I", self.read(4))[0]

    def attributes(self):
        return [(self.pool[self.u2()], self.read(self.u4())) for _ in range(self.u2())]

    def symbols(self):
        access, this_class = self.u2(), self.u2()
        owner = self.pool[self.pool[this_class]]
        self.u2()  # superclass
        self.read(2 * self.u2())
        members = []
        for kind in ("field", "method"):
            for _ in range(self.u2()):
                flags, name, descriptor = self.u2(), self.u2(), self.u2()
                attributes = self.attributes()
                if flags & 0x0005 and not flags & 0x1000:  # public/protected, non-synthetic
                    members.append((kind, self.pool[name], self.pool[descriptor],
                                    any(key == "Deprecated" for key, _ in attributes)))
        for key, value in self.attributes():
            if key == "InnerClasses":
                count = struct.unpack_from(">H", value)[0]
                for i in range(count):
                    inner, _, _, flags = struct.unpack_from(">HHHH", value, 2 + i * 8)
                    if inner == this_class:
                        access = flags
        if not access & 0x0005 or access & 0x1000:
            return []
        owner = owner.replace("/", ".")
        return [(owner, "type", "", "", False)] + [(owner, *member) for member in members]


def inspect_jar(data):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        for entry in sorted(jar.namelist()):
            if entry.endswith(".class") and entry.startswith(PREFIXES):
                yield from ClassFile(jar.read(entry)).symbols()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", default="app/build/reports/official-api/artifacts.tsv")
    parser.add_argument("--output", default="build/reports/official-api")
    parser.add_argument("--mappings", default="docs/official-api-mappings.csv")
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    with open(args.artifacts, encoding="utf-8-sig", newline="") as source:
        artifacts = list(csv.DictReader(source, delimiter="\t"))
    with open(args.mappings, encoding="utf-8-sig", newline="") as source:
        mappings = {(row["owner"], row["member"], row["descriptor"]): row for row in csv.DictReader(source)}
    cache, counts, matched, manifest = {}, Counter(), set(), []
    with (output / "symbols.csv").open("w", encoding="utf-8-sig", newline="") as target:
        fields = ["variant", "coordinate", "owner", "kind", "member", "descriptor", "deprecated",
                  "status", "entrypoint", "demo", "verification"]
        writer = csv.DictWriter(target, fieldnames=fields)
        writer.writeheader()
        for artifact in artifacts:
            if artifact["coordinate"].split(":")[1] not in ARTIFACTS:
                continue
            path = Path(artifact["file"])
            if path not in cache:
                data = path.read_bytes()
                digest = hashlib.sha256(data).hexdigest()
                if path.suffix == ".aar":
                    with zipfile.ZipFile(io.BytesIO(data)) as aar:
                        jars = [aar.read(name) for name in aar.namelist()
                                if name == "classes.jar" or (name.startswith("libs/") and name.endswith(".jar"))]
                else:
                    jars = [data]
                cache[path] = (sorted(set(symbol for jar in jars for symbol in inspect_jar(jar))), digest)
            symbols, digest = cache[path]
            manifest.append({"variant": artifact["variant"], "coordinate": artifact["coordinate"],
                             "sha256": digest, "symbols": len(symbols)})
            for owner, kind, member, descriptor, deprecated in symbols:
                key = (owner, member, descriptor)
                mapping = mappings.get(key, {})
                if mapping:
                    matched.add(key)
                status = mapping.get("status", "UNREVIEWED")
                counts[(artifact["variant"], status)] += 1
                writer.writerow(dict(variant=artifact["variant"], coordinate=artifact["coordinate"],
                    owner=owner, kind=kind, member=member, descriptor=descriptor, deprecated=deprecated,
                    status=status, entrypoint=mapping.get("entrypoint", ""), demo=mapping.get("demo", ""),
                    verification=mapping.get("verification", "NOT_VERIFIED")))
    summary = {"note": "Public JVM surface in listed namespaces, including undocumented public types. "
               "UNREVIEWED is a backlog, not unsupported. No coverage percentage is inferred.",
               "prefixes": PREFIXES, "artifacts": manifest,
               "counts": [{"variant": variant, "status": status, "count": count}
                          for (variant, status), count in sorted(counts.items())],
               "unmatchedMappings": [list(key) for key in sorted(mappings.keys() - matched)]}
    (output / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"counts": summary["counts"], "unmatchedMappings": summary["unmatchedMappings"]}, ensure_ascii=False))
    if summary["unmatchedMappings"]:
        raise SystemExit("Mapping does not match the resolved SDK. Review signatures before claiming coverage.")


if __name__ == "__main__":
    main()
