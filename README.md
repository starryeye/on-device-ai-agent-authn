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
