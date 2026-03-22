use crate::uac2::{AudioFormat, BitDepth, DeviceCapabilities, SampleRate, Uac2Error};

pub struct FormatNegotiationStrategy {
    prefer_quality: bool,
}

impl FormatNegotiationStrategy {
    pub fn new(prefer_quality: bool) -> Self {
        Self { prefer_quality }
    }

    pub fn quality_first() -> Self {
        Self { prefer_quality: true }
    }

    pub fn compatibility_first() -> Self {
        Self { prefer_quality: false }
    }
}

pub struct FormatNegotiationEngine {
    strategy: FormatNegotiationStrategy,
}

impl FormatNegotiationEngine {
    pub fn new(strategy: FormatNegotiationStrategy) -> Self {
        Self { strategy }
    }

    pub fn negotiate(
        &self,
        source_format: &AudioFormat,
        device_caps: &DeviceCapabilities,
    ) -> Result<AudioFormat, Uac2Error> {
        let sample_rate = self.negotiate_sample_rate(source_format, device_caps)?;
        let bit_depth = self.negotiate_bit_depth(source_format, device_caps)?;
        let channels = self.negotiate_channels(source_format, device_caps)?;

        Ok(AudioFormat {
            sample_rate,
            bit_depth,
            channels,
            format_type: source_format.format_type,
        })
    }

    fn negotiate_sample_rate(
        &self,
        source: &AudioFormat,
        caps: &DeviceCapabilities,
    ) -> Result<SampleRate, Uac2Error> {
        if caps.supported_sample_rates.contains(&source.sample_rate) {
            return Ok(source.sample_rate);
        }

        if self.strategy.prefer_quality {
            caps.supported_sample_rates
                .iter()
                .filter(|&&rate| rate.hz() >= source.sample_rate.hz())
                .min_by_key(|rate| rate.hz())
                .or_else(|| caps.supported_sample_rates.iter().max_by_key(|rate| rate.hz()))
                .copied()
                .ok_or(Uac2Error::NoSupportedFormat)
        } else {
            caps.supported_sample_rates
                .iter()
                .min_by_key(|rate| (rate.hz() as i32 - source.sample_rate.hz() as i32).abs())
                .copied()
                .ok_or(Uac2Error::NoSupportedFormat)
        }
    }

    fn negotiate_bit_depth(
        &self,
        source: &AudioFormat,
        caps: &DeviceCapabilities,
    ) -> Result<BitDepth, Uac2Error> {
        if caps.supported_bit_depths.contains(&source.bit_depth) {
            return Ok(source.bit_depth);
        }

        if self.strategy.prefer_quality {
            caps.supported_bit_depths
                .iter()
                .filter(|&&depth| Self::bit_depth_value(depth) >= Self::bit_depth_value(source.bit_depth))
                .min_by_key(|depth| Self::bit_depth_value(**depth))
                .or_else(|| caps.supported_bit_depths.iter().max_by_key(|depth| Self::bit_depth_value(**depth)))
                .copied()
                .ok_or(Uac2Error::NoSupportedFormat)
        } else {
            caps.supported_bit_depths
                .iter()
                .min_by_key(|depth| {
                    (Self::bit_depth_value(**depth) as i32 - Self::bit_depth_value(source.bit_depth) as i32).abs()
                })
                .copied()
                .ok_or(Uac2Error::NoSupportedFormat)
        }
    }

    fn negotiate_channels(
        &self,
        source: &AudioFormat,
        caps: &DeviceCapabilities,
    ) -> Result<usize, Uac2Error> {
        if caps.supported_channels.contains(&source.channels) {
            return Ok(source.channels);
        }

        if self.strategy.prefer_quality {
            caps.supported_channels
                .iter()
                .filter(|&&ch| ch >= source.channels)
                .min()
                .or_else(|| caps.supported_channels.iter().max())
                .copied()
                .ok_or(Uac2Error::NoSupportedFormat)
        } else {
            caps.supported_channels
                .iter()
                .min_by_key(|ch| (**ch as i32 - source.channels as i32).abs())
                .copied()
                .ok_or(Uac2Error::NoSupportedFormat)
        }
    }

    fn bit_depth_value(depth: BitDepth) -> u8 {
        match depth {
            BitDepth::Bit16 => 16,
            BitDepth::Bit24 => 24,
            BitDepth::Bit32 => 32,
        }
    }
}

pub struct FormatMismatchHandler;

impl FormatMismatchHandler {
    pub fn can_handle(source: &AudioFormat, target: &AudioFormat) -> bool {
        Self::can_convert_sample_rate(source, target)
            && Self::can_convert_bit_depth(source, target)
            && Self::can_convert_channels(source, target)
    }

    pub fn requires_conversion(source: &AudioFormat, target: &AudioFormat) -> bool {
        source.sample_rate != target.sample_rate
            || source.bit_depth != target.bit_depth
            || source.channels != target.channels
    }

    fn can_convert_sample_rate(source: &AudioFormat, target: &AudioFormat) -> bool {
        let ratio = source.sample_rate.hz() as f64 / target.sample_rate.hz() as f64;
        ratio >= 0.25 && ratio <= 4.0
    }

    fn can_convert_bit_depth(_source: &AudioFormat, _target: &AudioFormat) -> bool {
        true
    }

    fn can_convert_channels(source: &AudioFormat, target: &AudioFormat) -> bool {
        (source.channels == 1 && target.channels == 2)
            || (source.channels == 2 && target.channels == 1)
            || source.channels == target.channels
    }
}
