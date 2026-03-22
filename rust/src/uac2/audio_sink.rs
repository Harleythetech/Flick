use crate::audio::source::SourceProvider;
use crate::uac2::{AudioFormat, AudioPipeline, Uac2Device, Uac2Error};
use rusb::UsbContext;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};

pub struct Uac2AudioSink<T: UsbContext> {
    device: Arc<Uac2Device<T>>,
    source_format: AudioFormat,
    target_format: AudioFormat,
    pipeline: Arc<AudioPipeline>,
    running: Arc<AtomicBool>,
    thread_handle: Option<JoinHandle<()>>,
}

impl<T: UsbContext + Send + 'static> Uac2AudioSink<T> {
    pub fn new(device: Arc<Uac2Device<T>>, format: AudioFormat) -> Result<Self, Uac2Error> {
        let sample_rate = format.sample_rates.first()
            .ok_or(Uac2Error::InvalidDescriptor("No sample rates".to_string()))?;
        
        let target_format = AudioFormat::new(
            vec![*sample_rate],
            format.bit_depth,
            format.channels,
            format.format_type,
        )?;

        let pipeline = Arc::new(AudioPipeline::new(
            format.clone(),
            target_format.clone(),
            65536,
        )?);

        let running = Arc::new(AtomicBool::new(false));

        Ok(Self {
            device,
            source_format: format,
            target_format,
            pipeline,
            running,
            thread_handle: None,
        })
    }

    pub fn start(&mut self, source_provider: Arc<parking_lot::Mutex<SourceProvider>>) -> Result<(), Uac2Error> {
        if self.running.load(Ordering::Acquire) {
            return Ok(());
        }

        self.running.store(true, Ordering::Release);
        let running = Arc::clone(&self.running);
        let pipeline = Arc::clone(&self.pipeline);

        let handle = thread::Builder::new()
            .name("uac2-audio-sink".to_string())
            .spawn(move || {
                Self::audio_thread(running, pipeline, source_provider);
            })
            .map_err(|e| Uac2Error::ThreadSpawn(e.to_string()))?;

        self.thread_handle = Some(handle);
        Ok(())
    }

    pub fn stop(&mut self) -> Result<(), Uac2Error> {
        if !self.running.load(Ordering::Acquire) {
            return Ok(());
        }

        self.running.store(false, Ordering::Release);

        if let Some(handle) = self.thread_handle.take() {
            handle.join().map_err(|_| Uac2Error::ThreadJoin)?;
        }

        Ok(())
    }

    pub fn device(&self) -> &Uac2Device<T> {
        &self.device
    }

    pub fn source_format(&self) -> &AudioFormat {
        &self.source_format
    }

    pub fn target_format(&self) -> &AudioFormat {
        &self.target_format
    }

    pub fn is_bit_perfect(&self) -> bool {
        self.pipeline.is_passthrough()
    }

    fn audio_thread(
        running: Arc<AtomicBool>,
        pipeline: Arc<AudioPipeline>,
        source_provider: Arc<parking_lot::Mutex<SourceProvider>>,
    ) {
        let mut output_buffer = vec![0u8; 4096];

        while running.load(Ordering::Acquire) {
            let available = pipeline.available();
            
            if available < output_buffer.len() {
                let mut sources = source_provider.lock();
                
                if let Some(current) = sources.current_mut() {
                    let samples_needed = 4096;
                    let mut sample_buffer = vec![0f32; samples_needed];
                    
                    let read = current.read(&mut sample_buffer);
                    if read > 0 {
                        let bytes = unsafe {
                            std::slice::from_raw_parts(
                                sample_buffer.as_ptr() as *const u8,
                                read * std::mem::size_of::<f32>(),
                            )
                        };
                        
                        if let Err(e) = pipeline.process(bytes) {
                            eprintln!("UAC2 pipeline error: {:?}", e);
                            break;
                        }
                    }
                }
                
                drop(sources);
            }

            if pipeline.available() >= output_buffer.len() {
                match pipeline.read(&mut output_buffer) {
                    Ok(_read) => {
                    }
                    Err(e) => {
                        eprintln!("UAC2 read error: {:?}", e);
                        break;
                    }
                }
            }
            
            std::thread::sleep(std::time::Duration::from_micros(100));
        }
    }
}

impl<T: UsbContext> Drop for Uac2AudioSink<T> {
    fn drop(&mut self) {
        let _ = self.stop();
    }
}
