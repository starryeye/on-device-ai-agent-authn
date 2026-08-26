# 온디바이스 ADK 에이전트 샘플 앱 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 갤럭시(및 arm64 에뮬레이터)에서 LiteRT-LM으로 도는 온디바이스 ADK 에이전트가 툴 호출까지 수행하는 최소 앱을 만든다.

**Architecture:** 화면 하나짜리 Compose 앱. `AgentViewModel`이 `ModelStore`(모델 파일 확보) → `OnDeviceAgent`(LiteRtLmModel + LlmAgent 생성) → `InMemoryRunner`를 조립하고, `ChatScreen`은 상태를 인자로만 받아 그린다. 툴 호출 판단·실행·되먹임 루프는 전부 ADK Runner 내부에서 일어난다.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), AGP 9.3.1 (내장 Kotlin), Gradle 9.6.1, KSP 2.3.9, ADK Kotlin 0.8.0 (core-android + litertlm-android + processor), LiteRT-LM 런타임 0.13.1

**Spec:** [docs/superpowers/specs/2026-08-26-on-device-adk-agent-design.md](../specs/2026-08-26-on-device-adk-agent-design.md)

## Global Constraints

모든 태스크의 요구사항에 아래가 암묵적으로 포함된다.

- 패키지: `dev.starryeye.ondeviceagent`
- 앱 위치: 저장소 루트의 `android/` (독립 Gradle 루트). `server/`는 이번 범위 밖
- `minSdk 26`, `compileSdk 36`, `targetSdk 36`, JVM toolchain 17
- 의존성 버전 (Maven Central에서 실물 확인한 값, 임의로 올리지 말 것):
  - `com.google.adk:google-adk-kotlin-core-android:0.8.0`
  - `com.google.adk:google-adk-kotlin-litertlm-android:0.8.0`
  - `com.google.adk:google-adk-kotlin-processor:0.8.0` (ksp)
  - `com.google.ai.edge.litertlm:litertlm-android:0.13.1`
- 플러그인 버전 조합은 ADK 공식 예제가 검증한 값 그대로: AGP `9.3.1`, KSP `2.3.9`, Compose 컴파일러 플러그인 `2.2.10`. **`kotlin-android` 플러그인은 적용하지 않는다** — AGP 9의 내장 Kotlin 지원을 쓴다
- 모든 태스크는 커밋 후 `git push` 한다 (사용자 요구사항)
- 모델 로드·다운로드·해제·추론은 전부 `Dispatchers.IO`. 메인 스레드에서 하면 ANR
- 에이전트 instruction은 영어로 쓴다. 2B급 모델은 영어 지시에서 툴 호출이 더 안정적이다

---

## 스펙과의 차이

스펙 5장은 앱 파일을 6개로 잡았으나, 계획은 8개로 나눈다. `ChatMessage.kt`(UI 메시지 모델)와
`AgentUiState.kt`(화면 상태)를 각각 떼어냈다. 둘 다 아무것도 모르는 순수 데이터 타입이라
ViewModel이나 화면에 섞어 두면 그 파일들의 책임이 흐려진다. 기능 범위는 스펙과 동일하다.

---

## File Structure

| 파일 | 책임 | 아는 것 / 모르는 것 |
|---|---|---|
| `android/settings.gradle.kts` | 빌드 루트, 저장소, 플러그인 버전 | — |
| `android/gradle/libs.versions.toml` | 버전 카탈로그 | — |
| `android/app/build.gradle.kts` | 앱 모듈 빌드 설정 | — |
| `android/app/src/main/AndroidManifest.xml` | 권한(INTERNET), 액티비티 | — |
| `android/app/src/main/res/values/strings.xml` | 앱 이름 | — |
| `android/app/src/main/res/values/themes.xml` | 액티비티 테마 | — |
| `.../ondeviceagent/MainActivity.kt` | Compose 진입점 | ViewModel과 ChatScreen만 앎 |
| `.../ondeviceagent/ui/ChatScreen.kt` | 채팅 UI | 에이전트를 **모름**. 상태를 인자로만 받음 |
| `.../ondeviceagent/ui/ChatMessage.kt` | UI 메시지 모델 | 아무것도 모름 |
| `.../ondeviceagent/AgentUiState.kt` | 화면 상태 sealed interface | 아무것도 모름 |
| `.../ondeviceagent/AgentViewModel.kt` | 상태 보유, Runner 구동, 엔진 수명 | 아래 전부를 조립 |
| `.../ondeviceagent/agent/OnDeviceAgent.kt` | LiteRtLmModel + LlmAgent 생성 | DeviceTools를 앎 |
| `.../ondeviceagent/agent/DeviceTools.kt` | `@Tool` + `BatteryReader` 인터페이스/구현 | ADK를 앎, UI를 모름 |
| `.../ondeviceagent/model/ModelStore.kt` | `.litertlm` 탐색/다운로드 | ADK를 **모름** |
| `android/app/src/test/.../model/ModelStoreTest.kt` | 파일 선택 규칙 테스트 | — |
| `android/app/src/test/.../agent/DeviceToolsTest.kt` | 배터리 변환 규칙 테스트 | — |

---

## Task 1: Android SDK 세팅 + Gradle 스캐폴딩 + 빈 앱 빌드

목표는 **코드를 얹기 전에 버전 조합이 실제로 빌드된다는 것을 증명**하는 것이다. 스펙 8장의 최대 리스크가 AGP↔KSP↔Compose 컴파일러 정합성이므로, 여기서 깨지면 뒤 태스크가 전부 막힌다.

**Files:**
- Create: `android/settings.gradle.kts`
- Create: `android/gradle/libs.versions.toml`
- Create: `android/gradle.properties`
- Create: `android/gradle/wrapper/gradle-wrapper.properties` (+ `gradlew`, `gradlew.bat`, `gradle-wrapper.jar` — `gradle wrapper`가 생성)
- Create: `android/.gitignore`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/values/strings.xml`
- Create: `android/app/src/main/res/values/themes.xml`
- Create: `android/app/src/main/kotlin/dev/starryeye/ondeviceagent/MainActivity.kt`

**Interfaces:**
- Consumes: 없음
- Produces: 빌드 가능한 `:app` 모듈. 이후 모든 태스크가 여기에 파일을 얹는다

- [ ] **Step 1: Android SDK 설치**

`sdkmanager`는 brew로 이미 설치돼 있지만 SDK 본체가 없다. 수 GB를 받으므로 시간이 걸린다.

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
mkdir -p "$ANDROID_HOME"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0" \
  "emulator" \
  "system-images;android-36;google_apis;arm64-v8a"
```

`build-tools;36.0.0`이 없다는 오류가 나면 `sdkmanager --sdk_root="$ANDROID_HOME" --list | grep build-tools` 로 실제 존재하는 최신 36.x를 찾아 그 값을 쓴다. AGP가 필요한 build-tools를 자동으로 받기도 하므로, 최악의 경우 이 항목만 빼고 진행해도 된다.

