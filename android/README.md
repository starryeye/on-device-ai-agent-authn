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
