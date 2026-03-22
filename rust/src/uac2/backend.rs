use crate::audio::source::SourceProvider;
use crate::uac2::{AudioFormat, Uac2AudioSink, Uac2Device, Uac2Error};
use parking_lot::Mutex;
use rusb::UsbContext;
use std::sync::Arc;

pub trait AudioBackend: Send + Sync {
    fn start(&mut self, source_provider: Arc<Mutex<SourceProvider>>) -> Result<(), String>;
    fn stop(&mut self) -> Result<(), String>;
    fn is_active(&self) -> bool;
    fn name(&self) -> &str;
}

pub struct Uac2Backend<T: UsbContext + Send + 'static> {
    sink: Option<Uac2AudioSink<T>>,
    device: Arc<Uac2Device<T>>,
    format: AudioFormat,
    active: bool,
}

impl<T: UsbContext + Send + 'static> Uac2Backend<T> {
    pub fn new(device: Arc<Uac2Device<T>>, format: AudioFormat) -> Result<Self, Uac2Error> {
        Ok(Self {
            sink: None,
            device,
            format,
            active: false,
        })
    }

    pub fn device(&self) -> &Uac2Device<T> {
        &self.device
    }

    pub fn format(&self) -> &AudioFormat {
        &self.format
    }
}

impl<T: UsbContext + Send + 'static> AudioBackend for Uac2Backend<T> {
    fn start(&mut self, source_provider: Arc<Mutex<SourceProvider>>) -> Result<(), String> {
        if self.active {
            return Ok(());
        }

        let mut sink = Uac2AudioSink::new(Arc::clone(&self.device), self.format.clone())
            .map_err(|e| format!("Failed to create UAC2 sink: {:?}", e))?;

        sink.start(source_provider)
            .map_err(|e| format!("Failed to start UAC2 sink: {:?}", e))?;

        self.sink = Some(sink);
        self.active = true;

        Ok(())
    }

    fn stop(&mut self) -> Result<(), String> {
        if !self.active {
            return Ok(());
        }

        if let Some(mut sink) = self.sink.take() {
            sink.stop()
                .map_err(|e| format!("Failed to stop UAC2 sink: {:?}", e))?;
        }

        self.active = false;
        Ok(())
    }

    fn is_active(&self) -> bool {
        self.active
    }

    fn name(&self) -> &str {
        "UAC2"
    }
}