- [ ] **Step 2: SDK 설치 확인**

```bash
"$HOME/Library/Android/sdk/platform-tools/adb" version
```
Expected: `Android Debug Bridge version ...` 이 출력된다.

- [ ] **Step 3: `android/.gitignore` 작성**

```gitignore
.gradle/
build/
local.properties
*.iml
.idea/
.kotlin/
```

- [ ] **Step 4: `android/gradle.properties` 작성**

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=true
android.useAndroidX=true
```

- [ ] **Step 5: `android/gradle/libs.versions.toml` 작성**

버전은 Global Constraints의 값과 정확히 일치해야 한다.

```toml
[versions]
agp = "9.3.1"
androidx-activity-compose = "1.9.3"
androidx-compose-bom = "2024.09.03"
androidx-core = "1.16.0"
androidx-lifecycle-viewmodel-compose = "2.8.7"
adk = "0.8.0"
compose-compiler = "2.2.10"
junit = "4.13.2"
kotlinx-coroutines = "1.11.0"
ksp = "2.3.9"
litertlm = "0.13.1"

[libraries]
adk-core-android = { module = "com.google.adk:google-adk-kotlin-core-android", version.ref = "adk" }
adk-litertlm-android = { module = "com.google.adk:google-adk-kotlin-litertlm-android", version.ref = "adk" }
adk-processor = { module = "com.google.adk:google-adk-kotlin-processor", version.ref = "adk" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "androidx-activity-compose" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "androidx-compose-bom" }
androidx-compose-foundation = { module = "androidx.compose.foundation:foundation" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-core = { module = "androidx.core:core", version.ref = "androidx-core" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "androidx-lifecycle-viewmodel-compose" }
junit = { module = "junit:junit", version.ref = "junit" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
litertlm-android = { module = "com.google.ai.edge.litertlm:litertlm-android", version.ref = "litertlm" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "compose-compiler" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 6: `android/settings.gradle.kts` 작성**

```kotlin
pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "on-device-agent"

include(":app")
```

- [ ] **Step 7: `android/app/build.gradle.kts` 작성**

```kotlin
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

  // ADK의 전이 의존성들이 각자 META-INF/INDEX.LIST와 DEPENDENCIES를 넣어 APK 패키징 때 충돌한다.
  packaging {
    resources {
      merges += "**/META-INF/INDEX.LIST"
      merges += "**/META-INF/DEPENDENCIES"
    }
  }

  sourceSets {
    getByName("main") { kotlin.srcDir("src/main/kotlin") }
    getByName("test") { kotlin.srcDir("src/test/kotlin") }
  }
}

kotlin { jvmToolchain(17) }

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
```

- [ ] **Step 8: `android/app/src/main/AndroidManifest.xml` 작성**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 모델 파일을 최초 1회 내려받을 때만 쓴다. 이후 앱은 완전히 오프라인으로 동작한다. -->
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:theme="@style/Theme.OnDeviceAgent">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 9: `android/app/src/main/res/values/strings.xml` 작성**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">On-device Agent</string>
</resources>
```

- [ ] **Step 10: `android/app/src/main/res/values/themes.xml` 작성**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.OnDeviceAgent" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 11: 최소 `MainActivity.kt` 작성**

이 단계에서는 화면에 텍스트 하나만 그린다. 목적은 빌드 검증이다.

```kotlin
package dev.starryeye.ondeviceagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme { Text("On-device agent") } }
  }
}
```

- [ ] **Step 12: Gradle wrapper 생성**

```bash
cd android && gradle wrapper --gradle-version 9.6.1
```

- [ ] **Step 13: `local.properties` 생성**

`.gitignore`에 있으므로 커밋되지 않는다.

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > android/local.properties
```

- [ ] **Step 14: 빌드 실행 — 이 태스크의 검증점**

```bash
cd android && ./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

실패하면 **버전을 임의로 바꾸지 말고** 오류 메시지를 그대로 보고할 것. 이 조합은 ADK 공식 예제에서 검증된 값이며, 어긋난다면 원인을 특정해야 한다. 흔한 원인은 (a) Compose 컴파일러 플러그인 버전이 AGP 내장 Kotlin 버전과 불일치, (b) SDK 미설치, (c) `local.properties` 누락이다.

- [ ] **Step 15: 커밋 & 푸시**

```bash
git add android/ && git commit -m "$(cat <<'MSG'
build: Android 앱 스캐폴딩

화면 하나짜리 빈 Compose 앱. 코드를 얹기 전에 AGP 9.3.1 / KSP 2.3.9 /
Compose 컴파일러 2.2.10 / ADK 0.8.0 조합이 실제로 빌드되는지 먼저 확인했다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
)" && git push```

---

## Task 2: ModelStore — `.litertlm` 파일 확보

**Files:**
- Create: `android/app/src/main/kotlin/dev/starryeye/ondeviceagent/model/ModelStore.kt`
- Test: `android/app/src/test/kotlin/dev/starryeye/ondeviceagent/model/ModelStoreTest.kt`

**Interfaces:**
- Consumes: 없음 (ADK를 모르는 독립 단위)
- Produces:
  - `ModelStore.selectModelFile(directory: File): File?` — 순수 함수, 테스트 대상
  - `ModelStore.find(context: Context): File?`
  - `ModelStore.download(context: Context): Flow<Float>` — 0f..1f 진행률
  - `ModelStore.pushDirectory(context: Context): String`
  - `ModelStore.DOWNLOAD_SIZE_LABEL: String` — `"2.5 GB"`

- [ ] **Step 1: 실패하는 테스트 작성**

파일 선택 규칙 5가지를 고정한다. `Context`를 쓰지 않으므로 순수 JVM 테스트다.

`android/app/src/test/kotlin/dev/starryeye/ondeviceagent/model/ModelStoreTest.kt`:

```kotlin
package dev.starryeye.ondeviceagent.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStoreTest {

  @get:Rule val folder = TemporaryFolder()

  @Test
  fun `비어 있는 디렉터리에서는 모델을 찾지 못한다`() {
    assertNull(ModelStore.selectModelFile(folder.root))
  }

  @Test
  fun `내려받은 모델만 있으면 그것을 쓴다`() {
    val downloaded = folder.newFile("gemma-4-E2B-it.litertlm")

    assertEquals(downloaded, ModelStore.selectModelFile(folder.root))
  }

  @Test
  fun `직접 넣은 모델이 내려받은 모델보다 우선한다`() {
    folder.newFile("gemma-4-E2B-it.litertlm")
    val pushed = folder.newFile("my-own-model.litertlm")

    assertEquals(pushed, ModelStore.selectModelFile(folder.root))
  }

  @Test
  fun `직접 넣은 모델이 여럿이면 이름순 첫 번째를 쓴다`() {
    folder.newFile("z-model.litertlm")
    val first = folder.newFile("a-model.litertlm")

    assertEquals(first, ModelStore.selectModelFile(folder.root))
  }

  @Test
  fun `받다 만 파일은 모델로 취급하지 않는다`() {
    folder.newFile("gemma-4-E2B-it.litertlm.part")

    assertNull(ModelStore.selectModelFile(folder.root))
  }
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests '*ModelStoreTest*'
```
Expected: 컴파일 실패 — `Unresolved reference: ModelStore`.

