# 에이전트 신원 발급 서버

온디바이스 에이전트에게 하드웨어에 묶인 신원을 발급한다. Android Key Attestation 체인을
검증하고, 설정 가능한 정책을 적용하고, DPoP로 소유를 증명하게 만든 자격증명을 내준다.
설계: [../docs/superpowers/specs/2026-08-27-agent-identity-registration-design.md](../docs/superpowers/specs/2026-08-27-agent-identity-registration-design.md)

## 사전 준비

Key Attestation 검증에 구글 공식 Kotlin 라이브러리를 쓴다. Maven Central 에 없어
git 서브모듈로 받는다(`server/third_party/keyattestation`).

```bash
git submodule update --init --recursive
```

Java 21 이 필요하다(그 라이브러리의 요구사항). Gradle 툴체인이 자동으로 받아온다 — 로컬에
Java 21 이 없어도 첫 빌드에서 내려받는다.

## 빌드, 테스트, 실행

```bash
./gradlew build          # 컴파일 + 테스트
./gradlew test           # 테스트만 (68개)
./gradlew bootRun         # localhost:8080 에서 기동
```

데이터는 `./data/agent-identity`에 파일 기반 H2 DB로 쌓인다. 신원을 전부 지우고 처음부터
다시 보고 싶으면 서버를 멈추고 이 파일들을 지운 뒤 다시 띄우면 된다.

## 안드로이드에서 접속

기기(에뮬레이터 포함)에서 맥의 서버로 닿게 한다.

```bash
adb reverse tcp:8080 tcp:8080
```

`android/`의 클라이언트는 기본적으로 `http://127.0.0.1:8080`을 호출한다.

## 엔드포인트

모두 JSON을 주고받는다. 등록 흐름은 인증이 없고(신원이 아직 없으므로), 갱신·조회는 `DPoP`
헤더(RFC 9449 proof, 발급받은 에이전트 키로 서명)로 인증한다.

| | 메서드/경로 | 인증 | 하는 일 |
|---|---|---|---|
| ① | `POST /agent/registration/challenge` | 없음 | 1회용 challenge를 발급한다(`registrationId`, `challenge`, `expiresIn`). 용량 상한(`max-pending-challenges`)에 닿으면 `503 CHALLENGE_STORE_FULL` |
| ② | `POST /agent/registration` | 등록 PoP(body의 `pop`) | attestation 체인 + PoP + challenge를 검증하고 정책을 적용한다. 통과하면 `agentId`와 `credential`을 발급한다. 거절은 전부 `403`(사유는 `reason` 필드) |
| ③ | `POST /agent/credential` | `DPoP` 헤더 | attestation을 다시 하지 않고 자격증명만 새로 발급한다(하드웨어 키를 지금 쥐고 있다는 증명만으로). 거절은 `401` |
| ④ | `GET /agent/whoami` | `DPoP` 헤더 | proof 서명자 지문으로 신원을 찾아 `agentId`를 돌려준다. 거절은 `401` |

거절 사유 코드 전체 목록과 그 코드가 실제로 어느 경로에서 반환되는지(선언만 되고 아직
반환되지 않는 코드가 있다)는 설계 문서 §8을 참고한다.

curl로 확인할 수 있는 것은 challenge 발급뿐이다 — 나머지는 실제 하드웨어 Keystore가 만든
attestation 체인과 그 키로 서명한 proof가 있어야 하므로, 안드로이드 클라이언트(또는 실기기)
없이는 끝까지 가지 않는다.

```bash
curl -s -X POST localhost:8080/agent/registration/challenge | jq
```

## 정책 설정 (`application.yml`)

`agent-registration.*` 아래의 값들이 이 연구 프로젝트의 **실험 손잡이**다. 같은 기기, 같은
코드로 이 값만 바꿔가며 무엇이 왜 거절되는지 관찰하는 것이 목적이다. 기본값은
`PolicyProperties`에 있고, 아래처럼 `server/src/main/resources/application.yml`에
`agent-registration:` 블록을 추가해 덮어쓴다.

```yaml
agent-registration:
  require-security-level: TRUSTED_ENVIRONMENT
  require-verified-boot: true
  require-device-locked: true
  allowed-packages: [dev.starryeye.ondeviceagent]
  require-device-binding: false
  require-play-integrity: false
  agent-product-id: galaxy-personal-agent
  identifier-namespace: samsung
  challenge-ttl: 5m
  credential-ttl: 15m
  max-attestation-age: 7d
  max-pending-challenges: 10000
```

