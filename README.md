# 🥽 Meta Spatial Scanner

[![Android SDK](https://img.shields.io/badge/Android-34D058?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/)
[![Meta Spatial SDK](https://img.shields.io/badge/Meta%20Spatial%20SDK-0.11.0-blue?style=flat-square)](https://developers.meta.com/horizon/develop/spatial-sdk/)
[![ExecuTorch](https://img.shields.io/badge/Powered%20by-ExecuTorch-orange?style=flat-square)](https://pytorch.org/executorch/)

**Meta Spatial Scanner** is a mixed-reality showcase app built with the [Meta Spatial SDK](https://developers.meta.com/horizon/develop/spatial-sdk/). It demonstrates how to combine the **Passthrough Camera API** with high-performance, on-device AI to create context-aware spatial experiences.

This app allows users to scan their physical environment, detect real-world objects using **ExecuTorch-powered OCR and Vision**, and interact with digital twins or AI-generated information in 3D space.

---

## 🚀 Key Features

- **⚡ On-Device AI (ExecuTorch)** – Real-time object recognition and OCR (Optical Character Recognition) running locally on the Quest 3/3S using PyTorch's ExecuTorch framework.
- **🗣️ Voice Activation (KWS)** – Hands-free interaction via **Keyword Spotting**. Use voice commands to trigger scans or interact with the UI.
- **👁️ Passthrough Camera API** – Seamless access to the Quest's forward-facing cameras for low-latency computer vision inference.
- **🧠 Llama 3.2 11B Vision** – (Cloud fallback) Integration with AWS Bedrock to provide deep, multimodal insights about objects detected in the user's field of view.
- **📦 Curated 3D Content** – Automatic spawning of interactive 3D models (Fridge, TV, Phone) when specific real-world products are identified.

---

## 🏗️ App Architecture

The project is structured into modular components to facilitate reuse in other Spatial SDK projects:

### 1. Object Detection & Vision (`.objectdetection`)
- **`ExecutorchOcrDetector`**: The primary engine for analyzing camera frames locally.
- **`VoiceActivator`**: Manages the Keyword Spotting (KWS) lifecycle for voice-driven actions.
- **`ObjectDetectionFeature`**: A `SpatialFeature` that orchestrates the camera session and vision pipeline.

### 2. ExecuTorch Pipeline (`.executorch`)
- **`OCRManager`**: Handles the detection and recognition models.
- **`DateParser`**: Intelligent timestamp extraction for identifying product age or expiration dates.
- **`Pre/Post Processors`**: Optimized image manipulation and tensor handling for mobile NPU/GPU.

### 3. Spatial UI & Interaction
- **Interaction SDK (ISDK)**: Direct touch and ray-cast manipulation of 3D panels.
- **Wrist-Attached Controls**: Contextual UI anchored to the user's hand for quick access to scanning toggles.

---

## 🛠️ Getting Started

### Prerequisites
- **Meta Quest 3 or 3S** (Required for Passthrough Camera access).
- **Android Studio Koala+** with **NDK** installed (specified in `build.gradle.kts`).
- **Meta Spatial Editor** for scene modifications.

### Setup Steps
1. **Clone the Repo**:
   ```bash
   git clone https://github.com/AnanasPizzaMigliore/exp_meta.git
   ```
2. **Configure Secrets**:
   Copy `secrets.properties.example` to `secrets.properties` and add your AWS credentials if using the Llama 3.2 Vision features.
3. **Build & Deploy**:
   Open in Android Studio and run the `app` module on your Quest device.

---

## 🧪 Testing
The project includes a robust **Local Image Testing Framework** to test the ExecuTorch models without needing the physical headset every time.
See [README_LOCAL_IMAGE_TESTING.md](./README_LOCAL_IMAGE_TESTING.md) for details.

---

## 📜 License
This project is licensed under the [MIT License](LICENSE).

---

<p align="center">
  Built with ❤️ for the Meta Quest Developer Community.
</p>