- [ ] **Step 3: `ModelStore.kt` 구현**

```kotlin
package dev.starryeye.ondeviceagent.model

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * 에이전트가 쓸 `.litertlm` 가중치 파일을 확보한다.
 *
 * LiteRT-LM은 파일 경로를 받을 뿐 모델을 스스로 가져오지 않으므로, 기기에 올려놓는 일은 앱의
 * 몫이다. 디렉터리에 이미 있는 파일은 그대로 쓰므로 `adb push`도 통한다.
 *
 * 이 파일은 ADK를 모른다. 파일을 찾고 받아오는 일만 한다.
 */
object ModelStore {

  /** 받아올 모델. 툴 호출이 가능한 `.litertlm` 모델이면 무엇이든 된다. */
  private const val REPO = "litert-community/gemma-4-E2B-it-litert-lm"
  private const val FILE_NAME = "gemma-4-E2B-it.litertlm"
  private const val REVISION = "main"

  /** 다운로드를 시작하기 전에 사용자에게 비용을 알리기 위한 대략적인 크기. */
  const val DOWNLOAD_SIZE_LABEL: String = "2.5 GB"

  private const val EXTENSION = ".litertlm"
  private const val PARTIAL_SUFFIX = ".part"

  /** 버퍼마다가 아니라 이 횟수만큼만 진행률을 낸다. */
  private const val PROGRESS_STEPS = 200

  private const val TIMEOUT_MILLIS = 30_000

  /**
   * [directory]에서 쓸 모델을 고른다. 직접 넣은 파일이 내려받은 파일을 이긴다 — 그래야
   * `adb push`한 모델이 아무것도 지우지 않고 우선한다. 직접 넣은 것이 여럿이면 이름순 첫 번째다.
   *
   * 받다 만 파일은 `.litertlm.part`로 끝나므로 확장자 검사에서 자연히 걸러진다.
   */
  fun selectModelFile(directory: File): File? {
    val candidates =
      directory
        .listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
        .orEmpty()
        .sortedBy { it.name }
    return candidates.firstOrNull { it.name != FILE_NAME } ?: candidates.firstOrNull()
  }

  /** 쓸 수 있는 모델, 아직 아무것도 없으면 null. */
  fun find(context: Context): File? = selectModelFile(directory(context))

  /**
   * [FILE_NAME]을 내려받으며 완료 비율을 낸다. 바이트는 임시 파일에 쓰고 전송이 끝난 뒤에만
   * 이름을 바꾼다 — 중단된 다운로드가 멀쩡한 모델로 오인되지 않게 하려는 것이다.
   */
  fun download(context: Context): Flow<Float> =
    flow {
        emit(0f)
        val partial = File(directory(context), FILE_NAME + PARTIAL_SUFFIX)
        try {
          val connection = URL(downloadUrl()).openConnection() as HttpURLConnection
          connection.connectTimeout = TIMEOUT_MILLIS
          connection.readTimeout = TIMEOUT_MILLIS
          try {
            // 이게 없으면 오류 페이지가 모델인 양 파일로 쓰인다.
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
              "모델 다운로드가 HTTP ${connection.responseCode}로 실패했습니다."
            }
            val total = connection.contentLengthLong
            val step = if (total > 0) total / PROGRESS_STEPS else Long.MAX_VALUE
            var copied = 0L
            var reported = 0L
            connection.inputStream.use { input ->
              partial.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                  coroutineContext.ensureActive()
                  val read = input.read(buffer)
                  if (read < 0) break
                  output.write(buffer, 0, read)
                  copied += read
                  if (copied - reported >= step) {
                    reported = copied
                    emit(copied.toFloat() / total)
                  }
                }
              }
            }
          } finally {
            connection.disconnect()
          }
          check(partial.renameTo(File(directory(context), FILE_NAME))) {
            "받은 모델을 제자리로 옮기지 못했습니다."
          }
        } catch (t: Throwable) {
          // 처음부터 다시 받는 재시도를 위해 반쯤 받은 수 GB를 남겨둘 이유가 없다.
          val unused = partial.delete()
          throw t
        }
        emit(1f)
      }
      .flowOn(Dispatchers.IO)

  /** 다운로드 안내와 함께 보여줄 `adb push` 목적지. */
  fun pushDirectory(context: Context): String = directory(context).absolutePath

  private fun directory(context: Context): File =
    context.getExternalFilesDir(null) ?: context.filesDir

  private fun downloadUrl(): String = "https://huggingface.co/$REPO/resolve/$REVISION/$FILE_NAME"
}
```

- [ ] **Step 4: 테스트를 돌려 통과를 확인한다**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests '*ModelStoreTest*'
```
Expected: 5개 테스트 모두 PASS.

- [ ] **Step 5: 커밋 & 푸시**

```bash
git add android/app/src && git commit -m "feat: .litertlm 모델 파일 확보 (ModelStore)" && git push
```

`download()`에는 자동 테스트가 없다. 실제 네트워크 다운로드는 Task 6의 수동 검증에서 확인한다.

---

## Task 3: DeviceTools — 에이전트가 호출할 툴

**Files:**
- Create: `android/app/src/main/kotlin/dev/starryeye/ondeviceagent/agent/DeviceTools.kt`
- Test: `android/app/src/test/kotlin/dev/starryeye/ondeviceagent/agent/DeviceToolsTest.kt`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `fun interface BatteryReader { fun batteryPercent(): Int }`
  - `class AndroidBatteryReader(context: Context) : BatteryReader`
  - `class DeviceTools(batteryReader: BatteryReader)` — `@Tool fun getBatteryLevel(): Map<String, Any>`
  - `internal fun batteryLevelResult(percent: Int): Map<String, Any>` — 테스트 대상
  - KSP가 생성하는 확장 함수 `DeviceTools.generatedTools()` — Task 4가 사용한다

`BatteryReader`를 인터페이스로 뽑는 이유는 하나다. `BatteryManager`는 안드로이드 클래스라 JVM 단위 테스트에서 쓸 수 없다. 변환 규칙만 순수 함수로 떼어내면 테스트가 가능해진다.

- [ ] **Step 1: 실패하는 테스트 작성**

`android/app/src/test/kotlin/dev/starryeye/ondeviceagent/agent/DeviceToolsTest.kt`:

```kotlin
package dev.starryeye.ondeviceagent.agent

