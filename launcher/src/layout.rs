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
}
