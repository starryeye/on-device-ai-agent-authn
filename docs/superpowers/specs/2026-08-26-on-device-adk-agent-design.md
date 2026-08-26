# 온디바이스 AI 에이전트 (ADK for Android) — 설계

- 작성일: 2026-08-26
- 상태: 승인됨, 구현 대기
- 범위: 갤럭시(및 에뮬레이터)에서 도는 최소 온디바이스 ADK 에이전트 샘플 앱

## 1. 목적

ADK for Android가 온디바이스에서 **툴 호출까지 포함해 실제로 동작하는지**를 끝까지 확인하는
최소 샘플 앱을 만든다. 화면 하나, 툴 하나, 에이전트 하나로 배선을 증명하는 것이 전부이며,
프로덕션 앱을 만드는 것이 목적이 아니다.

이 저장소는 이후 인증(authn) 주제로 확장될 예정이다. 인증이 필요한 행동을 에이전트가 툴로
호출하려면 **툴 호출 루프가 반드시 동작해야** 하므로, 이번 샘플이 그 전제를 검증한다.

## 2. 배경 조사 결과

구현 전에 확인한 사실들. 설계의 근거이므로 남긴다.

### 2.1 ADK for Android 버전

문서 페이지(developer.android.com/ai/adk)의 `0.1.0`은 발표 시점 값이다. Maven Central에서
직접 확인한 실제 최신 버전:

| 아티팩트 | 버전 |
|---|---|
| `com.google.adk:google-adk-kotlin-core-android` | `0.8.0` |
| `com.google.adk:google-adk-kotlin-litertlm-android` | `0.8.0` |
| `com.google.adk:google-adk-kotlin-mlkit-android` | `0.8.0-beta` |
| `com.google.adk:google-adk-kotlin-processor` (KSP) | `0.8.0` |

### 2.2 모델 백엔드 선택: LiteRT-LM

ADK Android가 제공하는 온디바이스 백엔드는 둘이다.

| | ML Kit / Gemini Nano | LiteRT-LM |
|---|---|---|
| 모델 보관 | OS(AICore)가 관리 | 앱이 자기 저장소에 보관 |
| 추론 위치 | 폰의 시스템 프로세스 | 폰의 앱 프로세스 |
| 기기 요구 | **AICore 탑재 기기 전용** (S24+, Pixel 8 Pro+ 등) | 없음 |
| 에뮬레이터 | **불가** (AICore 없음) | 가능 |
| 툴 호출 | **미지원** | 지원 |
| 아티팩트 성숙도 | `-beta` | 정식 |

**LiteRT-LM을 택한다.** 결정적인 이유는 두 가지다.

1. ML Kit 백엔드는 툴 호출을 지원하지 않는다. 툴이 없으면 에이전트가 아니라 챗봇이고,
   이 저장소의 다음 단계(인증 툴 호출)로 갈 수 없다.
2. AICore를 요구하지 않으므로 기기 기종을 타지 않고 에뮬레이터에서 검증할 수 있다.
   현재 대상 갤럭시 기종이 확정되지 않았으므로 이 점이 중요하다.

Gemini Nano로 가고 싶어지면 나중에 모델 백엔드만 교체하면 된다. 나머지 구조는 그대로다.

### 2.3 호스트 환경

- Apple Silicon(arm64), RAM 24GB, 여유 디스크 585GB → 8GB짜리 arm64 AVD 구동 가능
- JDK 17 (Corretto), Gradle, brew `android-commandlinetools` 설치됨
- **`ANDROID_HOME` 미설정, SDK 본체/adb/에뮬레이터 없음** → 별도 세팅 필요
- Android Studio 없음. 빌드·실행은 CLI로 진행한다

### 2.4 참고한 공식 예제

`github.com/google/adk-kotlin`의 `examples/android` (LiteRT-LM chat)와 `litertlm` 모듈
소스를 직접 읽어 API 형태를 확인했다. 추측으로 쓴 API는 없다.

## 3. 사용자 시나리오

등장인물은 폰을 든 사용자 한 명. 화면도 하나.