import com.google.adk.kt.tools.FunctionTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceToolsTest {

  @Test
  fun `정상 범위의 배터리 값은 그대로 전달한다`() {
    assertEquals(mapOf("battery_percent" to 42), batteryLevelResult(42))
  }

  @Test
  fun `경계값 0과 100도 정상으로 본다`() {
    assertEquals(mapOf("battery_percent" to 0), batteryLevelResult(0))
    assertEquals(mapOf("battery_percent" to 100), batteryLevelResult(100))
  }

  @Test
  fun `범위를 벗어난 값은 모델에 넘기지 않고 오류로 바꾼다`() {
    val result = batteryLevelResult(Int.MIN_VALUE)

    assertTrue(result.containsKey(FunctionTool.ERROR_KEY))
    assertTrue(!result.containsKey("battery_percent"))
  }

  @Test
  fun `툴은 주입된 리더가 준 값을 사용한다`() {
    val tools = DeviceTools(BatteryReader { 77 })

    assertEquals(mapOf("battery_percent" to 77), tools.getBatteryLevel())
  }
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests '*DeviceToolsTest*'
```
Expected: 컴파일 실패 — `Unresolved reference: batteryLevelResult`.

- [ ] **Step 3: `DeviceTools.kt` 구현**

```kotlin
package dev.starryeye.ondeviceagent.agent

import android.content.Context
import android.os.BatteryManager
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.tools.FunctionTool

/**
 * 배터리 잔량을 읽는 얇은 경계. `BatteryManager`를 이 뒤로 밀어내면 [DeviceTools]가
 * 안드로이드 없이 테스트된다.
 */
fun interface BatteryReader {
  /** 0..100의 잔량. 기기가 보고하지 못하면 그 범위 밖의 값. */
  fun batteryPercent(): Int
}

/** 실제 기기에서 값을 읽는 [BatteryReader]. */
class AndroidBatteryReader(context: Context) : BatteryReader {

  private val batteryManager = context.getSystemService(BatteryManager::class.java)

  override fun batteryPercent(): Int =
    batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: Int.MIN_VALUE
}

/**
 * 에이전트가 호출할 수 있는 툴. KSP `@Tool` 프로세서가 이 클래스에 대한 확장 함수
 * `generatedTools()`를 만들어 준다.
 *
 * 툴이 하나뿐인 이유는 이것이 최소 샘플이기 때문이다. 배터리 잔량은 모델이 결코 알 수 없는
 * 실제 기기 상태이므로, 응답에 실제 수치가 나오면 툴이 진짜 실행됐다는 증거가 된다.
 */
class DeviceTools(private val batteryReader: BatteryReader) {

  @Tool(
    name = "get_battery_level",
    description = "Returns this device's current battery charge, as a percentage.",
  )
  fun getBatteryLevel(): Map<String, Any> = batteryLevelResult(batteryReader.batteryPercent())
}

/**
 * 잔량을 툴 응답으로 바꾼다. 잔량을 보고하지 못하는 기기는 `Integer.MIN_VALUE`를 주므로,
 * 그런 값을 모델에 넘기지 않는다.
 */
internal fun batteryLevelResult(percent: Int): Map<String, Any> =
  if (percent in 0..100) {
    mapOf("battery_percent" to percent)
  } else {
    mapOf(FunctionTool.ERROR_KEY to "This device does not report its battery level.")
  }
```

- [ ] **Step 4: 테스트를 돌려 통과를 확인한다**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests '*DeviceToolsTest*'
```
Expected: 4개 테스트 모두 PASS.

`FunctionTool.ERROR_KEY` 참조가 단위 테스트에서 해석되지 않으면, ADK 아티팩트가 `testImplementation` 경로에 없다는 뜻이다. `android/app/build.gradle.kts`의 `testImplementation(libs.adk.core.android)`를 추가해 해결한다.

- [ ] **Step 5: KSP가 툴을 생성했는지 확인한다**

```bash
cd android && ./gradlew :app:compileDebugKotlin && \
  find app/build/generated/ksp -name '*.kt' | head
```
Expected: `DeviceTools`에 대한 생성 파일이 나온다. 아무것도 없으면 `ksp(libs.adk.processor)` 설정이 동작하지 않은 것이므로 Task 4로 넘어가기 전에 해결해야 한다.

- [ ] **Step 6: 커밋 & 푸시**

```bash
git add android/app/src && git commit -m "feat: 배터리 잔량 조회 툴 (DeviceTools)" && git push
```

---

## Task 4: 에이전트 조립 — OnDeviceAgent + AgentViewModel

이 태스크가 ADK 배선의 전부다. 자동 테스트가 닿지 않는 구간이므로(LLM 응답은 단위 테스트로 고정할 수 없다) 컴파일 통과까지만 확인하고, 실제 동작은 Task 6에서 검증한다.

**Files:**
- Create: `android/app/src/main/kotlin/dev/starryeye/ondeviceagent/ui/ChatMessage.kt`
- Create: `android/app/src/main/kotlin/dev/starryeye/ondeviceagent/AgentUiState.kt`
- Create: `android/app/src/main/kotlin/dev/starryeye/ondeviceagent/agent/OnDeviceAgent.kt`
- Create: `android/app/src/main/kotlin/dev/starryeye/ondeviceagent/AgentViewModel.kt`

**Interfaces:**
- Consumes:
  - `ModelStore.find`, `ModelStore.download`, `ModelStore.pushDirectory`, `ModelStore.DOWNLOAD_SIZE_LABEL` (Task 2)
  - `DeviceTools`, `AndroidBatteryReader`, `DeviceTools.generatedTools()` (Task 3)
- Produces (Task 5가 사용한다):
  - `enum class ChatAuthor { USER, AGENT, SYSTEM }`
  - `data class ChatMessage(val author: ChatAuthor, val text: String)`
  - `sealed interface AgentUiState` — `NeedsModel` / `Downloading(progress: Float)` / `Loading` / `Ready` / `Failed(reason: String)`
  - `class AgentViewModel(application: Application) : AndroidViewModel` — 프로퍼티 `messages: List<ChatMessage>`, `uiState: AgentUiState`, `inputEnabled: Boolean`, `pushHint: String`; 함수 `downloadModel()`, `send(text: String)`

- [ ] **Step 1: `ui/ChatMessage.kt` 작성**

```kotlin
package dev.starryeye.ondeviceagent.ui

/** 말풍선을 그린 주체. */
enum class ChatAuthor {
  USER,
  AGENT,
  /** 앱이 상황을 알리는 줄. 모델과 무관하다. */
  SYSTEM,
}

/** 화면에 그려지는 한 줄. */
data class ChatMessage(val author: ChatAuthor, val text: String)
```

- [ ] **Step 2: `AgentUiState.kt` 작성**

```kotlin
package dev.starryeye.ondeviceagent

/**
 * 화면이 처한 상황. 입력창은 [Ready]에서만, 그리고 turn이 돌고 있지 않을 때만 열린다
 * ([AgentViewModel.inputEnabled] 참고).
 */
sealed interface AgentUiState {

  /** 아직 모델 파일이 없다. 내려받거나 `adb push` 해야 한다. */
  data object NeedsModel : AgentUiState

  /** 모델을 내려받는 중. [progress]는 0f..1f. */
  data class Downloading(val progress: Float) : AgentUiState

  /** 가중치를 읽어 네이티브 엔진을 여는 중. 수 초 걸린다. */
  data object Loading : AgentUiState

  /** 대화할 수 있다. */
  data object Ready : AgentUiState

  /** 회복할 수 없는 실패. [reason]을 그대로 사용자에게 보여준다. */
  data class Failed(val reason: String) : AgentUiState
}
```

- [ ] **Step 3: `agent/OnDeviceAgent.kt` 작성**

```kotlin
package dev.starryeye.ondeviceagent.agent

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.litertlm.LiteRtLmModel
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/**
 * 온디바이스 모델과 그것을 쓰는 [LlmAgent]를 만든다.
 *
 * 모델을 따로 만드는 이유는 그것이 네이티브 엔진을 소유하기 때문이다. 엔진의 수명은
 * [dev.starryeye.ondeviceagent.AgentViewModel]이 자기 수명에 묶어 관리한다.
 */
object OnDeviceAgent {

  const val NAME: String = "on_device_agent"

  /**
   * [modelFile]을 CPU 백엔드로 연다. 돌려받은 모델은 네이티브 엔진을 소유하므로 반드시
   * 닫아야 한다. [cacheDir]에 컴파일된 모델 캐시를 두어 시스템이 회수할 수 있게 한다.
   */
  fun createModel(modelFile: File, cacheDir: File): LiteRtLmModel =
    LiteRtLmModel.create(
      EngineConfig(
        modelPath = modelFile.absolutePath,
        backend = Backend.CPU(),
        cacheDir = cacheDir.absolutePath,
      ),
      name = modelFile.name,
    )

  /**
   * 이미 만들어진 [model] 위에 에이전트를 세운다.
   *
   * instruction을 영어로 쓴 것은 의도적이다. 2B급 모델은 영어 지시에서 툴 호출 판단이
   * 눈에 띄게 안정적이다.
   */
  fun create(model: LiteRtLmModel, batteryReader: BatteryReader): LlmAgent =
    LlmAgent(
      name = NAME,
      model = model,
      instruction =
        Instruction(
          """
          You are a helpful assistant running entirely on this device. Keep replies to one or two
          short sentences. Call get_battery_level when the user asks about the battery, then state
          the exact value the tool returned.
          """
            .trimIndent()
        ),
      tools = DeviceTools(batteryReader).generatedTools(),
    )
}
```

- [ ] **Step 4: `AgentViewModel.kt` 작성**

```kotlin
package dev.starryeye.ondeviceagent

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.events.Event
import com.google.adk.kt.litertlm.LiteRtLmModel
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import dev.starryeye.ondeviceagent.agent.AndroidBatteryReader
import dev.starryeye.ondeviceagent.agent.OnDeviceAgent
import dev.starryeye.ondeviceagent.model.ModelStore
import dev.starryeye.ondeviceagent.ui.ChatAuthor
import dev.starryeye.ondeviceagent.ui.ChatMessage
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 화면의 상태를 들고, 모델을 확보해 ADK Runner를 세우고, 한 turn을 돌린다.
 *
 * Activity가 아니라 ViewModel인 이유는 하나다. 모델 로드가 수 초 걸리고 2.5GB 가중치를
 * 붙잡으므로, 화면 회전마다 다시 로드하면 앱을 쓸 수 없다.
 *
 * 코루틴은 [viewModelScope]에서 메인 디스패처로 돌고, 블로킹 구간만 [Dispatchers.IO]로
 * 넘긴다. 그래서 상태 갱신은 항상 메인 스레드에서 일어난다.
 */
class AgentViewModel(application: Application) : AndroidViewModel(application) {

  private val sessionService = InMemorySessionService()
  private var runner: InMemoryRunner? = null
  private var model: LiteRtLmModel? = null

  private val _messages = mutableStateListOf<ChatMessage>()
  val messages: List<ChatMessage> = _messages

  var uiState: AgentUiState by mutableStateOf(AgentUiState.Loading)
    private set

  /** turn이 도는 동안 입력을 잠근다. 엔진은 한 번에 하나의 대화만 다룬다. */
  private var busy by mutableStateOf(false)

  val inputEnabled: Boolean
    get() = uiState is AgentUiState.Ready && !busy

  /** 모델을 직접 밀어 넣고 싶은 개발자에게 보여줄 `adb push` 목적지. */
  val pushHint: String
    get() = ModelStore.pushDirectory(getApplication())

  init {
    viewModelScope.launch {
      val modelFile = withContext(Dispatchers.IO) { ModelStore.find(getApplication()) }
      if (modelFile == null) {
        addSystem(
          "이 앱은 온디바이스 모델 파일(약 ${ModelStore.DOWNLOAD_SIZE_LABEL})이 필요합니다. " +
            "아래에서 내려받거나 직접 밀어 넣으세요:\n\nadb push your-model.litertlm $pushHint/"
        )
        uiState = AgentUiState.NeedsModel
      } else {
        loadModel(modelFile)
      }
    }
  }

  /** 가중치를 받고 이어서 로드한다. 이 앱이 네트워크를 쓰는 유일한 경로다. */
  fun downloadModel() {
    if (uiState !is AgentUiState.NeedsModel) return
    viewModelScope.launch {
      uiState = AgentUiState.Downloading(0f)
      try {
        ModelStore.download(getApplication()).collect { fraction ->
          uiState = AgentUiState.Downloading(fraction)
        }
        val modelFile =
          withContext(Dispatchers.IO) { ModelStore.find(getApplication()) }
            ?: error("다운로드는 끝났는데 모델 파일을 찾을 수 없습니다.")
        loadModel(modelFile)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        addSystem("다운로드 실패: ${e.message ?: e::class.simpleName}")
        uiState = AgentUiState.NeedsModel
      }
    }
  }

  private suspend fun loadModel(modelFile: File) {
    uiState = AgentUiState.Loading
    addSystem("${modelFile.name} 을(를) 불러오는 중입니다 (수 초 걸립니다)…")
    try {
      val loaded =
        withContext(Dispatchers.IO) {
          val created = OnDeviceAgent.createModel(modelFile, getApplication<Application>().cacheDir)
          // 첫 메시지가 아니라 지금 로드한다. 그래야 깨진 파일이 깨진 파일로 보고된다.
          created.engine.initialize()
          created
        }
      model = loaded
      runner =
        InMemoryRunner(
          agent = OnDeviceAgent.create(loaded, AndroidBatteryReader(getApplication())),
          appName = APP_NAME,
          sessionService = sessionService,
        )
      addSystem("준비됐습니다. \"배터리 몇 퍼센트야?\" 라고 물어보세요 — 툴을 호출해 답합니다.")
      uiState = AgentUiState.Ready
    } catch (e: Throwable) {
      // Throwable: 네이티브 바이너리가 없는 기기는 UnsatisfiedLinkError로 실패한다.
      val reason = e.message ?: e::class.simpleName ?: "알 수 없는 오류"
      addSystem("모델을 불러오지 못했습니다: $reason")
      uiState = AgentUiState.Failed(reason)
    }
  }

  /** 한 turn을 돌린다. 툴 호출 판단·실행·되먹임은 전부 Runner 안에서 일어난다. */
  fun send(text: String) {
    val activeRunner = runner ?: return
    if (!inputEnabled) return
    _messages.add(ChatMessage(ChatAuthor.USER, text))
    busy = true

    viewModelScope.launch {
      val partial = StringBuilder()
      var bubbleIndex = -1
      try {
        withContext(Dispatchers.IO) {
            activeRunner.runAsync(
              userId = USER_ID,
              sessionId = SESSION_ID,
              newMessage = Content(role = Role.USER, parts = listOf(Part(text = text))),
              runConfig =
                RunConfig(
                  streamingMode = StreamingMode.SSE,
                  // 작은 모델은 툴 하나를 물고 늘어질 수 있다. turn을 끝나게 만드는 상한.
                  maxLlmCalls = MAX_LLM_CALLS,
                ),
            )
          }
          .collect { event ->
            if (event.author != OnDeviceAgent.NAME) return@collect
            val chunk = event.visibleText()
            if (event.partial) {
              // SSE: 조각이 올 때마다 같은 말풍선을 키운다.
              if (chunk.isNotEmpty()) {
                partial.append(chunk)
                bubbleIndex = showAgentText(bubbleIndex, partial.toString())
              }
            } else {
              // 집계된 이벤트가 turn을 끝낸다. 그쪽 텍스트가 정본이다.
              val finalText = chunk.ifBlank { partial.toString() }.trim()
              if (finalText.isNotEmpty()) bubbleIndex = showAgentText(bubbleIndex, finalText)
              partial.setLength(0)
              // 툴 호출은 turn을 둘로 쪼갠다. 다음 조각은 새 말풍선을 갖는다.
              bubbleIndex = -1
              reportActivity(event)
            }
          }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        addSystem("오류: ${e.message ?: e::class.simpleName}")
      } finally {
        busy = false
      }
    }
  }

  /**
   * 네이티브 엔진을 놓아준다. 별도 스레드인 것은 해제가 느리기 때문이고, 예외를 삼키는 것은
   * 여기서 터진 예외가 프로세스를 통째로 내리기 때문이다.
   */
  override fun onCleared() {
    val closing = model
    model = null
    runner = null
    if (closing != null) {
      thread(name = "litertlm-close") {
        try {
          closing.close()
        } catch (_: Throwable) {
          // 보고할 화면이 이미 사라진 뒤다.
        }
      }
    }
    super.onCleared()
  }

  /** 어떤 툴이 불렸는지, 모델이 답 대신 오류를 냈는지 화면에 남긴다. */
  private fun reportActivity(event: Event) {
    event.errorMessage?.let { addSystem("모델 오류: $it") }
    for (part in event.content?.parts.orEmpty()) {
      part.functionCall?.name?.let { addSystem("툴 호출: $it") }
    }
  }

  /** 진행 중인 말풍선을 갱신하거나 새로 만든다. 새 인덱스를 돌려준다. */
  private fun showAgentText(index: Int, text: String): Int =
    if (index < 0) {
      _messages.add(ChatMessage(ChatAuthor.AGENT, text))
      _messages.lastIndex
    } else {
      _messages[index] = _messages[index].copy(text = text)
      index
    }

  private fun addSystem(text: String) {
    _messages.add(ChatMessage(ChatAuthor.SYSTEM, text))
  }

  private companion object {
    const val APP_NAME = "OnDeviceAgent"
    const val USER_ID = "local-user"
    const val SESSION_ID = "local-session"
    const val MAX_LLM_CALLS = 8
  }
}

/**
 * 이벤트가 담은 눈에 보이는 응답 텍스트. thought 조각은 빼고, 구분자 없이 잇는다 — 조각들은
 * 한 문장의 파편이라 구분자를 넣으면 스트리밍 도중 단어 가운데에 공백이 끼어든다.
 */
private fun Event.visibleText(): String =
  content?.parts.orEmpty().filter { it.thought != true }.mapNotNull { it.text }.joinToString("")
```

- [ ] **Step 5: 컴파일 확인**

```bash
cd android && ./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

`runAsync`가 `suspend`가 아니라는 오류가 나면 `withContext(Dispatchers.IO) { ... }` 블록을 제거하고 `activeRunner.runAsync(...)`를 직접 호출한 뒤 `.flowOn(Dispatchers.IO).collect { ... }`로 바꾼다. 어느 쪽이든 **생성은 IO에서, 상태 갱신은 메인에서** 라는 성질은 유지되어야 한다.

- [ ] **Step 6: 기존 단위 테스트가 여전히 통과하는지 확인**

```bash
cd android && ./gradlew :app:testDebugUnitTest
```
Expected: Task 2·3의 테스트 9개 전부 PASS.

- [ ] **Step 7: 커밋 & 푸시**

```bash
git add android/app/src && git commit -m "feat: ADK Runner 배선과 에이전트 수명 관리 (AgentViewModel)" && git push
```

---

## Task 5: 화면 — ChatScreen + MainActivity

**Files:**
- Create: `android/app/src/main/kotlin/dev/starryeye/ondeviceagent/ui/ChatScreen.kt`
- Modify: `android/app/src/main/kotlin/dev/starryeye/ondeviceagent/MainActivity.kt` (Task 1의 자리표시 내용을 대체)

**Interfaces:**
- Consumes: `ChatMessage`, `ChatAuthor`, `AgentUiState`, `AgentViewModel` (Task 4)
- Produces: `@Composable fun ChatScreen(messages: List<ChatMessage>, uiState: AgentUiState, inputEnabled: Boolean, onSend: (String) -> Unit, onDownload: () -> Unit)`

`ChatScreen`은 ViewModel도 에이전트도 모른다. 상태를 인자로 받고 콜백을 돌려줄 뿐이다. 그래서 나중에 교체하거나 프리뷰하기 쉽다.

- [ ] **Step 1: `ui/ChatScreen.kt` 작성**

```kotlin
package dev.starryeye.ondeviceagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.starryeye.ondeviceagent.AgentUiState

/**
 * 채팅 화면 전체. 이 컴포저블은 에이전트도 ViewModel도 모른다 — 상태를 인자로 받고 콜백을
 * 돌려줄 뿐이다.
 */
@Composable
fun ChatScreen(
  messages: List<ChatMessage>,
  uiState: AgentUiState,
  inputEnabled: Boolean,
  onSend: (String) -> Unit,
  onDownload: () -> Unit,
) {
  val listState = rememberLazyListState()

  // 새 메시지가 붙거나 스트리밍으로 마지막 말풍선이 자라면 바닥을 따라간다.
  LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
  }

  Column(modifier = Modifier.fillMaxSize().imePadding()) {
    LazyColumn(
      state = listState,
      modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
      items(messages.size) { index -> MessageBubble(messages[index]) }
    }

    StatusBar(uiState = uiState, onDownload = onDownload)

    InputRow(enabled = inputEnabled, onSend = onSend)
  }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
  when (message.author) {
    ChatAuthor.SYSTEM ->
      Text(
        text = message.text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      )
    else -> {
      val fromUser = message.author == ChatAuthor.USER
      Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart,
      ) {
        Text(
          text = message.text,
          style = MaterialTheme.typography.bodyMedium,
          color =
            if (fromUser) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
          modifier =
            Modifier.background(
                color =
                  if (fromUser) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp),
              )
              .padding(horizontal = 14.dp, vertical = 10.dp),
        )
      }
    }
  }
}

