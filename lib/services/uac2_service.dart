import 'package:flutter/foundation.dart';
import 'package:flick/src/rust/api/uac2_api.dart' as rust_uac2;

/// Service that wraps the custom UAC 2.0 (USB Audio Class 2.0) Rust API.
///
/// Used for DAC/AMP detection and bit-perfect playback. When the Rust library
/// is built without the `uac2` feature, [isAvailable] is false and device
/// operations return empty or no-op results.
class Uac2Service {
  Uac2Service._();

  static final Uac2Service instance = Uac2Service._();

  /// Whether the UAC 2.0 backend is available in this build.
  /// True when the Rust crate is built with the `uac2` feature.
  bool get isAvailable => rust_uac2.uac2IsAvailable();

  /// Enumerates connected UAC 2.0 devices (DACs/AMPs).
  /// Returns an empty list when UAC2 is not available or no devices are found.
  List<Uac2DeviceInfo> listDevices() {
    if (!isAvailable) return [];
    try {
      return rust_uac2.uac2ListDevices();
    } catch (e) {
      debugPrint('Uac2Service.listDevices failed: $e');
      return [];
    }
  }
}

/// Re-export the generated device info type for convenience.
typedef Uac2DeviceInfo = rust_uac2.Uac2DeviceInfo;
