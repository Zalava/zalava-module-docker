#!/usr/bin/env python3
"""Maintain module-owned immutable releases; the catalog only locates this file."""
import argparse
import hashlib
import io
import json
import re
import urllib.request
import zipfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
COMMIT = re.compile(r"[0-9a-f]{40}")


def metadata(payload):
    with zipfile.ZipFile(io.BytesIO(payload)) as jar:
        document = yaml.safe_load(jar.read("module-metadata.yaml"))
        modules = document["modules"]
        if len(modules) != 1:
            raise ValueError("release must declare exactly one module")
        module = modules[0]
        descriptor = jar
        if "module.jar" in jar.namelist():
            descriptor = zipfile.ZipFile(io.BytesIO(jar.read("module.jar")))
        try:
            properties = descriptor.read("module.properties").decode()
            if not descriptor.read("META-INF/services/org.zalava.api.ZalavaModule").strip():
                raise ValueError("missing current SDK service registration")
            if any(n.startswith("org/zalava/api/") and n.endswith(".class")
                   for n in descriptor.namelist()):
                raise ValueError("release bundles the host SDK")
        finally:
            if descriptor is not jar:
                descriptor.close()
        version = module["version"]
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?", version):
            raise ValueError("invalid immutable version")
        if not (f"module.version={version}" in properties.splitlines()
                or f"version={version}" in properties.splitlines()):
            raise ValueError("descriptor version does not match metadata")
        artifact = module["artifact"]
        if artifact["version"] != version or artifact["artifactId"] != module["moduleId"]:
            raise ValueError("artifact coordinates do not match module identity")
        return module, "module.jar" in jar.namelist()


def release(payload, repository, tag, revision):
    if not COMMIT.fullmatch(revision):
        raise ValueError("source revision must be a full immutable commit")
    module, bundle = metadata(payload)
    version = module["version"]
    if tag != "v" + version:
        raise ValueError("tag does not match released artifact version")
    expected_repository = "https://github.com/Zalava/" + module["moduleId"]
    if repository != expected_repository or module["source"]["repository"] != repository + ".git":
        raise ValueError("source repository does not match released module")
    permissions = module["security"]["permissions"]
    if not isinstance(permissions, list) or any(not isinstance(p, str) or not p for p in permissions):
        raise ValueError("security permissions must be an explicit list")
    license_name = module["source"]["license"]
    if not isinstance(license_name, str) or not license_name.strip():
        raise ValueError("source license is required")
    compatibility = module["compatibility"]["zalavaRuntime"]
    if not isinstance(compatibility, str) or not compatibility.strip():
        raise ValueError("Zalava runtime compatibility is required")
    return module["moduleId"], {
        "version": version, "releaseTag": tag,
        "artifact": {**module["artifact"], "sha256": hashlib.sha256(payload).hexdigest(),
                     "type": "github-release-assets", "repositoryUri": repository,
                     "releaseTag": tag, "assetName": f'{module["moduleId"]}-{version}.jar'},
        "artifactBundle": bundle,
        "source": {"repository": repository + ".git", "revision": revision, "license": license_name},
        "compatibility": {"zalavaRuntime": compatibility},
        "security": {"permissions": permissions},
    }


def append(index, module_id, entry):
    if index is None:
        index = {"schemaVersion": 1, "moduleId": module_id, "releases": []}
    if index["schemaVersion"] != 1 or index["moduleId"] != module_id:
        raise ValueError("release index identity mismatch")
    for existing in index["releases"]:
        if existing["version"] == entry["version"]:
            if existing != entry:
                raise ValueError("immutable release cannot be rewritten")
            return index
    index["releases"].insert(0, entry)
    return index


def verify(index):
    if index["schemaVersion"] != 1 or not index["releases"]:
        raise ValueError("invalid release index")
    versions = set()
    for entry in index["releases"]:
        if entry["version"] in versions:
            raise ValueError("duplicate immutable release")
        versions.add(entry["version"])
        artifact = entry["artifact"]
        repository = artifact["repositoryUri"]
        if repository != "https://github.com/Zalava/" + index["moduleId"]:
            raise ValueError("untrusted module repository")
        if artifact["assetName"] != f'{index["moduleId"]}-{entry["version"]}.jar':
            raise ValueError("invalid asset name")
        if entry["releaseTag"] != "v" + entry["version"]:
            raise ValueError("invalid release tag")
        url = f'{repository}/releases/download/{entry["releaseTag"]}/{artifact["assetName"]}'
        with urllib.request.urlopen(url, timeout=60) as response:
            payload = response.read(100 * 1024 * 1024 + 1)
        if len(payload) > 100 * 1024 * 1024:
            raise ValueError("release asset exceeds size limit")
        # Resolve the immutable tag from the actual module source, rather than
        # trusting the index's claimed source revision.
        import subprocess
        revision = subprocess.check_output(
            ["git", "rev-parse", entry["releaseTag"] + "^{commit}"], cwd=ROOT, text=True).strip()
        subprocess.run(
            ["git", "merge-base", "--is-ancestor", revision, "origin/main"],
            cwd=ROOT, check=True)
        module_id, expected = release(payload, repository, entry["releaseTag"], revision)
        if module_id != index["moduleId"] or expected != entry:
            raise ValueError("release index disagrees with immutable artifact/source")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index", type=Path, default=ROOT / "releases/index.yaml")
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("--tag")
    parser.add_argument("--revision")
    parser.add_argument("--repository", default="https://github.com/Zalava/" + ROOT.name)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    index = json.loads(args.index.read_text()) if args.index.exists() else None
    if args.verify:
        if index is None:
            raise ValueError("maintained release index is missing")
        verify(index)
    else:
        if args.artifact is None or args.tag is None or args.revision is None:
            parser.error("generation requires --artifact, --tag and --revision")
        module_id, entry = release(args.artifact.read_bytes(), args.repository, args.tag, args.revision)
        index = append(index, module_id, entry)
        args.index.parent.mkdir(parents=True, exist_ok=True)
        args.index.write_text(json.dumps(index, indent=2) + "\n")


if __name__ == "__main__":
    main()