/** 모델을 아직 못 쓰는 상황에서만 무언가를 보여준다. 준비되면 사라진다. */
@Composable
private fun StatusBar(uiState: AgentUiState, onDownload: () -> Unit) {
  when (uiState) {
    is AgentUiState.NeedsModel ->
      Button(
        onClick = onDownload,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      ) {
        Text("모델 내려받기")
      }
    is AgentUiState.Downloading ->
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
          text = "내려받는 중… ${(uiState.progress * 100).toInt()}%",
          style = MaterialTheme.typography.bodySmall,
        )
        LinearProgressIndicator(
          progress = { uiState.progress },
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
      }
    is AgentUiState.Loading ->
      LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
      )
    is AgentUiState.Failed ->
      Text(
        text = uiState.reason,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      )
    is AgentUiState.Ready -> Unit
  }
}

@Composable
private fun InputRow(enabled: Boolean, onSend: (String) -> Unit) {
  var draft by remember { mutableStateOf("") }

  Row(
    modifier = Modifier.fillMaxWidth().padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    OutlinedTextField(
      value = draft,
      onValueChange = { draft = it },
      enabled = enabled,
      singleLine = true,
      placeholder = { Text("메시지를 입력하세요") },
      modifier = Modifier.weight(1f),
    )
    Button(
      onClick = {
        val text = draft.trim()
        if (text.isNotEmpty()) {
          draft = ""
          onSend(text)
        }
      },
      enabled = enabled && draft.isNotBlank(),
    ) {
      Text("보내기")
    }
  }
}
```

`items(messages.size)`가 해석되지 않으면 `import androidx.compose.foundation.lazy.items` 대신 `androidx.compose.foundation.lazy.LazyListScope.items`가 필요한 형태다. `itemsIndexed(messages) { _, message -> MessageBubble(message) }`로 바꾸고 `androidx.compose.foundation.lazy.itemsIndexed`를 import 한다.

- [ ] **Step 2: `MainActivity.kt` 교체**

```kotlin
package dev.starryeye.ondeviceagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.starryeye.ondeviceagent.ui.ChatScreen

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          // ViewModel이 회전을 견딘다 — 2.5GB 모델을 다시 로드하지 않는다.
          val viewModel: AgentViewModel = viewModel()
          ChatScreen(
            messages = viewModel.messages,
            uiState = viewModel.uiState,
            inputEnabled = viewModel.inputEnabled,
            onSend = viewModel::send,
            onDownload = viewModel::downloadModel,
          )
        }
      }
    }
  }
}
```

- [ ] **Step 3: 빌드 확인**

```bash
cd android && ./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: 단위 테스트가 여전히 통과하는지 확인**

