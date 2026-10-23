//! JNI 只传字符串和编号；不暴露裸指针，拒绝跨线程或失效的会话编号。

use crate::session::Session;
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use std::cell::RefCell;
use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;
use std::sync::atomic::{AtomicI64, Ordering};

thread_local! {
    static SESSIONS: RefCell<HashMap<i64, Session>> = RefCell::new(HashMap::new());
}
static NEXT_ID: AtomicI64 = AtomicI64::new(1);

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_qingstudy_ime_NativeBridge_textLight(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    outputs: JString,
    complete: jni::sys::jboolean,
) -> jstring {
    guarded(&mut env, |env| {
        let source: String = env.get_string(&source).map_err(|e| e.to_string())?.into();
        let outputs: String = env.get_string(&outputs).map_err(|e| e.to_string())?.into();
        let outputs: Vec<String> = serde_json::from_str(&outputs).map_err(|e| e.to_string())?;
        let parts =
            qingjian_core::text_tools::lightweight::parts(&source).map_err(str::to_string)?;
        let prompts: Vec<String> = parts
            .iter()
            .map(|p| qingjian_core::text_tools::lightweight::prompt(p, parts.len() > 1))
            .collect();
        let grammars: Vec<String> = parts
            .iter()
            .map(|p| qingjian_core::text_tools::punctuation_grammar::build(p))
            .collect();
        let result =
            qingjian_core::text_tools::lightweight::finish(&source, &outputs, complete != 0);
        let json = serde_json::json!({"parts": parts, "prompts": prompts, "grammars": grammars, "accepted": result.is_ok(),
            "text": result.as_ref().ok(), "reason": result.err().unwrap_or("")});
        Ok(env
            .new_string(json.to_string())
            .map_err(|e| e.to_string())?
            .into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_qingstudy_ime_NativeBridge_textTool(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    output: JString,
    complete: jni::sys::jboolean,
) -> jstring {
    guarded(&mut env, |env| {
        let source: String = env.get_string(&source).map_err(|e| e.to_string())?.into();
        let output: String = env.get_string(&output).map_err(|e| e.to_string())?.into();
        let prompt = qingjian_core::text_tools::prompt(&source).map_err(str::to_string)?;
        let reason = qingjian_core::text_tools::review(&source, &output, complete != 0).err();
        let json = serde_json::json!({ "prompt": prompt, "accepted": reason.is_none(), "reason": reason.unwrap_or("") });
        Ok(env
            .new_string(json.to_string())
            .map_err(|e| e.to_string())?
            .into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_qingstudy_ime_NativeBridge_voiceText(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    options: JString,
    final_result: jni::sys::jboolean,
) -> jstring {
    guarded(&mut env, |env| {
        let source: String = env.get_string(&source).map_err(|e| e.to_string())?.into();
        let options: String = env.get_string(&options).map_err(|e| e.to_string())?.into();
        let options = serde_json::from_str(&options).map_err(|e| e.to_string())?;
        let result = qingjian_core::speech::format_speech(&source, &options, final_result != 0);
        let json = serde_json::to_string(&result).map_err(|e| e.to_string())?;
        Ok(env.new_string(json).map_err(|e| e.to_string())?.into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_qingstudy_ime_NativeBridge_phrases(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
) -> jstring {
    guarded(&mut env, |env| {
        let source: String = env.get_string(&source).map_err(|e| e.to_string())?.into();
        let result = crate::phrases::inspect(&source)?;
        Ok(env
            .new_string(result.to_string())
            .map_err(|e| e.to_string())?
            .into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_qingstudy_ime_NativeBridge_domains(
    mut env: JNIEnv,
    _class: JClass,
    directory: JString,
) -> jstring {
    guarded(&mut env, |env| {
        let directory: String = env
            .get_string(&directory)
            .map_err(|e| e.to_string())?
            .into();
        let result = crate::domains::info(Path::new(&directory));
        Ok(env
            .new_string(result.to_string())
            .map_err(|e| e.to_string())?
            .into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_qingstudy_ime_NativeBridge_learning(
    mut env: JNIEnv,
    _class: JClass,
    directory: JString,
    action: JString,
    source: JString,
) -> jstring {
    guarded(&mut env, |env| {
        let root: String = env
            .get_string(&directory)
            .map_err(|e| e.to_string())?
            .into();
        let action: String = env.get_string(&action).map_err(|e| e.to_string())?.into();
        let source: String = env.get_string(&source).map_err(|e| e.to_string())?.into();
        let result = match action.as_str() {
            "inspect" => crate::learning_backup::inspect(Path::new(&source))?,
            "restore" => crate::learning_backup::restore(Path::new(&root), Path::new(&source))?,
            "clear-habits" => crate::learning_backup::clear_habits(Path::new(&root))?,
            "directory" => {
                serde_json::json!({"directory": crate::learning_backup::active_directory(Path::new(&root))?})
            }
            _ => return Err("未知学习备份操作".into()),
        };
        Ok(env
            .new_string(result.to_string())
            .map_err(|e| e.to_string())?
            .into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_qingstudy_ime_NativeBridge_exportLearning(
    mut env: JNIEnv,
    _class: JClass,
    directory: JString,
    destination: JString,
) -> jstring {
    guarded(&mut env, |env| {
        let directory: String = env
            .get_string(&directory)
            .map_err(|e| e.to_string())?
            .into();
        let destination: String = env
            .get_string(&destination)
            .map_err(|e| e.to_string())?
            .into();
        let result =
            crate::learning_backup::export(Path::new(&directory), Path::new(&destination))?;
        Ok(env
            .new_string(result.to_string())
            .map_err(|e| e.to_string())?
            .into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_qingstudy_ime_NativeBridge_dictionaries(
    mut env: JNIEnv,
    _class: JClass,
    directory: JString,
    action: JString,
    argument: JString,
) -> jstring {
    guarded(&mut env, |env| {
        let directory: String = env
            .get_string(&directory)
            .map_err(|e| e.to_string())?
            .into();
        let action: String = env.get_string(&action).map_err(|e| e.to_string())?.into();
        let argument: String = env.get_string(&argument).map_err(|e| e.to_string())?.into();
        let result = crate::dictionaries::manage(Path::new(&directory), &action, &argument)?;
        Ok(env
            .new_string(result.to_string())
            .map_err(|e| e.to_string())?
            .into_raw())
    })
}

fn guarded<T: Default>(
    env: &mut JNIEnv<'_>,
    work: impl FnOnce(&mut JNIEnv<'_>) -> Result<T, String>,
) -> T {
    match catch_unwind(AssertUnwindSafe(|| work(env))) {
        Ok(Ok(value)) => value,
        result => {
            let message = match result {
                Ok(Err(error)) => error,
                _ => "native engine panicked".to_owned(),
            };
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            T::default()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_qingstudy_ime_NativeBridge_create(
    mut env: JNIEnv,
    _class: JClass,
    directory: JString,
) -> jlong {
    guarded(&mut env, |env| {
        let directory: String = env
            .get_string(&directory)
            .map_err(|e| e.to_string())?
            .into();
        let session = Session::open(Path::new(&directory))?;
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        SESSIONS.with(|sessions| sessions.borrow_mut().insert(id, session));
        Ok(id)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_qingstudy_ime_NativeBridge_step(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    action: JString,
    argument: JString,
) -> jstring {
    guarded(&mut env, |env| {
        let action: String = env.get_string(&action).map_err(|e| e.to_string())?.into();
        let argument: String = env.get_string(&argument).map_err(|e| e.to_string())?.into();
        let frame = SESSIONS.with(|sessions| {
            let mut sessions = sessions.borrow_mut();
            sessions
                .get_mut(&id)
                .ok_or_else(|| "invalid session or wrong thread".to_owned())?
                .step(&action, &argument)
        })?;
        Ok(env
            .new_string(frame.to_string())
            .map_err(|e| e.to_string())?
            .into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_qingstudy_ime_NativeBridge_close(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) {
    SESSIONS.with(|sessions| {
        if let Some(mut session) = sessions.borrow_mut().remove(&id) {
            let _ = session.step("flush", "");
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_qingstudy_ime_NativeBridge_phraseFile(
    mut env: JNIEnv,
    _class: JClass,
    action: JString,
    source: JString,
) -> jstring {
    guarded(&mut env, |env| {
        let action: String = env.get_string(&action).map_err(|e| e.to_string())?.into();
        let source: String = env.get_string(&source).map_err(|e| e.to_string())?.into();
        let result = crate::phrases::file(&action, &source)?;
        Ok(env
            .new_string(result.to_string())
            .map_err(|e| e.to_string())?
            .into_raw())
    })
}
