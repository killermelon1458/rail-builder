#!/usr/bin/env python3
"""Run one disposable production-JAR experiment; no launcher, SSH or real profiles.

Uses official Mojang version JSON and Fabric profile JSON supplied by the caller.
Downloads only their declared libraries, verifies hashes when provided, and reuses
matching Gradle-cache artifacts. Requires JDK 21, Linux and a graphical desktop.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import zipfile


def digest(path, algorithm="sha256"):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def fetch(url, destination, sha1, gradle):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and sha1 and digest(destination, "sha1") == sha1:
        return destination
    if sha1:
        for cached in gradle.glob("caches/modules-2/files-2.1/*/*/*/" + sha1 + "/*.jar"):
            if digest(cached, "sha1") == sha1:
                shutil.copyfile(cached, destination)
                return destination
    temporary = destination.with_suffix(".part")
    with urllib.request.urlopen(url, timeout=30) as response, temporary.open("wb") as output:
        shutil.copyfileobj(response, output)
    if sha1 and digest(temporary, "sha1") != sha1:
        temporary.unlink()
        raise ValueError("Checksum mismatch: " + url)
    temporary.replace(destination)
    return destination


def allowed(library):
    rules = library.get("rules")
    if not rules:
        return True
    result = False
    for rule in rules:
        operating = rule.get("os", {})
        if operating.get("name", "linux") != "linux":
            continue
        if operating.get("arch", "amd64") not in ("x86_64", "amd64"):
            continue
        if rule.get("features"):
            continue
        result = rule["action"] == "allow"
    return result


def mod_info(path):
    with zipfile.ZipFile(path) as jar:
        metadata = json.loads(jar.read("fabric.mod.json"))
    return dict(path=str(path), sha256=digest(path), id=metadata["id"], version=metadata["version"],
                depends=metadata.get("depends", {}), custom=metadata.get("custom", {}))


def evaluate(text, exit_code):
    required = ["Initializing Meteor Client", "Initializing Linear Build Suite", "Path Manager: Baritone",
                "[RailBuilderCompatibility] PASS", "Stopping!"]
    markers = {marker: marker in text for marker in required}
    failures = [marker for marker in (
        "[RailBuilderCompatibility] FAIL", "NoClassDefFoundError", "NoSuchMethodError", "NoSuchFieldError",
        "Incompatible mods found!", "LinkageError", "AbstractMethodError", "IncompatibleClassChangeError",
        "UnsatisfiedLinkError", "IllegalAccessError", "VerifyError", "ExceptionInInitializerError", "BootstrapMethodError"
    ) if marker in text]
    return dict(markers=markers, failures=failures,
                startup_passed=exit_code == 0 and all(markers.values()) and not failures)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("minecraft-info", "loader-profile", "assets", "addon", "meteor", "baritone", "work-dir", "cache", "gradle-home"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--timeout", type=int, default=240)
    args = parser.parse_args()
    if args.timeout < 1:
        parser.error("timeout must be positive")
    # Refuse reuse: no real profile can be overwritten, and no stale log can pass.
    args.work_dir = args.work_dir.resolve()
    args.work_dir.mkdir(parents=True, exist_ok=False)
    profile = args.work_dir / "profile"
    mods = profile / "mods"
    mods.mkdir(parents=True)
    mc = json.loads(args.minecraft_info.read_text())
    loader = json.loads(args.loader_profile.read_text())
    if mc["id"] != "1.21.11" or loader["inheritsFrom"] != mc["id"]:
        raise ValueError("This probe is specifically mapped to Minecraft 1.21.11")
    inputs = [mod_info(path.resolve()) for path in (args.addon, args.meteor, args.baritone)]
    if [m["id"] for m in inputs] != ["rail-builder", "meteor-client", "baritone-meteor"]:
        raise ValueError("Wrong experiment input mod IDs")
    for index, path in enumerate((args.addon, args.meteor, args.baritone)):
        shutil.copyfile(path, mods / (str(index) + "-" + path.name))
    (profile / "options.txt").write_text("onboardAccessibility:false\nfullscreen:false\nguiScale:2\n")
    libraries = []
    for library in mc["libraries"]:
        if not allowed(library):
            continue
        artifact = library.get("downloads", {}).get("artifact")
        if artifact:
            libraries.append(fetch(artifact["url"], args.cache / artifact["path"], artifact.get("sha1"), args.gradle_home))
    for library in loader["libraries"]:
        group, name, version = library["name"].split(":")
        relative = "/".join((group.replace(".", "/"), name, version, name + "-" + version + ".jar"))
        libraries.append(fetch(library["url"] + relative, args.cache / relative, library.get("sha1"), args.gradle_home))
    client = mc["downloads"]["client"]
    libraries.append(fetch(client["url"], args.cache / "minecraft-1.21.11-client.jar", client["sha1"], args.gradle_home))
    classpath = os.pathsep.join(str(path.resolve()) for path in libraries)
    classes = args.work_dir / "probe-classes"
    classes.mkdir()
    subprocess.run(["javac", "--release", "21", "-proc:none", "-cp", classpath, "-d", str(classes),
                    str(Path(__file__).with_name("StartupProbe.java"))], check=True, timeout=30)
    probe = mods / "rail-builder-compatibility-probe.jar"
    with zipfile.ZipFile(probe, "w") as jar:
        jar.writestr("fabric.mod.json", json.dumps(dict(schemaVersion=1, id="railbuilder-compatibility-probe",
            version="1.0.0", environment="client", entrypoints={"client": ["railbuilder.compatibility.StartupProbe"]},
            depends={"minecraft": "1.21.11", "fabricloader": ">=0.18.2", "rail-builder": "*"})))
        for file in classes.rglob("*.class"):
            jar.write(file, file.relative_to(classes))
    command = ["java", "-Xmx2G", "-cp", classpath, loader["mainClass"], "--gameDir", str(profile),
               "--assetsDir", str(args.assets.resolve()), "--assetIndex", mc["assetIndex"]["id"],
               "--version", mc["id"], "--username", "CompatibilityTest", "--uuid", "00000000000000000000000000000001",
               "--accessToken", "0", "--userType", "legacy", "--width", "960", "--height", "600"]
    report = dict(minecraft=mc["id"], fabric_profile=loader["id"], inputs=inputs, timeout=args.timeout,
                  gameplay_validated=False, command=command)
    manifest = args.work_dir / "result.json"
    manifest.write_text(json.dumps(report, indent=2))
    output = args.work_dir / "client.log"
    try:
        with output.open("wb") as log:
            process = subprocess.run(command, cwd=profile, stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout)
        report["exit_code"] = process.returncode
    except subprocess.TimeoutExpired:
        report["timed_out"] = True
    text = output.read_text(errors="replace")
    report.update(evaluate(text, report.get("exit_code")))
    manifest.write_text(json.dumps(report, indent=2))
    print(json.dumps({k: v for k, v in report.items() if k != "command"}, indent=2))
    return 0 if report["startup_passed"] else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        sys.exit("ERROR: " + str(error))