```bash
cd android && ./gradlew :app:testDebugUnitTest
```
Expected: 9개 테스트 전부 PASS.

- [ ] **Step 5: 커밋 & 푸시**

```bash
git add android/app/src && git commit -m "feat: 채팅 화면과 Activity 배선" && git push
```

---

## Task 6: 실기 검증 + 문서

여기서 스펙 10장의 완료 기준 7개를 전부 통과시킨다. **이 태스크가 끝나기 전에는 "동작한다"고 말하지 않는다.**

**Files:**
- Create: `server/README.md`
- Modify: `README.md` (저장소 루트)
- Create: `android/README.md`

**Interfaces:**
- Consumes: Task 1~5의 결과물 전부
- Produces: 검증된 앱과 재현 절차 문서

- [ ] **Step 1: AVD 생성**

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
  -n adk-arm64 -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_7
```

`avdmanager`가 그 경로에 없으면 `find "$ANDROID_HOME" -name avdmanager` 로 찾는다. brew의 `android-commandlinetools`를 쓰고 있다면 `avdmanager`가 PATH에 이미 있을 수 있다.

- [ ] **Step 2: 에뮬레이터 기동**

모델이 2.5GB이고 로드 시 메모리를 많이 쓰므로 RAM과 디스크를 넉넉히 준다. 호스트가 24GB이므로 8GB 할당은 안전하다.

```bash
"$ANDROID_HOME/emulator/emulator" -avd adk-arm64 -memory 8192 -partition-size 8192 &
```

- [ ] **Step 3: 부팅 대기 후 설치 — 완료 기준 3**

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
"$ANDROID_HOME/platform-tools/adb" wait-for-device
"$ANDROID_HOME/platform-tools/adb" shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 2; done'
cd android && ./gradlew :app:installDebug
```
Expected: `Installed on 1 device`.

