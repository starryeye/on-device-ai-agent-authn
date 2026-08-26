plugins {
  // Kotlin은 AGP 내장 지원으로 컴파일된다. kotlin-android 플러그인을 적용하지 않는다.
  alias(libs.plugins.android.application)
  // @Tool 애너테이션에서 FunctionTool을 생성한다.
  alias(libs.plugins.ksp)
  // Compose 컴파일러. AGP 내장 Kotlin 버전과 정확히 일치해야 한다.
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "dev.starryeye.ondeviceagent"
  compileSdk = 36

  defaultConfig {
    applicationId = "dev.starryeye.ondeviceagent"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
  }

  buildFeatures { compose = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  // ADK의 전이 의존성들이 각자 META-INF/INDEX.LIST와 DEPENDENCIES를 넣어 APK 패키징 때 충돌한다.
  packaging {
    resources {
      merges += "**/META-INF/INDEX.LIST"
      merges += "**/META-INF/DEPENDENCIES"
    }
  }
}

dependencies {
  implementation(libs.adk.core.android)
  implementation(libs.adk.litertlm.android)
  // litertlm 모듈은 런타임을 implementation으로 갖고 있어 전이되지 않는다. EngineConfig를 직접 만들려면 필요하다.
  implementation(libs.litertlm.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.androidx.core)

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  ksp(libs.adk.processor)

  testImplementation(libs.junit)
}
