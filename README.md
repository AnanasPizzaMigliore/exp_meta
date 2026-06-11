# 🥽 Meta Spatial Scanner

[![Android SDK](https://img.shields.io/badge/Android-34D058?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/)
[![Meta Spatial SDK](https://img.shields.io/badge/Meta%20Spatial%20SDK-0.13.0-blue?style=flat-square)](https://developers.meta.com/horizon/develop/spatial-sdk/)
[![ExecuTorch](https://img.shields.io/badge/Powered%20by-ExecuTorch-orange?style=flat-square)](https://pytorch.org/executorch/)
[![Sherpa-ONNX](https://img.shields.io/badge/Sherpa--ONNX-1.12.29-purple?style=flat-square)](https://github.com/k2-fsa/sherpa-onnx)

**Meta Spatial Scanner** is a mixed-reality showcase app built with the [Meta Spatial SDK](https://developers.meta.com/horizon/develop/spatial-sdk/). It demonstrates how to combine the **Passthrough Camera API** with high-performance, on-device AI to create context-aware spatial experiences.

This app allows users to scan their physical environment, detect real-world objects using **ExecuTorch-powered OCR and Vision**, and interact with digital twins or AI-generated information in 3D space.

---

## 🚀 Key Features

- **⚡ On-Device AI (ExecuTorch)** – Real-time object recognition and OCR running locally on the Quest 3/3S using PyTorch's ExecuTorch framework.
- **🗣️ Voice Activation (KWS)** – Hands-free interaction via **Keyword Spotting** powered by [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx) (Zipformer2 transducer). Supported keywords: `SCAN`, `START`, `STOP`, `CANCEL`, `RESCAN`.
- **🔊 On-Device TTS (Piper)** – Text-to-speech feedback synthesized locally using a **Piper VITS** model via Sherpa-ONNX `OfflineTts`, with no network round-trip.
- **👁️ Passthrough Camera API** – Seamless access to the Quest's forward-facing cameras for low-latency computer vision inference.
- **🧠 Llama 3.2 11B Vision** – (Cloud fallback) Integration with AWS Bedrock for deep, multimodal insights about detected objects.
- **📦 Curated 3D Content** – Automatic spawning of interactive 3D models (Fridge, TV, Phone) when specific real-world products are identified.
- **📊 Ablation Benchmarking** – Built-in **Gated vs. Continuous inference** experiment framework for measuring the real-world latency and power impact of the visual-proxy motion gate.

---

## 🏗️ App Architecture

The project is structured into modular components to facilitate reuse in other Spatial SDK projects:

### 1. Object Detection & Vision (`.objectdetection`)
- **`ExecutorchOcrDetector`**: Primary on-device engine — runs ExecuTorch OCR models and synthesizes spoken results via Piper TTS (Sherpa-ONNX).
- **`VoiceActivator`**: Manages the Sherpa-ONNX `KeywordSpotter` lifecycle for voice-driven scan triggers.
- **`ObjectDetectionFeature`**: A `SpatialFeature` that orchestrates the camera session and vision pipeline. Exposes `continuousMode` to bypass the visual-proxy gate for ablation experiments, plus per-session `framesTotal`/`framesPassedGate` counters.

### 2. ExecuTorch Pipeline (`.executorch`)
- **`OCRManager`**: Handles the detection and recognition models.
- **`DateParser`**: Intelligent timestamp extraction for identifying product age or expiration dates.
- **Pre/Post Processors**: Optimized image manipulation and tensor handling for mobile NPU/GPU.

### 3. Spatial UI & Interaction
- **Interaction SDK (ISDK)**: Direct touch and ray-cast manipulation of 3D panels.
- **Wrist-Attached Controls**: Contextual UI anchored to the user's hand for quick access to scanning toggles and ablation session buttons (Gated / Continuous).

### 4. Benchmarking (`.benchmark`)
- **`AblationSessionLogger`**: Records per-scan latency, frame counts, and battery draw across two experiment modes:
  - **Gated** – Default pipeline: visual-proxy motion gate + KWS/kinematic trigger.
  - **Continuous** – Baseline: camera always running, no gate, back-to-back inference.

---

## 🛠️ Getting Started

### Prerequisites
- **Meta Quest 3 or 3S** (Required for Passthrough Camera access).
- **Android Studio Koala+** with **NDK 27.0.12077973** installed.
- **Meta Spatial Editor v13** for scene modifications.

### Setup Steps
1. **Clone the Repo**:
   ```bash
   git clone https://github.com/AnanasPizzaMigliore/exp_meta.git
   ```
2. **Configure Secrets**:
   Copy `secrets.properties.example` to `secrets.properties` and fill in your AWS credentials if using the Llama 3.2 Vision features.
3. **Build & Deploy**:
   Open in Android Studio and run the `app` module on your Quest device.

---

## 🧪 Testing
The project includes a **Local Image Testing Framework** to validate the ExecuTorch models without needing the physical headset every time.
See [README_LOCAL_IMAGE_TESTING.md](./README_LOCAL_IMAGE_TESTING.md) for details.

---

## 📜 License
This project is licensed under the [MIT License](LICENSE).

---

<p align="center">
  Built with ❤️ for the Meta Quest Developer Community.
</p>
