//! Android JNI bridge for Kinetic.
//!
//! Everything here runs in-process on the phone: there is no socket, no
//! HTTP client and no remote endpoint. The Kotlin side
//! (`com.kineai.local.engine.NativeEngine`) calls these functions from the
//! `:engine` process.
//!
//! Errors are thrown as `java.lang.RuntimeException`; panics never cross
//! the JNI boundary.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;

use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use kinetic::{Message, Model, Options};
use kinetic_quant::TensorType;
use serde_json::{json, Value};

type Res<T> = Result<T, String>;

fn run<T>(env: &mut JNIEnv, f: impl FnOnce(&mut JNIEnv) -> Res<T>, fallback: T) -> T {
    match catch_unwind(AssertUnwindSafe(|| f(env))) {
        Ok(Ok(v)) => v,
        Ok(Err(msg)) => {
            let _ = env.throw_new("java/lang/RuntimeException", msg);
            fallback
        }
        Err(p) => {
            let why = p
                .downcast_ref::<&str>()
                .copied()
                .or_else(|| p.downcast_ref::<String>().map(String::as_str))
                .unwrap_or("unknown cause");
            let _ = env.throw_new("java/lang/RuntimeException", format!("internal error: {why}"));
            fallback
        }
    }
}

fn get_string(env: &mut JNIEnv, s: &JString) -> Res<String> {
    env.get_string(s).map(Into::into).map_err(|e| e.to_string())
}

fn new_string(env: &mut JNIEnv, s: &str) -> Res<jstring> {
    env.new_string(s).map(JString::into_raw).map_err(|e| e.to_string())
}

/// A loaded model behind a `jlong` handle.
struct Handle(Arc<Model>);

