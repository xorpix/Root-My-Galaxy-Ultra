/// Install standard KernelSU lifecycle hooks, independent of manager-owned ksud copies.
pub fn install_samsung_composer_hooks() -> Result<()> {
    use std::fs;
    use std::os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt, PermissionsExt};
    use std::path::Path;

    fn ensure_hook_directory(path: &str, private: bool) -> Result<()> {
        let created = match fs::DirBuilder::new().mode(0o700).create(path) {
            Ok(()) => true,
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => false,
            Err(error) => return Err(error.into()),
        };
        let metadata = fs::symlink_metadata(path)?;
        anyhow::ensure!(
            metadata.is_dir() && metadata.uid() == 0 && metadata.mode() & 0o022 == 0,
            "unsafe hook directory: {path}"
        );
        if private {
            fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
        } else if created {
            fs::set_permissions(path, fs::Permissions::from_mode(0o755))?;
        }
        Ok(())
    }

    fn write_hook(path: &str, contents: &str) -> Result<()> {
        match fs::symlink_metadata(path) {
            Ok(metadata) => anyhow::ensure!(
                metadata.is_file() && metadata.uid() == 0,
                "unsafe hook destination: {path}"
            ),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
        let destination = Path::new(path);
        let name = destination
            .file_name()
            .context("missing hook filename")?
            .to_string_lossy();
        let temporary = destination.with_file_name(format!(".{name}.{}.tmp", std::process::id()));
        let mut file = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o700)
            .open(&temporary)?;
        let result = std::io::Write::write_all(&mut file, contents.as_bytes())
            .and_then(|()| file.sync_all());
        drop(file);
        if let Err(error) = result {
            let _ = fs::remove_file(&temporary);
            return Err(error.into());
        }
        if let Err(error) = fs::rename(&temporary, destination) {
            let _ = fs::remove_file(&temporary);
            return Err(error.into());
        }
        Ok(())
    }

    let manufacturer = Command::new("/system/bin/getprop")
        .arg("ro.product.manufacturer")
        .output()?;
    anyhow::ensure!(
        manufacturer.status.success(),
        "cannot read device manufacturer"
    );
    if !String::from_utf8_lossy(&manufacturer.stdout)
        .trim()
        .eq_ignore_ascii_case("samsung")
    {
        return Ok(());
    }

    ensure_hook_directory("/data/adb", false)?;
    ensure_hook_directory("/data/adb/rmgu", true)?;
    ensure_hook_directory("/data/adb/rmgu/composer", true)?;
    ensure_hook_directory("/data/adb/post-mount.d", false)?;
    ensure_hook_directory("/data/adb/emulated-soft-reboot.d", false)?;
    // Publish the shared body first and the arming entry point last. A partial
    // installation cannot arm a transition whose reset script is not installed.
    write_hook("/data/adb/rmgu/composer/restart.sh", SAMSUNG_COMPOSER_SCRIPT)?;
    write_hook(
        "/data/adb/post-mount.d/00-rmgu-samsung-composer.sh",
        "#!/system/bin/sh\nexec /system/bin/sh /data/adb/rmgu/composer/restart.sh post-mount\n",
    )?;
    write_hook(
        "/data/adb/emulated-soft-reboot.d/00-rmgu-samsung-composer.sh",
        "#!/system/bin/sh\nexec /system/bin/sh /data/adb/rmgu/composer/restart.sh arm\n",
    )?;
    info!("RMGU Samsung composer lifecycle hooks installed");
    Ok(())
}
