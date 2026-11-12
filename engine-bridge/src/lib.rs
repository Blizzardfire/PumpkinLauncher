use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use pumpkin_config::{LoadConfiguration, PumpkinConfig};
use pumpkin_core::{
    command::CommandSender,
    data::VanillaData,
    init_logger,
    plugin::loader::PluginLoader,
    server::Server,
    PumpkinServer,
};
use pumpkin_wasm_host::WasmPluginLoader;
use std::{
    fs,
    os::fd::{AsRawFd, RawFd},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, Once, OnceLock,
    },
    thread,
};
use tokio::runtime::{Builder, Handle};

extern "C" {
    fn dup(fd: i32) -> i32;
    fn dup2(oldfd: i32, newfd: i32) -> i32;
    fn close(fd: i32) -> i32;
}

static RUNNING: AtomicBool = AtomicBool::new(false);
static STOP_REQUESTED: AtomicBool = AtomicBool::new(false);
static LOGGER_INIT: Once = Once::new();
static RAYON_INIT: Once = Once::new();
static ACTIVE_SERVER: OnceLock<Mutex<Option<Arc<Server>>>> = OnceLock::new();
static RUNTIME_HANDLE: OnceLock<Mutex<Option<Handle>>> = OnceLock::new();

struct WorkingDirectoryGuard {
    previous: PathBuf,
}

impl WorkingDirectoryGuard {
    fn enter(path: &str) -> Result<Self, String> {
        let previous = std::env::current_dir().map_err(|error| error.to_string())?;
        std::env::set_current_dir(path).map_err(|error| error.to_string())?;
        Ok(Self { previous })
    }
}

impl Drop for WorkingDirectoryGuard {
    fn drop(&mut self) {
        if let Err(error) = std::env::set_current_dir(&self.previous) {
            tracing::error!("Could not restore the Android process working directory: {error}");
        }
    }
}

struct RunningGuard;

// Pumpkin answers console commands with println!, which goes to stdout. The
// launcher only reads the engine log file, so stdout must point at it or every
// command reply ("help", "list", ...) is lost on Android.
struct StdoutRedirect {
    original: RawFd,
}

impl StdoutRedirect {
    fn install(log_file: &str) -> Result<Self, String> {
        if let Some(parent) = Path::new(log_file).parent() {
            fs::create_dir_all(parent).map_err(|error| error.to_string())?;
        }
        let file = fs::OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(log_file)
            .map_err(|error| error.to_string())?;
        // SAFETY: only manipulates this process's own file descriptors.
        let original = unsafe { dup(1) };
        if original < 0 {
            return Err("could not duplicate the stdout descriptor".to_string());
        }
        if unsafe { dup2(file.as_raw_fd(), 1) } < 0 {
            unsafe { close(original) };
            return Err("could not redirect stdout to the engine log".to_string());
        }
        Ok(Self { original })
    }
}

impl Drop for StdoutRedirect {
    fn drop(&mut self) {
        // SAFETY: restores the descriptor captured during install().
        unsafe {
            dup2(self.original, 1);
            close(self.original);
        }
    }
}

impl Drop for RunningGuard {
    fn drop(&mut self) {
        if let Ok(mut slot) = server_slot().lock() {
            *slot = None;
        }
        if let Ok(mut slot) = runtime_slot().lock() {
            *slot = None;
        }
        RUNNING.store(false, Ordering::Release);
    }
}

fn server_slot() -> &'static Mutex<Option<Arc<Server>>> {
    ACTIVE_SERVER.get_or_init(|| Mutex::new(None))
}

fn runtime_slot() -> &'static Mutex<Option<Handle>> {
    RUNTIME_HANDLE.get_or_init(|| Mutex::new(None))
}

fn java_string(env: &mut JNIEnv<'_>, value: JString<'_>) -> Result<String, String> {
    env.get_string(&value)
        .map(|value| value.into())
        .map_err(|error| error.to_string())
}

