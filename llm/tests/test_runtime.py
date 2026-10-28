"""The bridge runs from the shaded jar or the desktop thin jar with the configured Java; nothing launches for real."""

import os
import subprocess

import pytest

from llm import paths, runtime


@pytest.fixture
def fake_repo(tmp_path, monkeypatch):
    target = tmp_path / "target"
    target.mkdir()
    monkeypatch.setattr(paths, "BRIDGE_TARGET", target)
    monkeypatch.setattr(paths, "BRIDGE_HOME", tmp_path / "bridge")
    jdk = tmp_path / "jdk"
    (jdk / "bin").mkdir(parents=True)
    java = jdk / "bin" / ("java.exe" if os.name == "nt" else "java")
    java.write_text("")
    monkeypatch.setattr(paths, "settings", lambda: {"java_home": str(jdk)})
    monkeypatch.setattr(paths, "component", lambda: None)
    return target, java


def test_configured_java_home_wins_over_the_environment(fake_repo, monkeypatch):
    _, java = fake_repo
    monkeypatch.setenv("JAVA_HOME", "elsewhere")
    assert runtime.java() == str(java)


def test_java_home_without_java_is_an_error(fake_repo, tmp_path, monkeypatch):
    monkeypatch.setattr(paths, "settings", lambda: {"java_home": str(tmp_path / "empty")})
    with pytest.raises(RuntimeError, match="No java executable"):
        runtime.java()


def test_java_falls_back_to_path(monkeypatch):
    monkeypatch.setattr(paths, "settings", dict)
    monkeypatch.delenv("JAVA_HOME", raising=False)
    monkeypatch.setattr(runtime.shutil, "which", lambda name: f"/usr/bin/{name}")
    assert runtime.java() == "/usr/bin/java"


def test_only_the_release_jar_is_used(fake_repo):
    target, _ = fake_repo
    (target / "original-mage-client-bridge-1.4.62.jar").write_text("")
    (target / "mage-client-bridge-1.4.62-shaded.jar").write_text("")
    with pytest.raises(RuntimeError, match=r"build\.mjs bridge"):
        runtime.bridge_jar()
    shaded = target / "mage-client-bridge-1.4.62.jar"
    shaded.write_text("")
    assert runtime.bridge_jar() == shaded


def test_bridge_launches_with_java_jar_from_the_seat_folder(fake_repo, tmp_path, monkeypatch):
    target, java = fake_repo
    jar = target / "mage-client-bridge-1.4.62.jar"
    jar.write_text("")
    launched = {}

    def popen(command, **kwargs):
        launched.update(command=command, **kwargs)
        return "process"

    monkeypatch.setattr(runtime.subprocess, "Popen", popen)
    workdir = tmp_path / "table-seat-2" / "bridge"
    assert runtime.launch_bridge(["-Dxmage.bridge.seat=2"], {"PATH": "x"}, "log", workdir) == "process"
    assert launched["command"] == [str(java), "-Dxmage.bridge.seat=2", "-jar", str(jar)]
    # its card database (./db) belongs to this seat alone
    assert launched["cwd"] == workdir
    assert workdir.is_dir()
    assert launched["env"] == {"PATH": "x"}
    assert launched["stdout"] == "log"
    assert launched["stdin"] == subprocess.DEVNULL


def test_the_bridge_card_database_server_listens_on_loopback_only():
    assert "-Dh2.bindAddress=127.0.0.1" in paths.bridge_java_arguments()


def test_bridge_user_home_stays_in_the_run_folder():
    home = next(argument for argument in paths.bridge_java_arguments() if argument.startswith("-Duser.home="))
    assert home == f"-Duser.home={(paths.BRIDGE_HOME / 'home').as_posix()}"


def test_desktop_bridge_runs_its_thin_jar_on_the_bundled_server(fake_repo, tmp_path, monkeypatch):
    _, java = fake_repo
    server = tmp_path / "server"
    monkeypatch.setattr(paths, "REPO", tmp_path / "llm")
    monkeypatch.setattr(paths, "settings", lambda: {"java_home": str(java.parent.parent), "server_dir": str(server)})
    monkeypatch.setattr(
        paths,
        "component",
        lambda: {
            "bridge": {
                "jar": "bridge/mage-client-bridge.jar",
                "mainClass": "mage.client.bridge.BridgeClient",
                "serverClasspath": ["classes/Mage", "lib/*"],
            }
        },
    )
    launched = {}
    monkeypatch.setattr(runtime.subprocess, "Popen", lambda command, **_kwargs: launched.setdefault("command", command))
    runtime.launch_bridge(["-Dxmage.bridge.seat=2"], {}, "log", tmp_path / "seat")
    classpath = [
        tmp_path / "llm" / "bridge" / "mage-client-bridge.jar",
        server / "classes" / "Mage",
        server / "lib" / "*",
    ]
    assert launched["command"] == [
        str(java),
        "-Dxmage.bridge.seat=2",
        "-cp",
        os.pathsep.join(str(entry) for entry in classpath),
        "mage.client.bridge.BridgeClient",
    ]


def test_desktop_bridge_needs_the_launchers_server_folder(fake_repo, monkeypatch):
    monkeypatch.setattr(paths, "component", lambda: {"bridge": {}})
    with pytest.raises(RuntimeError, match="server folder"):
        runtime.bridge_main()
