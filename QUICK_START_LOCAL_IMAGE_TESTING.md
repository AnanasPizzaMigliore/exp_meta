# Local Image Testing for OCR Detector - Quick Start Guide

## What Was Created

Three new components have been created to test the OCR detector with local images instead of camera feed:

### 1. **LocalImageReader.kt**
A utility class for reading and managing image files from device storage.

**Key Methods:**
- `readImageAsMat(imageName)` - Read image from cache as OpenCV Mat
- `readImageFromExternalStorage(path)` - Read from device external storage
- `listAvailableImages()` - List all test images
- `saveImageFromAssets(assetName, fileName)` - Copy images from app assets
- `clearTestImages()` - Delete all test images

### 2. **LocalImageOcrDetector.kt**
A detector that implements `IObjectDetectorHelper` and processes local images instead of camera feed.

**Key Methods:**
- `detectFromLocalImage(imageName)` - Run OCR on cached image
- `detectFromExternalImage(path)` - Run OCR on external storage image
- `getAvailableTestImages(callback)` - Get list of available images

### 3. **LocalImageTestViewModel.kt**
An MVVM ViewModel for managing test UI and state.

**Key Methods:**
- `initialize(context)` - Set up the detector
- `loadTestImageFromAssets(assetPath, fileName)` - Copy test images
- `detectImage(imageName)` - Run detection
- `getAvailableTestImages()` - List images
- `clearAllTestImages()` - Clean up

---

## Quick Start - 3 Steps

### Step 1: Add Test Images to Your Project

**Option A: Add to Assets**
```
app/src/main/assets/
└── test_images/
    ├── receipt.jpg
    ├── menu.jpg
    └── document.png
```

**Option B: Push to Device**
```bash
adb push test_image.jpg /sdcard/Pictures/test_image.jpg
```

### Step 2: Initialize and Use in Your Activity/Fragment

```kotlin
class TestActivity : AppCompatActivity() {
    private val viewModel: LocalImageTestViewModel by viewModels()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize
        viewModel.initialize(this)
        
        // Load test image from assets
        viewModel.loadTestImageFromAssets("test_images/receipt.jpg", "receipt.jpg")
        
        // Observe detection results
        viewModel.detectionResults.observe(this) { result ->
            if (result != null) {
                Log.d("Test", "Found ${result.objects.size} text objects")
                result.objects.forEach { obj ->
                    Log.d("Test", "- ${obj.label} at ${obj.bounds}")
                }
            }
        }
        
        // Run detection
        viewModel.detectImage("receipt.jpg")
    }
}
```

### Step 3: Test and Iterate

```kotlin
// Get detection summary
val summary = viewModel.getDetectionSummary()
// "Inference Time: 250ms\nObjects Found: 5\nImage Size: 1280x1280"

// List available images
viewModel.getAvailableTestImages()
viewModel.availableImages.observe(this) { images ->
    images.forEach { image -> Log.d("Test", "Available: $image") }
}

// Test multiple images
for (imageName in listOf("receipt.jpg", "menu.jpg", "document.png")) {
    viewModel.detectImage(imageName)
}
```

---

## Common Usage Patterns

### Pattern 1: Test with Asset Images
```kotlin
// Copy image from assets once
viewModel.loadTestImageFromAssets("test_images/receipt.jpg", "receipt.jpg")

// Use it multiple times
viewModel.detectImage("receipt.jpg")
viewModel.detectImage("receipt.jpg") // Run again
```

### Pattern 2: Test from External Storage
```kotlin
// Direct path to device storage
val imagePath = "/sdcard/DCIM/Camera/IMG_001.jpg"
viewModel.detectImageFromExternal(imagePath)
```

### Pattern 3: Create Direct Detector Instance
```kotlin
val detector = LocalImageOcrDetector(context)
detector.setObjectDetectedListener { result, _ ->
    Log.d("Test", "Objects: ${result.objects.size}")
}
detector.detectFromLocalImage("test.jpg")
```