- [ ] **Step 4: 앱 실행 후 모델 확보 — 완료 기준 4**

앱을 실행하고 **모델 내려받기**를 누른다. 2.5GB이므로 시간이 걸린다.

이미 `.litertlm` 파일을 갖고 있다면 다운로드 대신 밀어 넣는 쪽이 훨씬 빠르다. 앱을 한 번 실행해 디렉터리가 생긴 뒤:

```bash
"$ANDROID_HOME/platform-tools/adb" push your-model.litertlm \
  /sdcard/Android/data/dev.starryeye.ondeviceagent/files/
```

Expected: 앱이 "준비됐습니다"를 표시하고 입력창이 열린다.

로드에 실패하면 그 메시지를 그대로 보고할 것. `UnsatisfiedLinkError`라면 이 에뮬레이터 이미지에서 LiteRT-LM 네이티브 라이브러리가 로드되지 않는다는 뜻이며(스펙 8장의 리스크), 실기기로 옮겨야 한다.

- [ ] **Step 5: 일반 대화 — 완료 기준 5**

"안녕, 너는 뭐야?" 를 보낸다.

Expected: 답이 스트리밍으로 흘러나온다. 에뮬레이터에서는 수십 초가 걸릴 수 있다 — 느린 것은 정상이며 실패가 아니다.

- [ ] **Step 6: 툴 호출 — 완료 기준 6, 이 프로젝트의 검증점**

