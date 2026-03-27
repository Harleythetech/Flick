import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:flick/core/theme/app_colors.dart';
import 'package:flick/core/theme/adaptive_color_provider.dart';
import 'package:flick/core/constants/app_constants.dart';
import 'package:flick/providers/providers.dart';
import 'package:flick/services/uac2_service.dart';

class Uac2VolumeControl extends ConsumerStatefulWidget {
  const Uac2VolumeControl({super.key});

  @override
  ConsumerState<Uac2VolumeControl> createState() => _Uac2VolumeControlState();
}

class _Uac2VolumeControlState extends ConsumerState<Uac2VolumeControl> {
  double _volume = 1.0;
  bool _muted = false;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadVolumeState();
  }

  Future<void> _loadVolumeState() async {
    final deviceStatusNotifier = ref.read(uac2DeviceStatusProvider);
    final volume = await deviceStatusNotifier.getVolume();
    final muted = await deviceStatusNotifier.getMute();

    if (mounted) {
      setState(() {
        _volume = volume ?? 1.0;
        _muted = muted ?? false;
        _loading = false;
      });
    }
  }

  Future<void> _setVolume(double volume) async {
    setState(() => _volume = volume);
    final deviceStatusNotifier = ref.read(uac2DeviceStatusProvider);
    await deviceStatusNotifier.setVolume(volume);
  }

  Future<void> _toggleMute() async {
    final newMuted = !_muted;
    setState(() => _muted = newMuted);
    final deviceStatusNotifier = ref.read(uac2DeviceStatusProvider);
    await deviceStatusNotifier.setMute(newMuted);
  }

  @override
  Widget build(BuildContext context) {
    final deviceStatusNotifier = ref.watch(uac2DeviceStatusProvider);
    final deviceStatus = deviceStatusNotifier.status;
    final effectiveVolume = deviceStatus?.volume ?? _volume;
    final effectiveMuted = deviceStatus?.muted ?? _muted;

    if (deviceStatus == null ||
        deviceStatus.state == Uac2State.idle ||
        !deviceStatus.hasVolumeControl) {
      return const SizedBox.shrink();
    }

    if (_loading) {
      return const Center(
        child: SizedBox(
          width: 20,
          height: 20,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
      );
    }

    return Container(
      padding: const EdgeInsets.all(AppConstants.spacingMd),
      decoration: BoxDecoration(
        color: AppColors.surface.withValues(alpha: 0.6),
        borderRadius: BorderRadius.circular(AppConstants.radiusLg),
        border: Border.all(color: AppColors.glassBorder),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                LucideIcons.volume2,
                color: context.adaptiveTextSecondary,
                size: 20,
              ),
              const SizedBox(width: AppConstants.spacingSm),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      deviceStatus.isExternalRoute
                          ? 'USB Route Volume'
                          : 'Device DAC Volume',
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                            color: context.adaptiveTextPrimary,
                            fontWeight: FontWeight.w600,
                          ),
                    ),
                    if ((deviceStatus.routeLabel?.isNotEmpty ?? false))
                      Text(
                        deviceStatus.routeLabel!,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              color: context.adaptiveTextTertiary,
                            ),
                      ),
                  ],
                ),
              ),
              IconButton(
                icon: Icon(
                  effectiveMuted ? LucideIcons.volumeX : LucideIcons.volume2,
                  size: 20,
                ),
                onPressed: _toggleMute,
                color: effectiveMuted
                    ? Colors.red.shade400
                    : context.adaptiveTextSecondary,
                tooltip: effectiveMuted ? 'Unmute' : 'Mute',
              ),
            ],
          ),
          const SizedBox(height: AppConstants.spacingSm),
          Row(
            children: [
              Icon(
                LucideIcons.volume1,
                color: context.adaptiveTextTertiary,
                size: 16,
              ),
              Expanded(
                child: Slider(
                  value: effectiveVolume,
                  min: 0.0,
                  max: 1.0,
                  divisions: 100,
                  label: '${(effectiveVolume * 100).round()}%',
                  onChanged: effectiveMuted ? null : _setVolume,
                  activeColor: AppColors.accent,
                  inactiveColor: AppColors.textTertiary.withValues(alpha: 0.3),
                ),
              ),
              Icon(
                LucideIcons.volume2,
                color: context.adaptiveTextTertiary,
                size: 16,
              ),
              const SizedBox(width: AppConstants.spacingSm),
              SizedBox(
                width: 40,
                child: Text(
                  '${(effectiveVolume * 100).round()}%',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: context.adaptiveTextSecondary,
                        fontWeight: FontWeight.w500,
                      ),
                  textAlign: TextAlign.right,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
