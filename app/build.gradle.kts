import java.util.Properties

// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.meta.spatial.plugin)
  alias(libs.plugins.jetbrains.kotlin.plugin.compose)
}

android {
  namespace = "com.meta.pixelandtexel.scanner"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.meta.pixelandtexel.scanner"
    minSdk = 34
    //noinspection ExpiredTargetSdkVersion
    targetSdk = 36
    versionCode = 12
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Pass our aws credentials to the BuildConfig
    val awsRegion = getLocalProperty("AWS_REGION", project)
    val awsAccessKey = getLocalProperty("AWS_BEDROCK_ACCESS_KEY", project)
    val awsSecretKey = getLocalProperty("AWS_BEDROCK_SECRET_KEY", project)
    buildConfigField("String", "AWS_REGION", "\"$awsRegion\"")
    buildConfigField("String", "AWS_ACCESS_KEY", "\"$awsAccessKey\"")
    buildConfigField("String", "AWS_SECRET_KEY", "\"$awsSecretKey\"")
  }

  packaging {
    resources.excludes.add("META-INF/LICENSE")
    resources.excludes.add("META-INF/INDEX.LIST")
    resources.excludes.add("META-INF/io.netty.versions.properties")
    jniLibs {
          pickFirsts.add("lib/x86/libc++_shared.so")
          pickFirsts.add("lib/x86_64/libc++_shared.so")
          pickFirsts.add("lib/armeabi-v7a/libc++_shared.so")
          pickFirsts.add("lib/arm64-v8a/libc++_shared.so")
      }
  }

  lint { abortOnError = false }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }

  androidResources { noCompress.addAll(listOf(".tflite", ".lite", ".caffemodel")) }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)

  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.navigation.compose)

  // Jetpack Compose

  implementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(platform(libs.androidx.compose.bom))
  // material design 3
  implementation(libs.androidx.material3)
  // Android Studio Preview support
  implementation(libs.androidx.ui.tooling.preview)
  debugImplementation(libs.androidx.ui.tooling)
  // View Model support
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // Activity integration
  implementation(libs.androidx.activity.compose)

  // Meta Spatial SDK libs
  implementation(libs.meta.spatial.sdk.base)
  implementation(libs.meta.spatial.sdk.compose)
  implementation(libs.meta.spatial.sdk.isdk)
  implementation(libs.meta.spatial.sdk.toolkit)
  implementation(libs.meta.spatial.sdk.uiset)
  implementation(libs.meta.spatial.sdk.vr)

  // Mediapipe CV object detection
  implementation(libs.google.mediapipe.tasks.vision)
  // ML Kit object detection
  implementation(libs.google.mlkit.object1.detection)
  implementation(libs.google.mlkit.object1.detection.custom)
  // Open CV
  implementation(libs.opencv)
  implementation(libs.executorch.android)
  implementation(libs.soloader)
  implementation(libs.androidx.documentfile)
  implementation(files("libs/sherpa-onnx-1.12.29.aar"))
// Check for latest version

  // For Markdown formatting in Jetpack Compose
  implementation(libs.compose.markdown)

  // AWS Bedrock integration, and parsing JSON response
  implementation(libs.aws.bedrockruntime)
  implementation(libs.google.gson)

  // Http server for video streaming
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
}

afterEvaluate { tasks.named("assembleDebug") { dependsOn("export") } }

// function to load properties from a .properties file
fun getLocalProperty(key: String, project: Project): String {
  val properties = Properties()
  val propertiesFile = project.rootProject.file("secrets.properties")
  if (propertiesFile.exists()) {
    propertiesFile.inputStream().use { inputStream -> properties.load(inputStream) }
  }
  return properties.getProperty(key, "")
}

val projectDir = layout.projectDirectory
val sceneDirectory = projectDir.dir("scenes")

spatial {
  allowUsageDataCollection.set(true)
  scenes {
    // if you have installed Meta Spatial Editor somewhere else, update the file path.
      cliPath.set("D:\\Meta Spatial Editor\\v13\\Resources\\CLI.exe")

    exportItems {
      item {
        projectPath.set(sceneDirectory.file("Main.metaspatial"))
        outputPath.set(projectDir.dir("src/main/assets/scenes"))
      }
    }
    hotReload {
      appPackage.set("com.meta.pixelandtexel.scanner")
      appMainActivity.set(".MainActivity")
      assetsDir.set(File("src/main/assets"))
    }
    shaders {
      sources.add(
          // replace with your shader directory
          projectDir.dir("src/shaders")
      )
    }
  }
}
