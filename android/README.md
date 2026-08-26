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

## 관찰된 결과

이 확인은 `adk-arm64` 에뮬레이터(API 36, arm64)에서 `gemma-4-E2B-it.litertlm`으로 진행했다.

한 대화에서 배터리 값 변화를 추적했다:

1. "What is my battery percentage?"
   → `툴 호출: get_battery_level`
   → "Your battery is at 100%."

2. 외부에서 배터리를 강제 변경: `adb shell dumpsys battery set level 42`

3. "What is my battery percentage now?"
   → `툴 호출: get_battery_level`
   → "Your battery is currently at 42%."

이 형태의 확인이 중요한 이유는 값이 정말 도구에서 읽혀 왔다는 것을 증명하기 때문이다. 모델이 추측하고 있었다면 또는 바로 이전의 "100"을 반복하고 있었다면, 다시 묻는 것에 다른 답을 줄 수 없다. 외부 변화 후 답이 42로 바뀐 것은 그 숫자가 도구 호출에서만 올 수 있다는 증거다.

### 언어를 바꿔 다시 물었을 때

처음에는 한국어 프롬프트에서 툴이 호출되지 않는 것으로 보였고, 이 문서도 한때 "한국어
경로는 툴 호출이 보장되지 않는다"고 적고 있었다. 그 진단은 틀렸다. 관찰이 영어로 답이 오간
뒤 이어진 질문 하나뿐이어서, 언어 때문인지 다른 이유인지 구분되지 않은 채 언어를 원인으로
지목한 것이었다.

조건을 갈라 다시 재 보니 무너지는 지점이 달랐다. 조건마다 새 대화에서 3회씩:

| 조건 | 툴 호출 |
|---|---|
| 새 대화 · 영어 | 3/3 |
| 새 대화 · 한국어 | 3/3 |
| 이어지는 대화 · 영어로 재질문 | 3/3 |
| 이어지는 대화 · **한국어로 재질문** | **0/3** |

한국어 자체는 문제가 아니었다. 무너지는 조건은 **대화에 이미 답이 있는 상태에서 언어가
바뀌는 것**이었다. 세 번 모두 답은 `"배터리는 63%입니다."`였는데, 실제 값은 88이었다 —
툴을 다시 부르지 않고 직전 영어 답변의 63을 번역해 되풀이한 것이다.

원인은 지시문이었다. "배터리를 물으면 툴을 호출하라"는 있었지만 *다시* 물었을 때의 규칙이
없었고, 모델은 이미 답을 가진 질문에 대해 툴 호출을 건너뛰었다. 지시문에 언어와 무관하게
매번 호출하라는 규칙과, 이전 숫자는 낡았으니 번역해 되풀이하지 말라는 규칙을 넣어 고쳤다
([OnDeviceAgent.kt](app/src/main/kotlin/dev/starryeye/ondeviceagent/agent/OnDeviceAgent.kt)).
수정 후 위 네 조건에 "한국어로 답한 뒤 영어로 재질문"까지 더한 다섯 조건이 모두 통과한다.

## 툴 호출 회귀 테스트

위 다섯 조건은
[ToolCallLanguageTest](app/src/androidTest/kotlin/dev/starryeye/ondeviceagent/agent/ToolCallLanguageTest.kt)가
기기에서 실제 모델로 잰다. 화면과 입력기를 거치지 않고 에이전트를 직접 부르므로, 남는 변수는
질문뿐이다. 툴을 불렀는지만이 아니라 **답에 새 값이 담겼는지**까지 확인하므로, 툴을 부르고도
옛 숫자를 되풀이하면 실패한다.

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

**플래그를 빼면 안 된다.** `connectedAndroidTest`는 실행이 끝나면 앱 패키지를 삭제하는데,
모델이 앱 전용 외부 디렉터리에 있어 2.5GB가 함께 지워진다. 모델 원본을 기기 밖에도 한 벌
두고 `adb push`로 복구하는 편이 안전하다.

한 번 실행에 다섯 조건 × 3회 = 15 turn이라 에뮬레이터에서는 몇 분이 걸린다.

## 오프라인 확인

```bash
adb shell svc wifi disable && adb shell svc data disable
```

이 상태에서 대화가 그대로 되면 온디바이스임이 증명된다. 네트워크는 모델을 받을 때만
쓴다.

## 알려진 한계

- 에뮬레이터는 CPU로만 추론하므로 한 문장에 수십 초가 걸릴 수 있다. 느린 것은 정상이다.
- 작은 모델은 툴 호출을 항상 안정적으로 하지는 않는다. 실제로 이 앱에서도 지시문이 부실할 때
  한 조건이 결정적으로 무너졌다(위 "언어를 바꿔 다시 물었을 때"). 지시문을 고쳐 막았지만,
  툴이 늘어나면 같은 종류의 구멍이 다시 생길 수 있다 — 조건을 갈라 재 보는 것 말고는 알 방법이
  없다.
