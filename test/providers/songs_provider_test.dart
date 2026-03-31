import 'package:flutter_test/flutter_test.dart';
import 'package:flick/providers/songs_provider.dart';

void main() {
  group('SongFileTypeFilter.ogg', () {
    test('matches ogg container formats and opus streams', () {
      expect(SongFileTypeFilter.ogg.matches('OGG'), isTrue);
      expect(SongFileTypeFilter.ogg.matches('ogx'), isTrue);
      expect(SongFileTypeFilter.ogg.matches('OpUs'), isTrue);
      expect(SongFileTypeFilter.ogg.matches('vorbis'), isTrue);
      expect(SongFileTypeFilter.ogg.matches('oga'), isTrue);
    });

    test('does not match unrelated formats', () {
      expect(SongFileTypeFilter.ogg.matches('FLAC'), isFalse);
      expect(SongFileTypeFilter.ogg.matches('M4A'), isFalse);
    });
  });
}
