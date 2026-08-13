#!/usr/bin/env python3
"""
pumpkin_auto_build.py

Checks GitHub for new commits on Pumpkin-MC/Pumpkin (master branch),
and if a new commit is found, automatically:
  1. Pulls the latest source
  2. Updates git submodules
  3. Cross-compiles the server for arm64-v8a, armeabi-v7a, and x86_64
  4. Copies the resulting binaries into your Android project's jniLibs folders

Run it manually whenever you want to check for updates, or schedule it
with Windows Task Scheduler to run automatically (e.g. once a day).

--- CONFIGURE THESE PATHS BEFORE RUNNING ---
"""

import ctypes
import json
import os
import shutil
import subprocess
import sys
import urllib.request
from datetime import datetime

# --- Windows sleep-prevention constants ---
# These tell Windows "don't sleep, and don't turn off the display" for as
# long as this flag is set, without needing any user activity (mouse/keyboard).
ES_CONTINUOUS = 0x80000000
ES_SYSTEM_REQUIRED = 0x00000001
ES_DISPLAY_REQUIRED = 0x00000002


def prevent_sleep() -> None:
    """Tell Windows to stay awake until allow_sleep() is called."""
    if sys.platform != "win32":
        return
    ctypes.windll.kernel32.SetThreadExecutionState(
        ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED
    )


def allow_sleep() -> None:
    """Restore normal Windows sleep behavior."""
    if sys.platform != "win32":
        return
    ctypes.windll.kernel32.SetThreadExecutionState(ES_CONTINUOUS)

# ---------------------------------------------------------------------------
# CONFIGURATION - edit these to match your setup
# ---------------------------------------------------------------------------

PUMPKIN_REPO_DIR = r"C:\Users\BlizzardFire\Downloads\pumpkin\Pumpkin"
ANDROID_PROJECT_DIR = r"C:\Users\BlizzardFire\AndroidStudioProjects\PumpkinLauncher"
ANDROID_NDK_HOME = r"C:\Users\BlizzardFire\AppData\Local\Android\Sdk\ndk\30.0.15729638"

# Every run appends its output here, so you can check what happened
# even if the script ran overnight while you weren't watching.
LOG_FILE_PATH = r"C:\Users\BlizzardFire\Downloads\pumpkin\Pumpkin\pumpkin_build_log.txt"

# Prevents two overlapping runs from colliding (e.g. if a login-triggered
# run starts while a previous run is still mid-build). If this file exists
# when the script starts, it assumes another instance is already running
# and exits early instead of racing it.
LOCK_FILE_PATH = r"C:\Users\BlizzardFire\Downloads\pumpkin\Pumpkin\.pumpkin_auto_build.lock"

# If set to a commit hash (e.g. "4c0a4049"), the script will always target
# that exact commit, overriding TRACK_MODE below entirely.
PINNED_COMMIT: str | None = None

# "release" (default, recommended): tracks the latest GitHub Release, which
#   Pumpkin regenerates roughly every day or two. Much more stable than
#   chasing master directly - you won't get a "new commit" mid-build.
# "master": tracks the absolute newest commit on master, which changes
#   many times per hour. Only use this if you want bleeding-edge every run.
TRACK_MODE = "release"

GITHUB_COMMITS_API_URL = "https://api.github.com/repos/Pumpkin-MC/Pumpkin/commits/master"
# NOTE: GitHub's /releases/latest endpoint deliberately excludes pre-releases
# (Pumpkin's rolling "nightly" build is marked as one, so that endpoint 404s).
# We use the full /releases list instead - it's sorted newest-first and
# includes pre-releases, so this will automatically pick up nightly builds
# now AND any future stable/tagged release later, without needing to know
# its tag name in advance.
GITHUB_RELEASES_LIST_API_URL = "https://api.github.com/repos/Pumpkin-MC/Pumpkin/releases"
LAST_COMMIT_FILE = os.path.join(PUMPKIN_REPO_DIR, ".last_built_commit.txt")

# Set to True if you want the 16KB page-size linker flag applied.
# Leave False unless you specifically need Play Store 16KB compliance -
# it forces a full recompile and, as tested, may not resolve the warning anyway.
USE_16KB_LINKER_FLAG = False

TARGETS = ["arm64-v8a", "armeabi-v7a", "x86_64"]
RUST_TARGET_NAMES = {
    "arm64-v8a": "aarch64-linux-android",
    "armeabi-v7a": "armv7-linux-androideabi",
    "x86_64": "x86_64-linux-android",
}

# ---------------------------------------------------------------------------


