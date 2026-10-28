"""Start and stop the bridge process for one model seat."""

import json
import os
import re
import shutil
import subprocess
from pathlib import Path

import psutil

from llm import mcp_engine, paths

BRIDGE_JAR = re.compile(r"mage-client-bridge-[0-9.]+\.jar")


def save_json(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def stop_tree(proc):
    if proc.poll() is not None:
        return
    try:
        parent = psutil.Process(proc.pid)
    except psutil.NoSuchProcess:
        return
    descendants = parent.children(recursive=True)
    for child in reversed(descendants):
        try:
            child.terminate()
        except psutil.NoSuchProcess:
            pass
    try:
        parent.terminate()
    except psutil.NoSuchProcess:
        pass
    _, alive = psutil.wait_procs([*descendants, parent], timeout=5)
    for child in alive:
        try:
            child.kill()
        except psutil.NoSuchProcess:
            pass


def java():
    """The configured JDK, then JAVA_HOME, then java on PATH; the bridge needs Java 17 or later."""
    java_home = paths.settings().get("java_home") or os.environ.get("JAVA_HOME")
    if java_home:
        executable = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if not executable.is_file():
            raise RuntimeError(f"No java executable in {java_home}")
        return str(executable)
    found = shutil.which("java")
    if not found:
        raise RuntimeError("Java 17 or later is required: set JAVA_HOME or rerun python -m llm.configure --java-home")
    return found


def bridge_jar():
    # The shaded jar named after the release; maven-shade keeps the unshaded one as original-*.jar.
    jars = sorted(
        (jar for jar in paths.BRIDGE_TARGET.glob("mage-client-bridge-*.jar") if BRIDGE_JAR.fullmatch(jar.name)),
        key=lambda jar: jar.stat().st_mtime,
    )
    if not jars:
        raise RuntimeError("The bridge is not built; run node scripts/build.mjs bridge")
    return jars[-1]


def bridge_main():
    """The desktop component's thin bridge jar on the bundled server's classes, or the shaded jar of a checkout."""
    component = paths.component()
    if component is None:
        return ["-jar", str(bridge_jar())]
    bridge = component["bridge"]
    server_dir = paths.settings().get("server_dir")
    if not server_dir:
        raise RuntimeError("The launcher did not record its server folder; restart XMage Nexus")
    classpath = [paths.REPO / bridge["jar"], *(Path(server_dir) / entry for entry in bridge["serverClasspath"])]
    return ["-cp", os.pathsep.join(str(entry) for entry in classpath), bridge["mainClass"]]


def launch_bridge(arguments, env, log_file, workdir):
    # The card database is ./db under the working folder, so each seat gets its own: two
    # processes opening one H2 file at the same moment can lose the race and fail to join.
    workdir.mkdir(parents=True, exist_ok=True)
    return subprocess.Popen(
        [java(), *arguments, *bridge_main()],
        cwd=workdir,
        env=env,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )


def launch_mcp(env, log_file):
    return mcp_engine.launch(env, log_file)