### 3.1 최초 실행 (한 번만)

1. 앱 실행. 채팅 화면이 뜨지만 입력창은 잠겨 있고 안내 메시지가 보인다:
   "이 앱은 온디바이스 모델 파일(약 2.5GB)이 필요합니다."
2. **다운로드** 버튼 → 진행률 표시. Hugging Face
   (`litert-community/gemma-4-E2B-it-litert-lm`)에서 `gemma-4-E2B-it.litertlm`을
   앱 전용 저장소로 스트리밍한다.
   - 개발자는 이 단계를 건너뛰고 `adb push`로 밀어 넣을 수 있다.
3. 완료되면 자동으로 모델을 로드한다(수 초). 입력창이 열린다.

**이 다운로드가 앱이 네트워크를 쓰는 유일한 순간이다.** 이후로는 완전히 오프라인이다.

### 3.2 평상시 사용

4. **일반 대화** — 폰 CPU에서 토큰이 생성되며 답변이 스트리밍된다.
5. **툴 호출** — "지금 배터리 몇 퍼센트야?"
   - 모델은 이 값을 알 수 없으므로 `get_battery_level` 호출을 결정한다.
   - ADK Runner가 Kotlin 함수를 실행 → `BatteryManager` 실측값 → 모델에 되먹임.
   - 모델이 문장을 만든다: "현재 배터리는 42%입니다."

**5번이 이 프로젝트 전체의 검증점이다.** 실제 수치가 나오면 온디바이스 LLM + 툴 호출
루프가 끝까지 돌았다는 뜻이다.

### 3.3 결정적 확인

6. **비행기 모드**를 켜고 4~5번 반복 → 동일하게 동작 → 온디바이스임이 증명된다.
7. 앱 재시작 → 모델을 다시 받지 않는다. 대화 내용은 사라진다
   (`InMemorySessionService`. 대화 영속화는 이번 범위 밖).

### 3.4 실패 경로

| 상황 | 사용자가 보는 것 |
|---|---|
| 다운로드 실패 | "다운로드 실패: <이유>" + 재시도. 받다 만 파일은 `.part`로 남아 모델로 오인되지 않음 |
| 모델 로드 실패 | "모델을 불러오지 못했습니다: <이유>". 입력창은 잠긴 채 유지 |
| 배터리 값 읽기 실패 | 툴이 오류를 반환하고 모델이 그 사실을 문장으로 답함. 앱은 죽지 않음 |

### 3.5 알려진 한계

- **에뮬레이터는 느리다.** GPU 가속 없이 CPU로만 도는 2B급 모델이라 한 문장에 수십 초가
  걸릴 수 있다. 동작 확인에는 충분하나 사용감은 실기기여야 한다.
- **작은 모델은 툴 호출을 안정적으로 하지 못한다.** 2.5GB 모델을 고른 이유다. 툴 호출용으로
  학습된 모델이어야 시나리오 5가 안정적으로 돈다.

## 4. 저장소 구조

```
on-device-ai-agent-authn/
├─ README.md                      # 저장소 소개
├─ docs/superpowers/specs/        # 설계 문서
├─ android/                       # 이번 작업. 독립 Gradle 빌드
│  ├─ settings.gradle.kts
│  ├─ gradle/libs.versions.toml
│  ├─ gradle.properties
│  ├─ gradlew, gradlew.bat, gradle/wrapper/
│  └─ app/
│     ├─ build.gradle.kts
│     └─ src/
│        ├─ main/kotlin/dev/starryeye/ondeviceagent/
│        └─ test/kotlin/dev/starryeye/ondeviceagent/
└─ server/README.md               # 나중에 만들 인증 서버 자리표시
```

`android/`와 `server/`는 **각각 독립된 Gradle 루트**로 둔다. 하나로 묶으면 AGP와 서버 쪽
플러그인이 같은 빌드에서 충돌하기 쉽고 JDK 타깃도 다르다. 나누면 각자 자기 속도로 간다.

패키지: `dev.starryeye.ondeviceagent`

## 5. 앱 구조

파일 6개.

