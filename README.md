# on-device-ai-agent-authn

온디바이스 AI 에이전트와 인증을 다루는 연구용 저장소.

## 구조

| 디렉터리 | 상태 | 내용 |
|---|---|---|
| `android/` | 구현됨 | ADK for Android 온디바이스 에이전트 샘플 앱 |
| `server/` | 구현됨 | 에이전트 신원 발급 서버 — Key Attestation 체인 검증 + 정책 + DPoP 자격증명 |
| `docs/superpowers/` | — | 설계 문서와 구현 계획 |

`android/`와 `server/`는 각각 독립된 Gradle 루트다. 안드로이드 빌드와 서버 빌드가
서로 간섭하지 않게 하려는 것이다.

## 인증 연구의 세 사이클

이 저장소는 온디바이스 에이전트에 인증을 붙이는 문제를 세 단계로 나눠 다룬다.

1. **① 에이전트 신원** — 에이전트가 하드웨어(Android Keystore)에 묶인 자기 신원을 갖는다.
   `server/`가 Key Attestation 체인을 검증해 그 신원을 발급하고, `android/`의 클라이언트가
   키를 만들어 등록하고 재시작 간에 재사용한다. **완료, 실기기 검증됨** — 자동화
   테스트는 서버 68개, 안드로이드 16개가 모두 통과하고, 2026-08-30에 Galaxy A36
   실기기로 종단 간 흐름(최초 등록 → 재시작 후 신원 유지 → 채팅 동작 →
   STRONGBOX/기기 증명 정책 거절 → 정책 복원 후 재등록)을 직접 관찰했다. 재시작 후
   같은 `agentId`가 재사용되는 것, 정책 위반 시 `POLICY_SECURITY_LEVEL`·
   `POLICY_DEVICE_BINDING`으로 거절되는 것을 실기기에서 확인했다. 세부 관찰 결과와
   재현 절차는 [server/README.md](server/README.md)의 "실기기 검증" 절에 있다.
   남은 인증 사이클(②·③)이 끝나야 최종적으로 이 프로젝트가 완결된다는 점에서
   "완료"는 사이클 ①에 한정된다. 설계:
   [docs/superpowers/specs/2026-08-27-agent-identity-registration-design.md](docs/superpowers/specs/2026-08-27-agent-identity-registration-design.md),
   운영 문서: [server/README.md](server/README.md)
2. **② 사용자 위임** — 에이전트가 "누구를 대신해" 행동하는지(`act` 클레임, OBO 패턴)를 신원에
   싣는다. 아직 시작 전.
3. **③ 행동별 인가** — 신원과 위임이 갖춰진 다음, 에이전트가 시도하는 개별 행동(툴 호출)마다
   무엇을 허용할지 판단한다(step-up 인증 포함). 아직 시작 전.

①이 끝나야 ②·③이 의미를 갖는다 — 위임도 행동별 인가도 "누가 요청했는가"가 먼저 확정돼야
하기 때문이다.

## 왜 LiteRT-LM인가

ADK for Android가 제공하는 온디바이스 백엔드는 ML Kit(Gemini Nano)과 LiteRT-LM 둘이다.
ML Kit 쪽은 **툴 호출을 지원하지 않고**, AICore가 탑재된 기기(Galaxy S24 이상, Pixel 8 Pro
이상 등)에서만 동작해 에뮬레이터로 검증할 수 없다. 툴 호출이 없으면 에이전트가 아니라
챗봇이고, 이 저장소가 향하는 "인증이 필요한 행동을 에이전트가 툴로 호출한다"는 주제로
갈 수 없다. 그래서 LiteRT-LM을 골랐다.

Gemini Nano로 옮기고 싶어지면 모델 백엔드만 교체하면 된다. 나머지 구조는 그대로다.

## 문서

- [설계 — 온디바이스 ADK 에이전트](docs/superpowers/specs/2026-08-26-on-device-adk-agent-design.md)
- [구현 계획 — 온디바이스 ADK 에이전트](docs/superpowers/plans/2026-08-26-on-device-adk-agent.md)
- [앱 실행 방법](android/README.md)
- [설계 — 에이전트 신원 등록 (사이클 ①)](docs/superpowers/specs/2026-08-27-agent-identity-registration-design.md)
- [구현 계획 — 에이전트 신원 등록](docs/superpowers/plans/2026-08-28-agent-identity-registration.md)
- [서버 실행 방법](server/README.md)
