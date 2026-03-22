pub mod api;

// Audio engine is now available on all platforms including Android (using CPAL with Oboe backend)
pub mod audio;

/// Custom UAC 2.0 USB Audio (DAC/AMP detection and bit-perfect playback).
/// Real implementation is gated by the `uac2` feature.
pub mod uac2;

mod frb_generated;

// Android NDK context initialization for cpal/oboe
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "C" fn JNI_OnLoad(vm: jni::JavaVM, res: *mut std::os::raw::c_void) -> jni::sys::jint {
    use std::ffi::c_void;
    let vm = vm.get_java_vm_pointer() as *mut c_void;
    unsafe {
        ndk_context::initialize_android_context(vm, res);
    }
    jni::JNIVersion::V6.into()
}