| 파일 | 역할 | 의존 |
|---|---|---|
| `MainActivity.kt` | Compose 진입점 | `AgentViewModel`, `ChatScreen` |
| `ui/ChatScreen.kt` | 메시지 목록 + 입력창 + 다운로드 바 | 없음 (상태를 인자로 받음) |
| `AgentViewModel.kt` | 상태 보유, Runner 구동, 엔진 수명 관리 | `OnDeviceAgent`, `ModelStore` |
| `agent/OnDeviceAgent.kt` | `LiteRtLmModel` 생성 + `LlmAgent` 정의 | `DeviceTools` |
| `agent/DeviceTools.kt` | `@Tool` 1개 (`get_battery_level`) + `BatteryReader` 인터페이스와 그 안드로이드 구현 | 없음 |
| `model/ModelStore.kt` | `.litertlm` 파일 탐색 / 다운로드 | 없음 |

각 단위의 경계:

- `ChatScreen`은 상태를 인자로만 받는다. 에이전트를 모른다 → 프리뷰 가능, 교체 가능.
- `ModelStore`는 ADK를 모른다. 파일을 찾고 받아오는 일만 한다 → 순수 테스트 가능.
- `DeviceTools`는 `BatteryReader` 인터페이스에 의존한다 → 안드로이드 없이 테스트 가능.
- `AgentViewModel`만 이들을 조립한다.

### 5.1 의존성

```kotlin
implementation("com.google.adk:google-adk-kotlin-core-android:0.8.0")
implementation("com.google.adk:google-adk-kotlin-litertlm-android:0.8.0")
implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")  // 네이티브 런타임
ksp("com.google.adk:google-adk-kotlin-processor:0.8.0")                 // @Tool -> FunctionTool
```

빌드 설정: `minSdk 26`, `compileSdk 36`, `targetSdk 36`, JVM toolchain 17,
Compose 활성화. 인터넷 권한 필요(모델 다운로드용).

버전 조합은 공식 예제가 검증한 값에서 출발한다: Kotlin 2.3.21 / KSP 2.3.9 /
Compose 컴파일러 2.2.10.

### 5.2 툴

툴은 `get_battery_level` **하나**다. 인자가 없어 가장 단순하면서, 모델이 결코 알 수 없는
실제 기기 상태를 반환한다. 따라서 **응답에 실제 배터리 수치가 나오면 툴이 실행됐다는 증거**가
된다.

```kotlin
@Tool(name = "get_battery_level",
      description = "Returns this device's current battery charge, as a percentage.")
fun getBatteryLevel(): Map<String, Any>
```

`0..100` 범위를 벗어난 값(기기가 보고하지 못하는 경우)은 모델에 넘기지 않고
`FunctionTool.ERROR_KEY`로 오류를 반환한다.

## 6. 데이터 흐름

```
사용자 입력
   ↓
AgentViewModel.send(text)
   ↓
runner.runAsync(userId, sessionId, Content(Role.USER, [Part(text)]))
   ↓  Flow<Event>
   ├─ 모델이 툴 호출 결정 → Runner가 DeviceTools.getBatteryLevel() 실행
   │                        → 결과를 모델에 되먹임 (이 왕복은 ADK가 처리)
   └─ 텍스트 조각 → collect → messages 갱신 → Compose 재구성
```

앱 코드는 툴 호출 루프에 관여하지 않는다. `@Tool` 함수를 선언하고 이벤트를 받아 그릴 뿐이다.

### 6.1 상태

```
NeedsModel → Downloading(progress) → Loading → Ready
                                            ↘ Failed(reason)
```

입력창은 `Ready`에서만 열린다.

### 6.2 수명 관리

`LiteRtLmModel`은 네이티브 엔진을 소유하므로 **반드시 닫아야 한다.**
`ViewModel.onCleared()`에서 IO 스레드로 닫는다. 빠뜨리면 메모리가 새고 재실행 시 두 번째
로드가 실패한다.

모델 로드·다운로드·해제는 전부 `Dispatchers.IO`에서 수행한다. 로드는 수 초가 걸리므로
메인 스레드에서 하면 ANR이 난다.