에뮬레이터 배터리는 기본 100% 고정이라 우연의 일치와 구분되지 않는다. 값을 강제로 바꿔서 확인한다.

```bash
"$ANDROID_HOME/platform-tools/adb" shell dumpsys battery set level 42
```

앱에서 "배터리 몇 퍼센트야?" 를 보낸다.

Expected: 화면에 `툴 호출: get_battery_level` 이 뜨고, 답변에 **42** 가 등장한다.

값이 42가 아니거나 툴 호출 줄이 없으면 툴 루프가 돌지 않은 것이다. `adb logcat`으로 원인을 확인하고, 모델이 툴 호출을 거부하는 것이라면 스펙 3.5절에 적어둔 대로 모델의 툴 호출 능력 문제일 수 있다.

끝나면 배터리를 되돌린다:

```bash
"$ANDROID_HOME/platform-tools/adb" shell dumpsys battery reset
```

- [ ] **Step 7: 오프라인 확인 — 완료 기준 7**

```bash
"$ANDROID_HOME/platform-tools/adb" shell svc wifi disable
"$ANDROID_HOME/platform-tools/adb" shell svc data disable
```

Step 5·6을 반복한다.

Expected: 동일하게 동작한다. **이것이 온디바이스라는 증거다.**

끝나면 되돌린다:

```bash
"$ANDROID_HOME/platform-tools/adb" shell svc wifi enable
"$ANDROID_HOME/platform-tools/adb" shell svc data enable
```

- [ ] **Step 8: `server/README.md` 작성**

```markdown
# 인증 서버 (예정)

아직 구현하지 않았다. 이 디렉터리는 자리표시다.

에이전트가 인증이 필요한 행동을 툴로 호출하는 시나리오를 다루게 된다. `android/`와는
별도의 Gradle 루트로 두어, 안드로이드 빌드와 서로 간섭하지 않게 한다.

전제 조건인 "온디바이스 에이전트의 툴 호출"은 `android/`에서 먼저 검증했다.
```

- [ ] **Step 9: `android/README.md` 작성**

````markdown
# 온디바이스 ADK 에이전트 앱

LiteRT-LM으로 도는 온디바이스 ADK 에이전트. 화면 하나, 툴 하나.

## 사전 준비

Android SDK가 필요하다. `$ANDROID_HOME`을 설정하고 아래를 설치한다.

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-36" "build-tools;36.0.0" \
  "emulator" "system-images;android-36;google_apis;arm64-v8a"
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

## 빌드와 테스트

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## 실행

에뮬레이터는 arm64 이미지를 쓴다. 모델이 크므로 메모리를 넉넉히 준다.

```bash
avdmanager create avd -n adk-arm64 -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_7
"$ANDROID_HOME/emulator/emulator" -avd adk-arm64 -memory 8192 -partition-size 8192 &
adb wait-for-device
./gradlew :app:installDebug
```

## 모델 확보

앱을 처음 열면 모델이 없다고 나온다. **모델 내려받기**를 누르면
`gemma-4-E2B-it.litertlm`(약 2.5GB)을 받는다. Wi-Fi를 쓸 것.

이미 `.litertlm` 파일이 있으면 밀어 넣는 편이 빠르다. 앱을 한 번 실행해 디렉터리가
만들어진 뒤:

```bash
adb push your-model.litertlm /sdcard/Android/data/dev.starryeye.ondeviceagent/files/
```

직접 넣은 파일이 내려받은 파일보다 우선한다. 툴 호출이 되려면 **툴 호출용으로 학습된
모델**이어야 한다.

## 툴 호출 확인

이 앱이 증명하려는 것. 에뮬레이터 배터리는 100% 고정이라 값을 강제로 바꿔서 확인한다.

```bash
adb shell dumpsys battery set level 42
```

앱에서 "배터리 몇 퍼센트야?"라고 묻는다. `툴 호출: get_battery_level`이 뜨고 답변에
**42**가 나오면 온디바이스 LLM + 툴 호출 루프가 끝까지 돈 것이다.

```bash
adb shell dumpsys battery reset
```

## 오프라인 확인

```bash
adb shell svc wifi disable && adb shell svc data disable
```

이 상태에서 대화가 그대로 되면 온디바이스임이 증명된다. 네트워크는 모델을 받을 때만
쓴다.

## 알려진 한계

- 에뮬레이터는 CPU로만 추론하므로 한 문장에 수십 초가 걸릴 수 있다. 느린 것은 정상이다.
- 작은 모델은 툴 호출을 항상 안정적으로 하지는 않는다.
````

- [ ] **Step 10: 저장소 루트 `README.md` 갱신**

현재 내용은 제목 한 줄뿐이다. 아래로 대체한다.

````markdown
# on-device-ai-agent-authn

온디바이스 AI 에이전트와 인증을 다루는 연구용 저장소.

## 구조

| 디렉터리 | 상태 | 내용 |
|---|---|---|
| `android/` | 구현됨 | ADK for Android 온디바이스 에이전트 샘플 앱 |
| `server/` | 예정 | 인증 서버 |
| `docs/superpowers/` | — | 설계 문서와 구현 계획 |

`android/`와 `server/`는 각각 독립된 Gradle 루트다. 안드로이드 빌드와 서버 빌드가
서로 간섭하지 않게 하려는 것이다.

## 왜 LiteRT-LM인가

ADK for Android가 제공하는 온디바이스 백엔드는 ML Kit(Gemini Nano)과 LiteRT-LM 둘이다.
ML Kit 쪽은 **툴 호출을 지원하지 않고**, AICore가 탑재된 기기(Galaxy S24 이상, Pixel 8 Pro
이상 등)에서만 동작해 에뮬레이터로 검증할 수 없다. 툴 호출이 없으면 에이전트가 아니라
챗봇이고, 이 저장소가 향하는 "인증이 필요한 행동을 에이전트가 툴로 호출한다"는 주제로
갈 수 없다. 그래서 LiteRT-LM을 골랐다.

Gemini Nano로 옮기고 싶어지면 모델 백엔드만 교체하면 된다. 나머지 구조는 그대로다.

## 문서

- [설계](docs/superpowers/specs/2026-08-26-on-device-adk-agent-design.md)
- [구현 계획](docs/superpowers/plans/2026-08-26-on-device-adk-agent.md)
- [앱 실행 방법](android/README.md)
````

- [ ] **Step 11: 최종 검증 — 완료 기준 1·2**

```bash
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, 테스트 9개 PASS.

- [ ] **Step 12: 커밋 & 푸시**

```bash
git add README.md android/README.md server/README.md && git commit -m "docs: 실행 절차와 저장소 안내" && git push
```

- [ ] **Step 13: 결과 보고**

완료 기준 7개 각각에 대해 통과 여부를 **실제로 관찰한 출력과 함께** 보고한다. 통과하지 못한 항목이 있으면 그것을 명시한다. 짐작으로 통과했다고 적지 않는다.