def log(message: str) -> None:
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{timestamp}] {message}"
    print(line, flush=True)
    try:
        with open(LOG_FILE_PATH, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError:
        # If the log file can't be written for some reason, don't let that
        # crash the build - just keep going with console output only.
        pass


def get_remote_target_commit() -> str:
    """Return the commit SHA we should build, based on PINNED_COMMIT / TRACK_MODE."""
    if PINNED_COMMIT:
        return PINNED_COMMIT

    if TRACK_MODE == "release":
        req = urllib.request.Request(
            GITHUB_RELEASES_LIST_API_URL,
            headers={"User-Agent": "pumpkin-auto-build-script"},
        )
        with urllib.request.urlopen(req, timeout=15) as response:
            releases_data = json.loads(response.read().decode("utf-8"))

        if not releases_data:
            raise RuntimeError("No releases found for Pumpkin-MC/Pumpkin at all.")

        newest_release = releases_data[0]  # GitHub returns these newest-first
        tag_name = newest_release["tag_name"]
        is_prerelease = newest_release.get("prerelease", False)
        log(f"Newest release found: tag '{tag_name}' "
            f"({'pre-release' if is_prerelease else 'stable release'})")

        # Resolve the tag to its actual commit SHA
        req = urllib.request.Request(
            f"https://api.github.com/repos/Pumpkin-MC/Pumpkin/commits/{tag_name}",
            headers={"User-Agent": "pumpkin-auto-build-script"},
        )
        with urllib.request.urlopen(req, timeout=15) as response:
            commit_data = json.loads(response.read().decode("utf-8"))
        log(f"Latest release tag: {tag_name} -> commit {commit_data['sha'][:8]}")
        return commit_data["sha"]

    # TRACK_MODE == "master"
    req = urllib.request.Request(
        GITHUB_COMMITS_API_URL,
        headers={"User-Agent": "pumpkin-auto-build-script"},
    )
    with urllib.request.urlopen(req, timeout=15) as response:
        data = json.loads(response.read().decode("utf-8"))
    return data["sha"]


def get_last_built_commit() -> str | None:
    """Read the commit hash we last successfully built, if any."""
    if os.path.exists(LAST_COMMIT_FILE):
        with open(LAST_COMMIT_FILE, "r", encoding="utf-8") as f:
            return f.read().strip()
    return None


def save_last_built_commit(sha: str) -> None:
    with open(LAST_COMMIT_FILE, "w", encoding="utf-8") as f:
        f.write(sha)


def run(cmd: list[str], cwd: str, env: dict | None = None) -> None:
    """Run a subprocess, streaming output live, and raise if it fails."""
    log(f"Running: {' '.join(cmd)}  (cwd={cwd})")
    process = subprocess.run(cmd, cwd=cwd, env=env, shell=False)
    if process.returncode != 0:
        raise RuntimeError(f"Command failed with exit code {process.returncode}: {' '.join(cmd)}")


def pull_latest_source(target_commit: str) -> None:
    log(f"Fetching Pumpkin source and checking out {target_commit[:8]}...")
    run(["git", "fetch", "origin"], cwd=PUMPKIN_REPO_DIR)
    # Force-discard any local changes first. This repo is purely a build
    # source for us - we never want local edits to block an automated
    # checkout to a new commit (this is what caused the "git checkout"
    # failure with exit code 1).
    run(["git", "reset", "--hard", "HEAD"], cwd=PUMPKIN_REPO_DIR)
    run(["git", "clean", "-fd"], cwd=PUMPKIN_REPO_DIR)
    run(["git", "checkout", target_commit], cwd=PUMPKIN_REPO_DIR)
    run(["git", "submodule", "update", "--init", "--recursive"], cwd=PUMPKIN_REPO_DIR)


def build_all_targets() -> None:
    env = os.environ.copy()
    env["ANDROID_NDK_HOME"] = ANDROID_NDK_HOME
    if USE_16KB_LINKER_FLAG:
        env["RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"

    target_args: list[str] = []
    for abi in TARGETS:
        target_args += ["-t", abi]

    cmd = ["cargo", "ndk"] + target_args + ["-o", ".\\android-build", "build", "--release", "-p", "pumpkin"]
    log("Starting cross-compile for all architectures. This can take a long time...")

    # NOTE: cargo-ndk always tries to copy a "cdylib" artifact as its final step,
    # but Pumpkin builds a plain executable, not a cdylib - so this step reliably
    # fails with "No cdylib file found to copy" even when the actual compile
    # succeeded for every architecture. Because of that, we don't treat a
    # non-zero exit code here as fatal by itself; instead we run the command,
    # then verify the real binaries actually exist on disk afterward.
    process = subprocess.run(cmd, cwd=PUMPKIN_REPO_DIR, env=env, shell=False)

    missing = [
        abi for abi, rust_target in RUST_TARGET_NAMES.items()
        if not os.path.exists(os.path.join(PUMPKIN_REPO_DIR, "target", rust_target, "release", "pumpkin"))
    ]

    if missing:
        raise RuntimeError(
            f"Build did not produce binaries for: {', '.join(missing)}. "
            f"cargo ndk exited with code {process.returncode}."
        )

    if process.returncode != 0:
        log("Note: cargo-ndk exited with a non-zero code, but all expected "
            "binaries were found on disk (this is the known harmless "
            "'No cdylib file found to copy' quirk). Continuing.")


def copy_binaries_into_android_project() -> None:
    log("Copying compiled binaries into Android project...")
    jni_libs_root = os.path.join(ANDROID_PROJECT_DIR, "app", "src", "main", "jniLibs")

    for abi, rust_target in RUST_TARGET_NAMES.items():
        source_path = os.path.join(PUMPKIN_REPO_DIR, "target", rust_target, "release", "pumpkin")
        dest_dir = os.path.join(jni_libs_root, abi)
        dest_path = os.path.join(dest_dir, "libpumpkin.so")

        if not os.path.exists(source_path):
            raise FileNotFoundError(f"Expected binary not found: {source_path}")

        os.makedirs(dest_dir, exist_ok=True)
        shutil.copyfile(source_path, dest_path)

        # Verify the copy actually landed on disk before moving on, and log
        # the absolute path + size so there's no ambiguity about where the
        # file really is (helps rule out "wrong folder" confusion).
        if not os.path.exists(dest_path):
            raise RuntimeError(f"Copy appeared to succeed but file is missing: {dest_path}")
        size_mb = os.path.getsize(dest_path) / (1024 * 1024)
        log(f"  Copied {abi} -> {os.path.abspath(dest_path)} ({size_mb:.1f} MB)")

    # Open a brand-new File Explorer window at jniLibs. A fresh window has no
    # stale cached listing, so this sidesteps Explorer/Android Studio project
    # views that don't auto-refresh after files change from outside the IDE.
    if sys.platform == "win32":
        try:
            log("Opening a fresh File Explorer window at jniLibs to confirm the files visually...")
            os.startfile(jni_libs_root)  # noqa: S606
        except OSError as exc:
            log(f"(Could not auto-open Explorer: {exc} - not fatal, files are still copied correctly.)")


def main() -> None:
    try:
        with open(LOG_FILE_PATH, "a", encoding="utf-8") as f:
            f.write("\n" + "=" * 60 + "\n")
    except OSError:
        pass

    if os.path.exists(LOCK_FILE_PATH):
        log("Another instance appears to already be running "
            "(lock file present). Exiting without doing anything, so we "
            "don't collide with the in-progress build.")
        log(f"If no build is actually running, delete this file and try "
            f"again: {LOCK_FILE_PATH}")
        return

    try:
        with open(LOCK_FILE_PATH, "w", encoding="utf-8") as f:
            f.write(f"Started at {datetime.now().isoformat()}\n")
    except OSError as exc:
        log(f"WARNING: could not create lock file ({exc}). "
            f"Continuing anyway, but overlapping runs are no longer guarded against.")

    try:
        log("Checking for new Pumpkin commits...")

        try:
            remote_sha = get_remote_target_commit()
        except Exception as exc:
            log(f"ERROR: could not reach GitHub API: {exc}")
            sys.exit(1)

        last_built_sha = get_last_built_commit()

        if last_built_sha == remote_sha:
            log(f"Already up to date (commit {remote_sha[:8]}). Nothing to do.")
            return

        log(f"New commit found: {remote_sha[:8]} (previously built: "
            f"{last_built_sha[:8] if last_built_sha else 'none'})")
        log("Starting rebuild pipeline...")

        log("Preventing system sleep for the duration of the build...")
        prevent_sleep()
        try:
            pull_latest_source(remote_sha)
            build_all_targets()
            copy_binaries_into_android_project()
        except Exception as exc:
            log(f"ERROR: build pipeline failed: {exc}")
            log("The last-built-commit marker was NOT updated, so this version "
                "will be retried next run.")
            sys.exit(1)
        finally:
            allow_sleep()
            log("Sleep prevention lifted; system can sleep normally again.")

        save_last_built_commit(remote_sha)
        log(f"Success! Built and installed commit {remote_sha[:8]} into jniLibs.")
        log("Next steps: open Android Studio, Clean Project, Build APK(s), "
            "then reinstall on your phone.")
    except SystemExit:
        raise
    finally:
        # NOTE: the lock file is intentionally NOT auto-deleted here anymore.
        # Delete it yourself (LOCK_FILE_PATH below) whenever you're ready to
        # let the script run again.
        log(f"Lock file left in place at: {LOCK_FILE_PATH}")
        log("Delete it manually before the next run if you want this script to run again.")


if __name__ == "__main__":
    main()
