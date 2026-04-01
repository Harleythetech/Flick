import 'package:flutter_test/flutter_test.dart';
import 'package:flick/services/player_service.dart';

void main() {
  group('canonicalPlaybackFileType', () {
    test('prefers the real file extension over stale stored file type', () {
      expect(
        canonicalPlaybackFileType(
          fileType: 'M4A',
          filePath: '/music/library/example.ogg',
        ),
        'ogg',
      );
    });

    test('normalizes mime-style and ogg-family values', () {
      expect(
        canonicalPlaybackFileType(fileType: 'audio/ogg', filePath: null),
        'ogg',
      );
      expect(
        canonicalPlaybackFileType(fileType: 'audio/mp4', filePath: null),
        'm4a',
      );
      expect(
        canonicalPlaybackFileType(fileType: 'OpUs', filePath: null),
        'opus',
      );
      expect(
        canonicalPlaybackFileType(fileType: '.oga', filePath: null),
        'ogg',
      );
    });
  });

  group('shouldOptimisticallySyncSkipForLoopMode', () {
    test('disables optimistic UI skip sync for repeat-one only', () {
      expect(shouldOptimisticallySyncSkipForLoopMode(LoopMode.off), isTrue);
      expect(shouldOptimisticallySyncSkipForLoopMode(LoopMode.all), isTrue);
      expect(shouldOptimisticallySyncSkipForLoopMode(LoopMode.one), isFalse);
    });
  });

  group('shouldHandleManualCompletion', () {
    test('keeps manual completion handling for the Rust backend', () {
      expect(
        shouldHandleManualCompletion(
          usingRustBackend: true,
          loopMode: LoopMode.off,
        ),
        isTrue,
      );
      expect(
        shouldHandleManualCompletion(
          usingRustBackend: true,
          loopMode: LoopMode.one,
        ),
        isTrue,
      );
      expect(
        shouldHandleManualCompletion(
          usingRustBackend: true,
          loopMode: LoopMode.all,
        ),
        isTrue,
      );
    });

    test('lets just_audio own repeat-one and repeat-all completion', () {
      expect(
        shouldHandleManualCompletion(
          usingRustBackend: false,
          loopMode: LoopMode.off,
        ),
        isTrue,
      );
      expect(
        shouldHandleManualCompletion(
          usingRustBackend: false,
          loopMode: LoopMode.one,
        ),
        isFalse,
      );
      expect(
        shouldHandleManualCompletion(
          usingRustBackend: false,
          loopMode: LoopMode.all,
        ),
        isFalse,
      );
    });
  });
}