| 키 | 기본값 | 뭘 증명하려고 있는가 |
|---|---|---|
| `require-security-level` | `TRUSTED_ENVIRONMENT` | 키가 만들어진 하드웨어 등급의 하한. `STRONGBOX`로 올리면 StrongBox가 없는 기기(예: Galaxy A36)의 등록이 `POLICY_SECURITY_LEVEL`로 거절되는 것을 보여준다 — "TEE로는 충분하지 않다"는 정책을 코드 변경 없이 강제할 수 있음을 실증한다 |
| `require-verified-boot` | `true` | 부팅 체인이 검증됐는지 요구한다. `false`로 내리면 언락된 부트로더의 기기도 통과한다 — 동시에 앱 신원(`allowed-packages`) 검사도 함께 무의미해진다는 것(§4.1의 근거)을 실제로 관찰할 수 있다 |
| `require-device-locked` | `true` | 등록 시점에 화면 잠금이 걸려 있었는지 요구한다. `false`로 내리면 잠금 없는 기기도 통과한다 |
| `allowed-packages` | `[]`(검사 안 함, 개발용) | attested 패키지명 허용 목록. 값을 채우면 다른 패키지명(또는 변조된 `attestationApplicationId`)의 등록이 `POLICY_APPLICATION`으로 거절되는 것을 보여준다 |
| `require-device-binding` | `false` | 기기 자체의 증명(현재는 이음매만 있고 구현은 7장 범위 밖)을 요구한다. `true`로 올리면 `deviceBinding`이 없는 요청이 `POLICY_DEVICE_BINDING`으로 거절되는 것을 관찰할 수 있다 |
| `require-play-integrity` | `false` | Play Integrity 판정(이음매만 있음, §5.3)을 요구한다. `true`로 올리면 토큰이 없는 요청이 `POLICY_INTEGRITY`로 거절된다 |
| `agent-product-id` | `galaxy-personal-agent` | 발급되는 `agentId`(`urn:<namespace>:agent:<product>:<uuid>`)에 들어가는 제품 식별자 |
| `identifier-namespace` | `samsung` | 같은 `agentId`의 네임스페이스 부분 |
| `challenge-ttl` | `5m` | 발급된 challenge가 살아있는 시간. 지나면 등록 시도가 `CHALLENGE_INVALID`로 거절된다 |
| `credential-ttl` | `15m` | 발급되는 자격증명(JWT)의 `exp`. 지금 구현은 `/agent/credential`·`/agent/whoami`에서 이 값을 검사하지 않는다(§4.4) — 아직 강제되지 않는 값이라는 점을 실험할 때 염두에 둔다 |
| `max-attestation-age` | `7d` | 재-attestation 없이 자격증명 갱신만으로 신원을 연장할 수 있는 최대 기간. 넘기면 `REATTESTATION_REQUIRED`가 나고, 클라이언트는 새 키로 재등록한다 — 이때 **새 agentId**가 나오는 것이 정상이다(기존 Keystore 키는 새 challenge로 다시 attest할 수 없다). 짧게 줄이면 이 전이를 빠르게 관찰할 수 있다 |
| `max-pending-challenges` | `10000` | 미소비 challenge 저장소의 크기 상한. 인증 없는 `/agent/registration/challenge`를 홍수처럼 호출해 상한을 채우면 이후 발급이 `503 CHALLENGE_STORE_FULL`로 거절되는 것을 관찰할 수 있다 — 살아있는 challenge를 밀어내 자리를 만들지 않는다는 설계 선택(§8)도 함께 확인된다 |

**설계 초안에는 있었지만 구현되지 않은 손잡이**가 있다 — `allowed-signing-digests`,
`dpop.iat-skew`/`dpop.jti-cache-ttl`, `attestation.roots-url`/`crl-url`/`require-crl-check`.
이 키들을 `application.yml`에 적어도 아무 효과가 없다(대응하는 `@ConfigurationProperties`
필드가 없다). 자세한 사유는 설계 문서 §5.4를 참고한다.

## 상태

서버 쪽 자동화 테스트(68개)는 통과한다.

### 실기기 검증 (2026-08-30, Galaxy A36 / SM-A366N, Android SDK 36)

안드로이드 클라이언트 → 이 서버로 실제 종단 간 흐름을 관찰했다. 서버는 로컬 맥에서
`./gradlew bootRun`으로 띄우고 `adb reverse tcp:8080 tcp:8080`으로 연결했다.