### Pattern 4: Switch Between Camera and Test Mode
```kotlin
class MainActivity : AppCompatActivity() {
    private var detector: IObjectDetectorHelper = ExecutorchOcrDetector(this)
    private var testDetector: LocalImageOcrDetector? = null
    
    fun enableTestMode() {
        if (testDetector == null) {
            testDetector = LocalImageOcrDetector(this)
        }
        detector = testDetector!!
    }
    
    fun disableTestMode() {
        detector = ExecutorchOcrDetector(this)
    }
}
```

---

## Monitor Test Progress

### View Logs
```bash
adb logcat | grep -E "LocalImage|OCR" | tail -20
```

### Check Available Images
```bash
adb shell ls /data/data/com.meta.pixelandtexel.scanner/cache/test_images/
```

### View Detection Stats
Observe the `detectionState` and `detectionResults` LiveData:
```kotlin
viewModel.detectionState.observe(this) { state ->
    when (state) {
        DetectionState.Processing -> Log.d("Test", "Running OCR...")
        DetectionState.Success -> Log.d("Test", "OCR complete")
        DetectionState.Error -> Log.d("Test", "OCR failed")
    }
}
```

---

## Verify Installation

Check that all files are created:
```
app/src/main/java/com/meta/pixelandtexel/scanner/objectdetection/detector/
├── LocalImageReader.kt
├── LocalImageOcrDetector.kt
├── LocalImageTestViewModel.kt
└── LOCAL_IMAGE_TESTING.md (documentation)
```

---

## Example: Complete Test Activity

```kotlin
class OCRTestActivity : AppCompatActivity() {
    private val viewModel: LocalImageTestViewModel by viewModels()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)
        
        // 1. Initialize detector
        viewModel.initialize(this)
        
        // 2. Load test images
        lifecycleScope.launch {
            viewModel.loadTestImageFromAssets(
                "test_images/receipt.jpg", 
                "receipt.jpg"
            )
        }
        
        // 3. Observe results
        viewModel.detectionResults.observe(this) { result ->
            if (result != null) {
                displayResults(result)
            }
        }
        
        viewModel.detectionState.observe(this) { state ->
            updateUI(state)
        }
        
        // 4. Start detection
        findViewById<Button>(R.id.btn_test).setOnClickListener {
            viewModel.detectImage("receipt.jpg")
        }
    }
    
    private fun displayResults(result: DetectedObjectsResult) {
        val summary = buildString {
            appendLine("Inference: ${result.inferenceTime}ms")
            appendLine("Objects: ${result.objects.size}")
            result.objects.forEach { obj ->
                appendLine("  • ${obj.label}")
            }
        }
        Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
    }
    
    private fun updateUI(state: LocalImageTestViewModel.DetectionState) {
        val statusText = when (state) {
            LocalImageTestViewModel.DetectionState.Idle -> "Ready"
            LocalImageTestViewModel.DetectionState.Loading -> "Loading..."
            LocalImageTestViewModel.DetectionState.Processing -> "Processing..."
            LocalImageTestViewModel.DetectionState.Success -> "Complete"
            LocalImageTestViewModel.DetectionState.Error -> "Error"
        }
        findViewById<TextView>(R.id.tv_status).text = statusText
    }
}
```

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| "Image file not found" | Check `logcat` for actual path, use `adb shell` to verify file exists |
| "Failed to load image as Mat" | Image may be corrupted, try a different image file |
| No detection results | Verify OCRManager is initialized, check if image has readable text |
| Memory warnings | Clear test images with `viewModel.clearAllTestImages()` |

---

## File Locations

**Source Files:**
- `LocalImageReader.kt` - Image file I/O operations
- `LocalImageOcrDetector.kt` - OCR detection implementation  
- `LocalImageTestViewModel.kt` - UI state management

**Test Images Location (on device):**
- `/data/data/com.meta.pixelandtexel.scanner/cache/test_images/`

**Supported Image Formats:**
- JPG / JPEG
- PNG
- BMP
- WEBP

---

## Next Steps

1. Create test images with readable text
2. Add them to your assets folder
3. Initialize the ViewModel in your Activity
4. Call `detectImage()` with test image names
5. Observe results via LiveData

Done! You can now test OCR without needing camera access.