세션은 로드 직후 한 번 만들어 앱이 사는 동안 재사용한다 → 멀티턴 대화가 성립한다.

ViewModel을 쓰는 이유는 하나다: 화면 회전마다 2.5GB 모델을 다시 로드하면 앱을 쓸 수 없다.

## 7. 테스트 전략

**이 앱에서 자동 테스트가 닿는 범위는 넓지 않다.** LLM 응답과 ADK 배선은 단위 테스트로
잡히지 않는다. 그래서 나눈다.

### 7.1 TDD로 구현할 것 (JVM 단위 테스트, 안드로이드 의존 없음)

1. **`ModelStore`의 파일 선택 규칙**
   - 사용자가 push한 파일이 다운로드본보다 우선
   - `.part`(받다 만 파일)는 무시
   - 아무것도 없으면 `null`
   - 사용자 파일이 여럿이면 이름순 첫 번째
   - 임시 디렉터리로 검증한다.

2. **배터리 값 → 응답 변환 규칙**
   - `0..100`이면 값을 담는다
   - 벗어나면 `FunctionTool.ERROR_KEY`로 오류를 담는다
   - `BatteryManager`를 `BatteryReader` 인터페이스 뒤로 밀어내 순수 함수로 만든다.

### 7.2 수동으로 검증할 것

시나리오(3장) 그대로:

```bash
./gradlew :app:assembleDebug          # 빌드
./gradlew :app:testDebugUnitTest      # 단위 테스트
./gradlew :app:installDebug           # 에뮬레이터/기기 설치
adb shell dumpsys battery set level 42
```

이후 앱에서 "배터리 몇 퍼센트야?"를 물어 **42**가 나오는지 확인하고, 비행기 모드에서
반복한다. 에뮬레이터 배터리는 기본 100% 고정이라 값을 강제로 바꿔야 툴 실행을 증명할 수 있다.

**10장의 완료 기준 7개가 모두 통과하기 전에는 완료라고 말하지 않는다.**

## 8. 리스크

| 리스크 | 대응 |
|---|---|
| AGP 내장 Kotlin ↔ KSP ↔ Compose 컴파일러 **버전 정합성**. 셋이 어긋나면 빌드가 깨진다 (공식 예제도 주석으로 경고) | 검증된 조합에서 출발하고, **스캐폴딩 단계에서 빈 앱 빌드를 먼저 통과**시킨 뒤 코드를 얹는다 |
| 에뮬레이터 arm64 이미지에서 LiteRT-LM 네이티브 라이브러리가 로드되는지 | Apple Silicon이라 arm64 AVD가 네이티브로 돈다. 그래도 실제 로드 확인 전까지는 가정으로 취급한다 |
| 2.5GB 다운로드 부담 | `adb push` 경로를 처음부터 지원한다. 모델은 `ModelStore` 상수 교체로 바꿀 수 있다 |
| SDK 미설치 (`ANDROID_HOME` 없음) | `sdkmanager`로 platform-tools / platforms / build-tools / emulator / arm64 시스템 이미지를 설치하고 `local.properties`를 설정한다 |

## 9. 범위 밖 (이번에 하지 않는 것)

- 인증(authn) 기능 — 다음 사이클
- `server/` 실제 구현 — README 자리표시만
- 대화 영속화 (Room 세션 서비스)
- 여러 툴, 서브 에이전트, 멀티 에이전트 오케스트레이션
- Gemini Nano / ML Kit 백엔드
- 클라우드 모델 fallback
- 릴리스 빌드, 서명, 난독화

## 10. 완료 기준

1. `./gradlew :app:assembleDebug` 성공
2. `./gradlew :app:testDebugUnitTest` 통과
3. 에뮬레이터에 설치되어 실행됨
4. 모델을 얻고(다운로드 또는 push) 로드에 성공함
5. 일반 대화가 오간다
6. 배터리를 42%로 강제한 뒤 질문했을 때 응답에 **42**가 나온다
7. 비행기 모드에서 5·6이 동일하게 동작한다