- **최초 등록** — 앱을 새로 설치해 처음 실행하니 `새 에이전트 신원 등록:
  urn:samsung:agent:galaxy-personal-agent:5897c572-ede4-42be-a28a-fbc10c77e8c5` 줄이
  화면에 나타났고, 이어서 `서버가 확인한 신원: urn:samsung:agent:galaxy-personal-agent:
  5897c572-ede4-42be-a28a-fbc10c77e8c5`(동일한 값), `자격증명 갱신 성공
  (attestation 없이)`가 순서대로 표시됐다. 이 UUID 형식(`urn:samsung:agent:
  galaxy-personal-agent:<uuid>`)이 실기기에서 실제로 발급된 `agentId`다.
- **재시작 후 신원 유지** — 앱을 강제 종료하고 다시 켜니 `기존 에이전트 신원 재사용:
  urn:samsung:agent:galaxy-personal-agent:5897c572-ede4-42be-a28a-fbc10c77e8c5`로,
  최초 등록 때와 **완전히 같은 agentId**가 재사용됐다. `서버가 확인한 신원`도 동일했다.
- **채팅 동작** — "battery percent" 메시지를 보내니 `get_battery_level` 툴을 호출해
  "The battery level is 87%."로 정확히 응답했다. 신원 등록이 대화를 막지 않는다.
- **정책 거절 — 보안 등급** — `require-security-level: STRONGBOX`로 바꾸고 서버
  DB(`server/data/`)를 지워 재등록을 강제하니, 화면에 `신원 등록 실패:
  POLICY_SECURITY_LEVEL`이 표시됐다. Galaxy A36은 TEE 전용(StrongBox 없음)이라
  예상대로 거절된 것이다.
- **정책 거절 — 기기 증명** — `require-security-level: TRUSTED_ENVIRONMENT`로
  되돌리고 `require-device-binding: true`로 바꿔 같은 방식으로 재등록을 강제하니,
  `신원 등록 실패: POLICY_DEVICE_BINDING`이 표시됐다. 소매 기기라 기기 증명을 만들
  수 없어 예상대로 거절된 것이다.
- **정책 복원 후 재등록 성공** — 두 값을 원래대로(`TRUSTED_ENVIRONMENT`,
  `require-device-binding: false`) 되돌리고 DB를 다시 지운 뒤 재실행하니 등록에
  성공했다. 단, 이때 표시된 줄은 `서버가 모르는 키를 복구 재등록(새 신원):
  urn:samsung:agent:galaxy-personal-agent:0a212cb5-079e-4de8-880b-b70ebb42e75a`였고
  **agentId가 최초 등록 때와 달라졌다**. 이는 버그가 아니라 설계대로다 —
  `AgentRegistrar.registerWithNewKey`는 정책 거절 여부와 무관하게 매 재등록 시도마다
  로컬 Keystore 키를 새로 만들어 이전 키를 덮어쓴다. STRONGBOX·기기 증명 정책
  실험에서 거절당한 두 번의 시도가 이미 원래 키(5897c572...)를 두 차례 새 키로
  교체했으므로, 정책을 복원한 뒤의 성공은 `AGENT_NOT_FOUND` 복구 경로(새 키·새 신원)를
  탄 것이지 원래 신원(5897c572...)의 재등록이 아니다. 정책 실험을 하지 않고 단순히
  서버 DB만 지운 경우라면 같은 경로를 타되 원래 키가 살아있어 다른 결과가 나올 수
  있다 — 이 세션에서는 그 조합을 별도로 관찰하지 않았다.
- 이 세션에서 실행한 서버(`./gradlew bootRun`)의 콘솔에는 정책 거절 사유가 별도
  로그로 남지 않았고, 안드로이드 클라이언트도 HTTP 요청/응답을 logcat에 남기지
  않는다 — 위 결과는 전부 앱 화면을 스크린샷으로 캡처해 읽은 것이다.
- 부수적으로, 서버가 뜰 때마다 `HHH000305: Could not create proxy factory for:
  ...AgentIdentity`(Hibernate lazy-loading proxy 생성 실패) WARN 로그가 한 번씩
  찍혔다. Kotlin 엔티티 클래스의 getter가 기본적으로 `final`이라 Hibernate가 프록시를
  만들지 못해 나는 경고로 보이며, 기동과 요청 처리에는 영향이 없었다(등록·갱신·조회가
  모두 정상 동작했다). 원인이 궁금하면 `AgentIdentity` 엔티티에 `open`을 붙이거나
  Hibernate bytecode enhancement 플러그인을 검토해볼 만하다.