fn handle<'a>(h: jlong) -> Res<&'a Handle> {
    // SAFETY: Kotlin passes back a value from `nativeLoad` that it has not
    // yet given to `nativeUnload`; it serializes calls per handle.
    unsafe { (h as *const Handle).as_ref() }.ok_or_else(|| "model handle is null".to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_kineai_local_engine_NativeEngine_nativeVersion(
    mut env: JNIEnv,
    _: JClass,
) -> jstring {
    run(
        &mut env,
        |e| new_string(e, &format!("kinetic-android (kinetic {})", env!("CARGO_PKG_VERSION"))),
        std::ptr::null_mut(),
    )
}

/// Fixes the worker thread count for this process. Kinetic sizes its spin
/// pool from rayon's global pool, which can be built only once, so a
/// different count needs a fresh process (the app restarts `:engine`).
#[no_mangle]
pub extern "system" fn Java_com_kineai_local_engine_NativeEngine_nativeSetThreads(
    mut env: JNIEnv,
    _: JClass,
    threads: jint,
) -> jboolean {
    run(
        &mut env,
        |_| {
            let n = threads.max(1) as usize;
            Ok(match rayon::ThreadPoolBuilder::new().num_threads(n).build_global() {
                Ok(()) => JNI_TRUE,
                Err(_) => JNI_FALSE,
            })
        },
        JNI_FALSE,
    )
}

/// What the CPU offers, for the diagnostics screen.
#[no_mangle]
pub extern "system" fn Java_com_kineai_local_engine_NativeEngine_nativeCpuFeatures(
    mut env: JNIEnv,
    _: JClass,
) -> jstring {
    run(
        &mut env,
        |e| {
            #[cfg(target_arch = "aarch64")]
            let v = json!({
                "arch": "aarch64",
                "neon": std::arch::is_aarch64_feature_detected!("neon"),
                "dotprod": std::arch::is_aarch64_feature_detected!("dotprod"),
                "i8mm": std::arch::is_aarch64_feature_detected!("i8mm"),
                "fp16": std::arch::is_aarch64_feature_detected!("fp16"),
                "bf16": std::arch::is_aarch64_feature_detected!("bf16"),
                "sve": std::arch::is_aarch64_feature_detected!("sve"),
                "rayonThreads": rayon::current_num_threads(),
            });
            #[cfg(not(target_arch = "aarch64"))]
            let v = json!({"arch": std::env::consts::ARCH, "rayonThreads": rayon::current_num_threads()});
            new_string(e, &v.to_string())
        },
        std::ptr::null_mut(),
    )
}

/// Reads a GGUF header without loading weights and says whether this
/// engine can run it. The answer uses the engine's own architecture list
/// and kernel tables, so the app never advertises a model that cannot run.
#[no_mangle]
pub extern "system" fn Java_com_kineai_local_engine_NativeEngine_nativeInspect(
    mut env: JNIEnv,
    _: JClass,
    path: JString,
) -> jstring {
    run(
        &mut env,
        |e| {
            let path = get_string(e, &path)?;
            let out = inspect(&path);
            new_string(e, &out.to_string())
        },
        std::ptr::null_mut(),
    )
}

fn inspect(path: &str) -> Value {
    let file = match kinetic_gguf::GgufFile::open(path) {
        Ok(f) => f,
        Err(err) => return json!({"ok": false, "status": "unsupported", "reason": format!("not a readable GGUF file: {err}")}),
    };
    let g = file.header();
    let arch = g.architecture().unwrap_or("unknown").to_string();
    let number = |key: &str| g.arch_key(key).and_then(|v| v.as_u64());
    let params: u64 = g.tensors.iter().map(|t| t.elements()).sum();
    let mut by_type: std::collections::BTreeMap<&str, (u64, u64)> = Default::default();
    let mut slow: Vec<&str> = Vec::new();
    for t in &g.tensors {
        let e = by_type.entry(t.ty.name()).or_default();
        e.0 += 1;
        e.1 += t.size;
        // 1-D tensors (norms, biases) are f32 and never go through a kernel.
        if t.dims.len() > 1 && !kinetic_cpu::has_fast_kernel(t.ty) && !slow.contains(&t.ty.name()) {
            slow.push(t.ty.name());
        }
    }
    let supported = kinetic_models::supports(&arch);
    let mut status = "compatible";
    let mut reason = String::new();
    if !supported {
        status = "unsupported";
        reason = format!(
            "architecture \"{arch}\" is not implemented by Kinetic v0.1.0 (supported: {})",
            kinetic_models::architectures().collect::<Vec<_>>().join(", ")
        );
    } else if kinetic_models::is_encoder(&arch) {
        status = "unsupported";
        reason = "embedding/encoder models cannot chat".into();
    } else if !slow.is_empty() {
        status = "limited";
        reason = format!("{} has no fast ARM64 kernel yet; it runs through the slower f32 path", slow.join(", "));
    }
    let quants: Vec<Value> = by_type
        .iter()
        .map(|(ty, (count, bytes))| json!({"type": ty, "tensors": count, "bytes": bytes}))
        .collect();
    let _ = TensorType::F32;
    json!({
        "ok": true,
        "status": status,
        "reason": reason,
        "architecture": arch,
        "name": g.get("general.name").and_then(|v| v.as_str()),
        "fileBytes": file.len(),
        "parameters": params,
        "contextLength": number("context_length"),
        "layers": number("block_count"),
        "width": number("embedding_length"),
        "headCountKv": number("attention.head_count_kv"),
        "headCount": number("attention.head_count"),
        "quantizations": quants,
    })
}

/// Loads a model. `context` of 0 takes the model's own length (capped by
/// Kinetic). Weights stay memory-mapped from the file.
#[no_mangle]
pub extern "system" fn Java_com_kineai_local_engine_NativeEngine_nativeLoad(
    mut env: JNIEnv,
    _: JClass,
    path: JString,
    context: jint,
) -> jlong {
    run(
        &mut env,
        |e| {
            let path = get_string(e, &path)?;
            let options = Options {
                context: (context > 0).then_some(context as usize),
                gpu: false, // CPU only: no Vulkan backend is enabled in this build.
            };
            let model = Model::open_with(&path, &options).map_err(|err| err.to_string())?;
            Ok(Box::into_raw(Box::new(Handle(Arc::new(model)))) as jlong)
        },
        0,
    )
}

#[no_mangle]
pub extern "system" fn Java_com_kineai_local_engine_NativeEngine_nativeUnload(
    mut env: JNIEnv,
    _: JClass,
    h: jlong,
) {
    run(
        &mut env,
        |_| {
            if h != 0 {
                // SAFETY: the handle came from `nativeLoad` and is released once.
                drop(unsafe { Box::from_raw(h as *mut Handle) });
            }
            Ok(())
        },
        (),
    )
}

#[no_mangle]
pub extern "system" fn Java_com_kineai_local_engine_NativeEngine_nativeModelInfo(
    mut env: JNIEnv,
    _: JClass,
    h: jlong,
) -> jstring {
    run(
        &mut env,
        |e| {
            let m = &handle(h)?.0;
            let v = json!({"name": m.name(), "contextLength": m.context_length()});
            new_string(e, &v.to_string())
        },
        std::ptr::null_mut(),
    )
}

/// Token count of `text` under the model's tokenizer, for context budgeting.
#[no_mangle]
pub extern "system" fn Java_com_kineai_local_engine_NativeEngine_nativeCountTokens(
    mut env: JNIEnv,
    _: JClass,
    h: jlong,
    text: JString,
) -> jint {
    run(
        &mut env,
        |e| {
            let m = &handle(h)?.0;
            let text = get_string(e, &text)?;
            Ok(m.tokenize(&text, false).len() as jint)
        },
        -1,
    )
}

/// Streams an answer to the chat in `messages_json` (an array of
/// `{role, content}`). `sink.onToken(String): Boolean` is called for each
/// piece; returning false cancels. Returns timing JSON.
///
/// Cancellation takes effect at the next emitted piece: Kinetic checks the
/// callback only after a token is produced, so a cancel during the prompt
/// pass lands when the first token arrives.
#[no_mangle]
pub extern "system" fn Java_com_kineai_local_engine_NativeEngine_nativeChat(
    mut env: JNIEnv,
    _: JClass,
    h: jlong,
    messages_json: JString,
    max_tokens: jint,
    temperature: jfloat,
    seed: jlong,
    sink: JObject,
) -> jstring {
    run(
        &mut env,
        |e| {
            let m = Arc::clone(&handle(h)?.0);
            let raw = get_string(e, &messages_json)?;
            let parsed: Vec<Value> = serde_json::from_str(&raw).map_err(|x| format!("bad messages: {x}"))?;
            let messages: Vec<Message> = parsed
                .iter()
                .map(|v| {
                    Message::new(
                        v["role"].as_str().unwrap_or("user"),
                        v["content"].as_str().unwrap_or(""),
                    )
                })
                .collect();
            let mut request = m.request();
            request.max_tokens = (max_tokens > 0).then_some(max_tokens as usize);
            if temperature >= 0.0 {
                request.sampling.temperature = temperature;
            }
            if seed >= 0 {
                request.sampling.seed = Some(seed as u64);
            }
            let mut cancelled = false;
            let mut callback_error: Option<String> = None;
            let done = m.chat(&messages, &request, &mut |piece| {
                let jp = match e.new_string(piece) {
                    Ok(s) => s,
                    Err(x) => {
                        callback_error = Some(x.to_string());
                        return false;
                    }
                };
                match e
                    .call_method(&sink, "onToken", "(Ljava/lang/String;)Z", &[JValue::Object(&jp)])
                    .and_then(|r| r.z())
                {
                    Ok(true) => true,
                    Ok(false) => {
                        cancelled = true;
                        false
                    }
                    Err(x) => {
                        callback_error = Some(x.to_string());
                        false
                    }
                }
            });
            if let Some(msg) = callback_error {
                return Err(format!("callback failed: {msg}"));
            }
            let c = done.map_err(|x| x.to_string())?;
            let t = c.timings;
            let out = json!({
                "finish": if cancelled { "cancelled" } else { c.finish.as_str() },
                "promptTokens": c.prompt_tokens,
                "cachedTokens": t.cached_tokens,
                "completionTokens": c.completion_tokens,
                "prefillMs": t.prefill.as_secs_f64() * 1000.0,
                "decodeMs": t.decode.as_secs_f64() * 1000.0,
            });
            new_string(e, &out.to_string())
        },
        std::ptr::null_mut(),
    )
}