fn run_engine(work_dir: String, log_file: &str) -> Result<(), String> {
    fs::create_dir_all(&work_dir).map_err(|error| error.to_string())?;
    if let Some(parent) = Path::new(log_file).parent() {
        fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    }
    // Pumpkin currently resolves some runtime paths relative to cwd. Keep this
    // change scoped to the engine lifetime and restore the app cwd on every exit.
    // Note: cwd is process-global, so the host must avoid relative-path work while
    // the embedded server is running.
    let _working_directory = WorkingDirectoryGuard::enter(&work_dir)?;

    let mut config = PumpkinConfig::load(Path::new(&work_dir));
    // The file logger is disabled: stdout already carries every log line plus
    // command replies, and two writers on one file would corrupt it.
    config.advanced.logging.file = String::new();
    config.advanced.logging.color = false;
    config.advanced.commands.use_console = false;
    config.advanced.commands.use_tty = false;

    let _ = rustls::crypto::ring::default_provider().install_default();
    RAYON_INIT.call_once(|| {
        let _ = rayon::ThreadPoolBuilder::new()
            .thread_name(|index| format!("Pumpkin-Worker-{index}"))
            .build_global();
    });
    LOGGER_INIT.call_once(|| init_logger(&config.advanced));

    let verify_plugin_signatures = config.advanced.plugins.verify_signatures;
    let vanilla_data = VanillaData::load();
    let runtime = Builder::new_multi_thread()
        .enable_all()
        .thread_name("Pumpkin-Android")
        .build()
        .map_err(|error| error.to_string())?;

    let handle = runtime.handle().clone();
    runtime
        .block_on(async move {
            let plugin_loaders: Vec<Arc<dyn PluginLoader>> =
                vec![Arc::new(WasmPluginLoader::new(verify_plugin_signatures))];

            let pumpkin = PumpkinServer::new(
                config.basic,
                config.advanced,
                config.telemetry,
                vanilla_data,
                plugin_loaders,
                false,
            )
            .await
            .map_err(|error| error.to_string())?;

            let server = pumpkin.server.clone();
            if let Ok(mut slot) = server_slot().lock() {
                *slot = Some(server.clone());
            }
            if STOP_REQUESTED.swap(false, Ordering::AcqRel) {
                server.stop();
            }
            if let Ok(mut slot) = runtime_slot().lock() {
                *slot = Some(handle);
            }

            pumpkin.init_plugins().await;
            pumpkin.start().await;

            if let Ok(mut slot) = server_slot().lock() {
                *slot = None;
            }
            if let Ok(mut slot) = runtime_slot().lock() {
                *slot = None;
            }
            Ok::<(), String>(())
        })
}

#[no_mangle]
pub extern "system" fn Java_com_blizzardfire_pumpkinlauncher_PumpkinNative_nativeStart(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    work_dir: JString<'_>,
    log_file: JString<'_>,
) -> jint {
    if RUNNING
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        return -1;
    }

    STOP_REQUESTED.store(false, Ordering::Release);
    let work_dir = match java_string(&mut env, work_dir) {
        Ok(value) => value,
        Err(_) => {
            RUNNING.store(false, Ordering::Release);
            return -2;
        }
    };
    let log_file = match java_string(&mut env, log_file) {
        Ok(value) => value,
        Err(_) => {
            RUNNING.store(false, Ordering::Release);
            return -2;
        }
    };

    let spawned = thread::Builder::new()
        .name("Pumpkin-Engine".to_string())
        .spawn(move || {
            let _running_guard = RunningGuard;
            // Keep stdout attached to the log until the final error is reported.
            let _stdout_redirect = match StdoutRedirect::install(&log_file) {
                Ok(guard) => Some(guard),
                Err(error) => {
                    eprintln!("Could not redirect engine stdout to the log: {error}");
                    None
                }
            };
            if let Err(error) = run_engine(work_dir, &log_file) {
                tracing::error!("Pumpkin engine stopped with an error: {error}");
            }
            if let Ok(mut slot) = server_slot().lock() {
                *slot = None;
            }
            if let Ok(mut slot) = runtime_slot().lock() {
                *slot = None;
            }
            RUNNING.store(false, Ordering::Release);
        });

    if spawned.is_err() {
        RUNNING.store(false, Ordering::Release);
        return -3;
    }
    0
}

#[no_mangle]
pub extern "system" fn Java_com_blizzardfire_pumpkinlauncher_PumpkinNative_nativeSendCommand(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    command: JString<'_>,
) -> jboolean {
    let command = match java_string(&mut env, command) {
        Ok(value) if !value.trim().is_empty() => value,
        _ => return JNI_FALSE,
    };

    let server = match server_slot().lock() {
        Ok(slot) => match slot.as_ref() {
            Some(server) => server.clone(),
            None => return JNI_FALSE,
        },
        Err(_) => return JNI_FALSE,
    };
    let handle = match runtime_slot().lock() {
        Ok(slot) => match slot.as_ref() {
            Some(handle) => handle.clone(),
            None => return JNI_FALSE,
        },
        Err(_) => return JNI_FALSE,
    };

    handle.spawn(async move {
        let source = CommandSender::Console.into_source(&server);
        server
            .command_dispatcher
            .load()
            .handle_command(&source, command.trim());
    });
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_blizzardfire_pumpkinlauncher_PumpkinNative_nativeStop(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jboolean {
    STOP_REQUESTED.store(true, Ordering::Release);
    let server = match server_slot().lock() {
        Ok(slot) => slot.as_ref().cloned(),
        Err(_) => None,
    };
    if let Some(server) = server {
        server.stop();
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_blizzardfire_pumpkinlauncher_PumpkinNative_nativeIsRunning(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jboolean {
    if RUNNING.load(Ordering::Acquire) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
