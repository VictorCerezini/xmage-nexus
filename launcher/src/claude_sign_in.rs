//! LBY-33: the window the Create Table screen's "Sign in" opens for Claude. It runs Claude Code's
//! subscription sign-in (`auth login --claudeai`) the way model seats run Claude Code, so the login lands
//! where they look, then says what to do next and stays open until Enter.

use std::path::PathBuf;

/// The variables model seats drop before they run Claude Code (`llm/claude_client.py`, CREDENTIAL_OVERRIDES).
/// CLAUDE_CONFIG_DIR matters most: one set for the terminal's Claude Code would take the login to a folder
/// model seats never read.
pub const DROPPED_ENV: &[&str] = &[
    "CLAUDE_CONFIG_DIR",
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
    "ANTHROPIC_BASE_URL",
    "ANTHROPIC_MODEL",
    "ANTHROPIC_CUSTOM_HEADERS",
    "CLAUDE_CODE_OAUTH_TOKEN",
    "CLAUDE_CODE_USE_BEDROCK",
    "CLAUDE_CODE_USE_VERTEX",
    "CLAUDE_CODE_USE_FOUNDRY",
    "CLAUDE_CODE_USE_GATEWAY",
    "CLAUDE_CODE_USE_MANTLE",
];

/// Carries the program's path into the window's script, so no path is ever written into the script itself.
pub const PROGRAM_ENV: &str = "NEXUS_CLAUDE_SIGN_IN";

/// What the window runs (PowerShell).
pub const SCRIPT: &str = "$Host.UI.RawUI.WindowTitle = 'XMage Nexus: sign in to Claude'\n\
& $env:NEXUS_CLAUDE_SIGN_IN auth login --claudeai\n\
Write-Host ''\n\
if ($LASTEXITCODE -eq 0) { Write-Host 'Signed in. Close this window and press Refresh models in XMage Nexus.' } \
else { Write-Host 'The sign-in did not finish. Close this window and press Sign in again.' }\n\
Read-Host 'Press Enter to close' | Out-Null\n";

/// The page names the program the model service found. Only an existing file named `claude.exe` is accepted,
/// so the page cannot open a window for anything else.
pub fn claude_program(program: &str) -> Result<PathBuf, String> {
    let path = PathBuf::from(program);
    let named_claude = path
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name.eq_ignore_ascii_case("claude.exe"));
    if !path.is_absolute() || !named_claude {
        return Err("this is not Claude Code".into());
    }
    if !path.is_file() {
        return Err(format!("Claude Code is not at {}", path.display()));
    }
    Ok(path)
}

/// cmd.exe's command line: `start` gives PowerShell a console window of its own, and the script travels as
/// -EncodedCommand (base64 of UTF-16LE), so cmd parses nothing in it.
pub fn start_command_line() -> String {
    format!("/c start \"\" powershell.exe -NoProfile -NoLogo -EncodedCommand {}", encoded_command(SCRIPT))
}

/// PowerShell's -EncodedCommand form of a script.
pub fn encoded_command(script: &str) -> String {
    let bytes: Vec<u8> = script.encode_utf16().flat_map(u16::to_le_bytes).collect();
    base64(&bytes)
}

fn base64(bytes: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let byte = |i: usize| u32::from(chunk.get(i).copied().unwrap_or(0));
        let triple = (byte(0) << 16) | (byte(1) << 8) | byte(2);
        for i in 0..4 {
            if i <= chunk.len() {
                out.push(char::from(TABLE[((triple >> (18 - 6 * i)) & 63) as usize]));
            } else {
                out.push('=');
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base64_matches_the_standard_vectors() {
        let cases = [("", ""), ("f", "Zg=="), ("fo", "Zm8="), ("foo", "Zm9v"), ("foob", "Zm9vYg=="), ("foobar", "Zm9vYmFy")];
        for (plain, encoded) in cases {
            assert_eq!(base64(plain.as_bytes()), encoded, "{plain:?}");
        }
    }

    #[test]
    fn the_script_is_utf16_little_endian_as_powershell_reads_it() {
        assert_eq!(encoded_command("a"), "YQA=");
        assert_eq!(encoded_command("é"), "6QA=");
    }

    #[test]
    fn cmd_sees_only_safe_characters_after_start() {
        let line = start_command_line();
        let (head, encoded) = line.rsplit_once(' ').unwrap();
        assert_eq!(head, "/c start \"\" powershell.exe -NoProfile -NoLogo -EncodedCommand");
        assert!(encoded.chars().all(|c| c.is_ascii_alphanumeric() || "+/=".contains(c)));
    }

    #[test]
    fn the_script_runs_the_named_program_with_the_subscription_sign_in() {
        assert!(SCRIPT.contains(&format!("& $env:{PROGRAM_ENV} auth login --claudeai")));
        assert!(SCRIPT.contains("Refresh models"));
        assert!(DROPPED_ENV.contains(&"CLAUDE_CONFIG_DIR"));
    }

    #[test]
    fn the_script_is_five_powershell_lines() {
        let lines: Vec<&str> = SCRIPT.lines().collect();
        assert_eq!(lines.len(), 5, "{SCRIPT}");
        assert!(lines[0].starts_with("$Host.UI.RawUI.WindowTitle"));
        assert!(lines[3].contains("} else {"));
        assert!(!SCRIPT.contains('\\'), "no escape left in {SCRIPT}");
    }

    /// The real window, minus the window: PowerShell decodes and runs the script, and a sign-in that fails
    /// (a stand-in program that rejects the arguments) says so instead of "Signed in".
    #[cfg(windows)]
    #[test]
    fn powershell_runs_the_script_and_reports_a_failed_sign_in() {
        let out = std::process::Command::new("powershell.exe")
            .args(["-NoProfile", "-NoLogo", "-EncodedCommand", &encoded_command(SCRIPT)])
            .env(PROGRAM_ENV, r"C:\Windows\System32\where.exe")
            .stdin(std::process::Stdio::null())
            .output()
            .unwrap();
        let text = String::from_utf8_lossy(&out.stdout);
        let errors = String::from_utf8_lossy(&out.stderr);
        assert!(text.contains("The sign-in did not finish"), "stdout: {text}\nstderr: {errors}");
        assert!(!text.contains("Signed in."), "{text}");
    }

    #[test]
    fn only_an_existing_claude_exe_is_accepted() {
        let dir = tempfile::tempdir().unwrap();
        let claude = dir.path().join("claude.exe");
        std::fs::write(&claude, "stub").unwrap();
        let other = dir.path().join("powershell.exe");
        std::fs::write(&other, "stub").unwrap();

        assert_eq!(claude_program(claude.to_str().unwrap()).unwrap(), claude);
        assert!(claude_program(other.to_str().unwrap()).is_err());
        assert!(claude_program(dir.path().join("gone").join("claude.exe").to_str().unwrap()).is_err(), "missing file");
        assert!(claude_program("claude.exe").is_err(), "relative path");
        assert!(claude_program("").is_err());
    }
}
