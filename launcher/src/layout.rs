use crate::manifest::ComponentsManifest;
use std::io;
use std::path::{Path, PathBuf};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum LayoutError {
    #[error("io error: {0}")]
    Io(#[from] io::Error),
}

pub const INSTALLED_MARKER: &str = ".installed";

#[derive(Debug, Clone)]
pub struct Paths {
    pub root: PathBuf,
    pub components: PathBuf,
    pub data: PathBuf,
    pub logs: PathBuf,
}

pub fn resolve(data_root: &Path) -> Paths {
    Paths {
        root: data_root.to_path_buf(),
        components: data_root.join("components"),
        data: data_root.join("data"),
        logs: data_root.join("logs"),
    }
}

pub fn release_dir(paths: &Paths, release: &str) -> PathBuf {
    paths.components.join(release)
}

pub fn is_installed(paths: &Paths, release: &str) -> bool {
    release_dir(paths, release).join(INSTALLED_MARKER).is_file()
}

pub fn mark_installed(paths: &Paths, release: &str) -> Result<(), LayoutError> {
    let dir = release_dir(paths, release);
    std::fs::create_dir_all(&dir)?;
    std::fs::write(dir.join(INSTALLED_MARKER), release)?;
    Ok(())
}

pub fn missing_components(
    manifest: &ComponentsManifest,
    target: &str,
    paths: &Paths,
) -> Vec<String> {
    if is_installed(paths, &manifest.release) {
        return Vec::new();
    }
    manifest
        .component_names()
        .into_iter()
        .filter(|name| manifest.get(name, target).is_some())
        .collect()
}

pub fn component_archive(paths: &Paths, release: &str, component: &str) -> PathBuf {
    release_dir(paths, release).join(format!("{component}.tar.gz"))
}

pub fn component_dir(paths: &Paths, release: &str, component: &str) -> PathBuf {
    release_dir(paths, release).join(component)
}

pub fn server_data_dir(paths: &Paths) -> PathBuf {
    paths.data.join("server")
}

pub fn llm_data_dir(paths: &Paths) -> PathBuf {
    paths.data.join("llm")
}

#[derive(Debug, serde::Deserialize)]
struct LlmComponent {
    python: String,
}

pub fn llm_proxy_args(paths: &Paths, release: &str) -> Result<Vec<String>, String> {
    let component = component_dir(paths, release, "llm");
    let Ok(description) = std::fs::read_to_string(component.join("llm.json")) else {
        return Ok(Vec::new());
    };
    let llm: LlmComponent =
        serde_json::from_str(&description).map_err(|e| format!("llm.json: {e}"))?;
    let python = llm
        .python
        .split('/')
        .fold(component.clone(), |path, part| path.join(part));
    if !python.is_file() {
        return Err(format!("missing {}", python.display()));
    }
    let data = llm_data_dir(paths);
    std::fs::create_dir_all(&data).map_err(|e| format!("llm data dir: {e}"))?;
    let desktop = serde_json::json!({
        "java_home": component_dir(paths, release, "jre").display().to_string(),
        "server_dir": component_dir(paths, release, "server").display().to_string(),
    });
    std::fs::write(data.join("desktop.json"), format!("{desktop:#}\n"))
        .map_err(|e| format!("desktop.json: {e}"))?;
    Ok(vec![
        "--llm".to_string(),
        "on".to_string(),
        "--llmRoot".to_string(),
        component.display().to_string(),
        "--llmPython".to_string(),
        python.display().to_string(),
        "--llmData".to_string(),
        data.display().to_string(),
    ])
}

pub fn seed_server_data(staging_server: &Path, data_server: &Path) -> Result<(), LayoutError> {
    for sub in ["saved", "gamesHistory"] {
        std::fs::create_dir_all(data_server.join(sub))?;
    }
    let seed = staging_server.join("config");
    if seed.is_dir() {
        let dest = data_server.join("config");
        if !dest.is_dir() {
            copy_dir(&seed, &dest)?;
        }
    }
    let staging_plugins = staging_server.join("plugins");
    if staging_plugins.is_dir() {
        let dest = data_server.join("plugins");
        let _ = std::fs::remove_dir_all(&dest);
        copy_dir(&staging_plugins, &dest)?;
    }
    Ok(())
}

/// Where the copy of the game page that friends open in their browser lives.
pub fn friends_web_dir(paths: &Paths) -> PathBuf {
    paths.root.join("friends-web")
}

/// Hosting is on when the server config accepts other machines (`serverAddress` is not a
/// loopback address): that is the step a host takes to let friends in, so the proxy friends
/// open in a browser runs only then and a normal install opens nothing new.
pub fn server_open_to_network(config_xml: &Path) -> bool {
    let Ok(text) = std::fs::read_to_string(config_xml) else {
        return false;
    };
    let Some(start) = text.find("serverAddress=\"") else {
        return false;
    };
    let value = &text[start + 15..];
    let address = value[..value.find('"').unwrap_or(value.len())].trim();
    !matches!(address, "" | "127.0.0.1" | "localhost" | "::1")
}

/// The proxy friends reach: every address of this machine, the copied page, logins only to the
/// local server and never model seats (it gets no `--llm`, and the proxy refuses the two together).
pub fn friends_proxy_args(web_dir: &Path, server_port: u16) -> Vec<String> {
    vec![
        "--bind".to_string(),
        "0.0.0.0".to_string(),
        "--webDir".to_string(),
        web_dir.display().to_string(),
        "--friendsServer".to_string(),
        format!("127.0.0.1:{server_port}"),
    ]
}

const FRIENDS_PAGE_MARKER: &str = ".complete";

/// The copy is current when it was finished for this app version and its `index.html` (which
/// names the fingerprinted bundles) is the one the app carries.
pub fn friends_page_current(dir: &Path, version: &str, index_html: &[u8]) -> bool {
    std::fs::read_to_string(dir.join(FRIENDS_PAGE_MARKER)).is_ok_and(|v| v == version)
        && std::fs::read(dir.join("index.html")).is_ok_and(|b| b == index_html)
}

/// Writes the page files (paths as the app's bundle names them, `/assets/x.js`) into a fresh
/// `dir`. A name that would leave `dir` is skipped. The marker is written last, so a copy cut
/// short is redone on the next start.
pub fn write_friends_page(
    dir: &Path,
    version: &str,
    files: impl IntoIterator<Item = (String, Vec<u8>)>,
) -> Result<(), LayoutError> {
    if dir.exists() {
        std::fs::remove_dir_all(dir)?;
    }
    std::fs::create_dir_all(dir)?;
    for (name, bytes) in files {
        let parts: Vec<&str> = name.split('/').filter(|p| !p.is_empty()).collect();
        let safe = !parts.is_empty()
            && parts
                .iter()
                .all(|p| *p != "." && *p != ".." && !p.contains(['\\', ':']));
        if !safe {
            continue;
        }
        let path = parts.iter().fold(dir.to_path_buf(), |path, part| path.join(part));
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(path, bytes)?;
    }
    std::fs::write(dir.join(FRIENDS_PAGE_MARKER), version)?;
    Ok(())
}

fn copy_dir(src: &Path, dest: &Path) -> Result<(), LayoutError> {
    std::fs::create_dir_all(dest)?;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let from = entry.path();
        let to = dest.join(entry.file_name());
        if from.is_dir() {
            copy_dir(&from, &to)?;
        } else {
            std::fs::copy(&from, &to)?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_manifest() -> ComponentsManifest {
        ComponentsManifest::parse(
            r#"{"schema":1,"release":"r1","xmage":"x","components":{
                "server": {"t1": {"url": "file:///s", "sha256": "aa", "bytes": 1}},
                "proxy": {"t1": {"url": "file:///p", "sha256": "bb", "bytes": 2}}}}"#,
        )
        .unwrap()
    }

    #[test]
    fn install_lifecycle() {
        let dir = tempfile::tempdir().unwrap();
        let paths = resolve(dir.path());
        let m = sample_manifest();
        assert_eq!(missing_components(&m, "t1", &paths), vec!["proxy", "server"]);
        assert_eq!(missing_components(&m, "other", &paths), Vec::<String>::new());
        mark_installed(&paths, "r1").unwrap();
        assert!(is_installed(&paths, "r1"));
        assert!(missing_components(&m, "t1", &paths).is_empty());
    }

    #[test]
    fn llm_component_turns_on_model_seats_with_the_bundled_java_and_server() {
        let dir = tempfile::tempdir().unwrap();
        let paths = resolve(dir.path());
        assert!(llm_proxy_args(&paths, "r1").unwrap().is_empty());
        let component = component_dir(&paths, "r1", "llm");
        std::fs::create_dir_all(component.join("python")).unwrap();
        std::fs::write(
            component.join("llm.json"),
            r#"{"python": "python/python.exe", "bridge": {}}"#,
        )
        .unwrap();
        assert!(llm_proxy_args(&paths, "r1")
            .unwrap_err()
            .contains("python.exe"));
        let python = component.join("python").join("python.exe");
        std::fs::write(&python, "").unwrap();
        let data = llm_data_dir(&paths);
        assert_eq!(
            llm_proxy_args(&paths, "r1").unwrap(),
            vec![
                "--llm".to_string(),
                "on".to_string(),
                "--llmRoot".to_string(),
                component.display().to_string(),
                "--llmPython".to_string(),
                python.display().to_string(),
                "--llmData".to_string(),
                data.display().to_string(),
            ]
        );
        let text = std::fs::read_to_string(data.join("desktop.json")).unwrap();
        let desktop: serde_json::Value = serde_json::from_str(&text).unwrap();
        let jre = component_dir(&paths, "r1", "jre");
        let server = component_dir(&paths, "r1", "server");
        assert_eq!(desktop["java_home"], jre.display().to_string());
        assert_eq!(desktop["server_dir"], server.display().to_string());
    }

    #[test]
    fn seeds_config_once_and_creates_dirs() {
        let dir = tempfile::tempdir().unwrap();
        let staging = dir.path().join("staging");
        std::fs::create_dir_all(staging.join("config")).unwrap();
        std::fs::write(staging.join("config").join("config.xml"), "<c/>").unwrap();
        std::fs::create_dir_all(staging.join("plugins")).unwrap();
        std::fs::write(staging.join("plugins").join("p-1.jar"), "v1").unwrap();
        let data = dir.path().join("data").join("server");
        seed_server_data(&staging, &data).unwrap();
        assert!(data.join("saved").is_dir());
        assert!(data.join("gamesHistory").is_dir());
        assert_eq!(
            std::fs::read(data.join("config").join("config.xml")).unwrap(),
            b"<c/>"
        );
        assert_eq!(
            std::fs::read(data.join("plugins").join("p-1.jar")).unwrap(),
            b"v1"
        );
        std::fs::write(data.join("config").join("config.xml"), "<user/>").unwrap();
        std::fs::write(staging.join("plugins").join("p-1.jar"), "v2").unwrap();
        seed_server_data(&staging, &data).unwrap();
        assert_eq!(
            std::fs::read(data.join("config").join("config.xml")).unwrap(),
            b"<user/>"
        );
        assert_eq!(
            std::fs::read(data.join("plugins").join("p-1.jar")).unwrap(),
            b"v2"
        );
    }

    #[test]
    fn hosting_is_on_only_when_the_server_accepts_other_machines() {
        let dir = tempfile::tempdir().unwrap();
        let config = dir.path().join("config.xml");
        assert!(!server_open_to_network(&config));
        for (address, open) in [
            ("127.0.0.1", false),
            ("localhost", false),
            ("", false),
            ("0.0.0.0", true),
            ("26.10.20.30", true),
        ] {
            std::fs::write(
                &config,
                format!("<config>\n  <server serverAddress=\"{address}\" port=\"17171\"/>\n</config>"),
            )
            .unwrap();
            assert_eq!(server_open_to_network(&config), open, "{address}");
        }
    }

    #[test]
    fn the_friends_proxy_serves_the_page_and_logs_in_only_to_this_server() {
        let web = Path::new("C:/data/friends-web");
        assert_eq!(
            friends_proxy_args(web, 17171),
            vec![
                "--bind".to_string(),
                "0.0.0.0".to_string(),
                "--webDir".to_string(),
                web.display().to_string(),
                "--friendsServer".to_string(),
                "127.0.0.1:17171".to_string(),
            ]
        );
        assert!(!friends_proxy_args(web, 17171).iter().any(|a| a.starts_with("--llm")));
    }

    #[test]
    fn friends_page_is_copied_once_per_build_and_stays_inside_its_folder() {
        let root = tempfile::tempdir().unwrap();
        let paths = resolve(root.path());
        let dir = friends_web_dir(&paths);
        let index = b"<script src=./assets/index-a.js></script>".to_vec();
        assert!(!friends_page_current(&dir, "0.5.2", &index));
        write_friends_page(
            &dir,
            "0.5.2",
            vec![
                ("/index.html".to_string(), index.clone()),
                ("/assets/index-a.js".to_string(), b"js".to_vec()),
                ("/../escape.txt".to_string(), b"no".to_vec()),
                ("/assets/c:evil".to_string(), b"no".to_vec()),
            ],
        )
        .unwrap();
        assert_eq!(std::fs::read(dir.join("assets").join("index-a.js")).unwrap(), b"js");
        assert!(!root.path().join("escape.txt").exists());
        assert!(!dir.join("escape.txt").exists());
        assert!(friends_page_current(&dir, "0.5.2", &index));
        assert!(!friends_page_current(&dir, "0.5.3", &index), "another app version");
        assert!(!friends_page_current(&dir, "0.5.2", b"<other build>"), "another web build");

        write_friends_page(&dir, "0.5.3", vec![("/index.html".to_string(), index.clone())]).unwrap();
        assert!(!dir.join("assets").exists(), "files of the old build are gone");
    }
}
