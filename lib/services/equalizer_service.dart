import 'dart:io';
import 'package:flutter/services.dart';
import 'package:flick/providers/equalizer_provider.dart';
import 'package:flick/services/player_service.dart';
import 'package:flick/src/rust/api/audio_api.dart' as rust_audio;

const MethodChannel _androidEqualizerChannel = MethodChannel(
  'com.ultraelectronica.flick/equalizer',
);

EqualizerState _lastRequestedState = EqualizerState.initial();

/// Applies EQ and dynamics state to the active audio backend.
/// Rust engine: graphic EQ, compressor, and limiter are applied natively.
/// Android: uses the native AudioEffect API for EQ only.
Future<void> applyEqualizer(EqualizerState state) async {
  _lastRequestedState = _snapshotState(state);

  final useGraphic = state.mode == EqMode.graphic;
  final gains = useGraphic
      ? state.graphicGainsDb
      : _parametricToGraphicGains(state.parametricBands);

  if (gains.length != 10) return;

  // Android: use native AudioEffect API with session ID from just_audio
  if (Platform.isAndroid) {
    final sessionId = PlayerService().androidAudioSessionId;
    if (sessionId == null && state.enabled) return;
    try {
      await _androidEqualizerChannel.invokeMethod('setEqualizer', {
        'enabled': state.enabled,
        'gainsDb': gains,
        'audioSessionId': sessionId,
      });
    } catch (_) {}
    return;
  }

  // Desktop: use Rust audio engine
  if (!rust_audio.audioIsNativeAvailable() ||
      !rust_audio.audioIsInitialized()) {
    return;
  }
  try {
    rust_audio.audioSetEqualizer(
      enabled: state.enabled,
      gainsDb: List<double>.from(gains),
    );
    await rust_audio.audioSetCompressor(
      enabled: state.enabled && state.compressor.enabled,
      thresholdDb: state.compressor.thresholdDb,
      ratio: state.compressor.ratio,
      attackMs: state.compressor.attackMs,
      releaseMs: state.compressor.releaseMs,
      makeupGainDb: state.compressor.makeupGainDb,
    );
    await rust_audio.audioSetLimiter(
      enabled: state.enabled && state.limiter.enabled,
      inputGainDb: state.limiter.inputGainDb,
      ceilingDb: state.limiter.ceilingDb,
      releaseMs: state.limiter.releaseMs,
    );
  } catch (_) {}
}

Future<void> reapplyEqualizer() async {
  await applyEqualizer(_lastRequestedState);
}

/// Map parametric bands to 10-band gains for Rust engine (graphic-only).
List<double> _parametricToGraphicGains(List<ParametricBand> bands) {
  final out = List<double>.filled(10, 0.0, growable: false);
  final freqs = EqualizerState.defaultGraphicFrequenciesHz;
  for (var i = 0; i < 10; i++) {
    final f = freqs[i];
    for (final b in bands) {
      if (!b.enabled) continue;
      final dist = (b.frequencyHz - f).abs();
      final bw = b.frequencyHz / b.q;
      if (dist < bw) {
        final t = dist / bw;
        out[i] = out[i] + b.gainDb * (1.0 - t);
      }
    }
  }
  return out;
}

EqualizerState _snapshotState(EqualizerState state) {
  return state.copyWith(
    graphicGainsDb: List<double>.of(state.graphicGainsDb, growable: false),
    parametricBands: List<ParametricBand>.of(
      state.parametricBands,
      growable: false,
    ),
    compressor: state.compressor.copyWith(),
    limiter: state.limiter.copyWith(),
  );
}
