# 에이전트 신원 등록 (Agent Identity Registration) — 설계

- 작성일: 2026-08-27
- 상태: 검토 대기
- 범위: 온디바이스 에이전트에게 하드웨어에 묶인 신원을 발급하고 인증하는 첫 사이클

## 1. 목적

에이전트를 **독립된 비인간 주체**로 보고, 그 신원을 발급·검증하는 경로를 만든다. 삼성 계정
개발자 입장에서 "에이전트에게 신원을 준다면 무엇을 증명받고 무엇을 발급할 것인가"에 답하는
것이 목표다.

앞 사이클에서 온디바이스 에이전트가 툴을 실제로 호출한다는 것은 확인했다
([2026-08-26 설계](2026-08-26-on-device-adk-agent-design.md)). 이 사이클은 그 에이전트에게
서버가 인정하는 이름을 붙인다.

### 1.1 3단계 중 첫 번째

| 단계 | 내용 | 상태 |
|---|---|---|
| **① 신원 발급** | 에이전트가 자기 키를 만들고 서버가 신원을 부여 | **이번** |
| ② 사용자 위임 | 사용자를 대신할 권한 (`sub`=사용자, `act`=에이전트) | 다음 |
| ③ 행동별 인가 | 민감한 툴 호출에 범위·재확인 | 그다음 |

## 2. 배경 조사 결과

설계의 근거이므로 남긴다. 모두 실기기(갤럭시 A36 5G, SM-A366N, Android 16/SDK 36)에서
직접 확인한 사실이다.

### 2.1 AppFunctions 경로를 택하지 않은 이유

애초 후보였던 "AppFunctions로 기능을 외부에 공개하고 그 호출을 인증한다"는 막힌다.

```
android.permission.EXECUTE_APP_FUNCTIONS      protectionLevel: internal|privileged
android.permission.BIND_APP_FUNCTION_SERVICE  protectionLevel: signature
```

`internal|privileged`는 일반 앱이 받을 수 없다. 이 기기에서 실제 보유 패키지는 셋뿐이다 —
`com.google.android.googlequicksearchbox`, `com.samsung.android.bixby.agent`,
`com.google.android.apps.restore`. 즉 **호출자 자리에 우리가 앉을 수 없어** 인증 로직의 끝단을
돌려볼 수 없다. 시스템 서비스(`app_function: IAppFunctionManager`)는 존재하므로 노출 자체는
가능하지만, 이번 주제에는 맞지 않는다.

### 2.2 하드웨어 보안 수준

| 기능 | 상태 |
|---|---|
| `android.hardware.strongbox_keystore` | **없음** |
| `android.hardware.hardware_keystore` | 300 (KeyMint 3.0, TEE) |
| `android.hardware.keystore.app_attest_key` | 있음 |
| `android.software.device_id_attestation` | 있음 (기기 지원. 앱이 쓸 수 있는지는 별개) |

KeyMint HAL도 `IKeyMintDevice/default` 하나뿐이다. StrongBox 인스턴스가 있으면
`.../strongbox`로 따로 잡힌다. **A36은 TEE 전용**이다.

이 사실이 오히려 이 연구의 손잡이가 된다. 보안 수준을 정책으로 두면, 같은 기기·같은 코드에서
정책만 바꿔 수용과 거절을 모두 관찰할 수 있다.

### 2.3 Device Owner / Knox

이 기기에는 이미 관리 프로필이 설정돼 있다.

```
User 10: admin=com.sds.emm.emmagent.lite.samsung/... ManagedProfileOwner(parentUserId=0)
```

Device Owner는 공장 초기화 상태의 엔터프라이즈 프로비저닝으로만 설정되며 기기당 하나다.
이 기기에서는 새로 앉힐 수 없다. 한편 Knox 계열은 존재한다.

```
android.security.samsungattestation: ISamsungAttestation
com.samsung.android.knox.attestation
```

둘 다 이번 사이클에서는 쓰지 않는다. 이유는 7장 참조.

### 2.4 표준 지형

확정 RFC와 진행 중 draft가 섞여 있다. 이번 사이클은 **확정 RFC만으로 구성**하고, draft는
②번에서 위임을 다룰 때 도입한다.

| 표준 | 상태 | 이번 사이클 |
|---|---|---|
| RFC 9449 DPoP (발신자 제한 토큰) | 확정 | **사용** |
| RFC 7638 JWK Thumbprint | 확정 | **사용** |
| RFC 7515/7519 JWS/JWT | 확정 | **사용** |
| RFC 7591 동적 클라이언트 등록 | 확정 | 형태만 참고 |
| RFC 8693 토큰 교환 / OBO | 확정 | ②번 |
| draft-oauth-ai-agents-on-behalf-of-user | draft | ②번 |
| SPIFFE / WIMSE Workload Identifier | CNCF / draft | **식별자 형식만 차용** |

**SPIFFE/WIMSE를 채택하지는 않는다.** 두 명세 모두 운영자가 노드를 통제하는 환경을
전제한다(WIMSE 아키텍처 draft의 워크로드 정의, SPIFFE의 격리 가정). 우리 노드는 사용자의
폰이고 사용자가 공격자일 수 있다. 다만 **식별자 형식**(`spiffe://trust-domain/path`)과
**단명 자격증명 자동 갱신** 원칙은 가져올 가치가 있어 차용한다.

## 3. 주체와 신뢰 경계

### 3.1 주체

| 주체 | 정체 | 이번 사이클 |
|---|---|---|
| 사용자 | 계정 소유자 | 자리만 예약 (②번) |
| 앱 | 패키지명 + 서명키 | 증거의 일부 |
| **에이전트 인스턴스** | **설치본 하나당 하나** | **발급 대상** |
| 발급 서버 | 삼성 계정 역할 | 검증·판단·발급 |

에이전트 인스턴스는 앱과 다른 주체다. 같은 APK라도 기기마다 다른 신원을 갖는다. 그래야
"어느 에이전트가 무엇을 했는가"에 답할 수 있고, 한 기기의 신원만 폐기할 수 있다.

### 3.2 신뢰 경계

```
[사용자의 폰 — 신뢰하지 않음]
   앱 프로세스: 에이전트
        │ 키 생성 (서버가 준 nonce를 attestation challenge로)
        ▼
   [TEE — 조건부 신뢰]  키쌍(추출 불가) + attestation 인증서 체인
        │                     ↑ 서명 주체는 우리가 아니라 구글 attestation 루트
        │ 등록 요청: 체인 + 소유 증명
        ▼
[서버 — 우리가 통제]  검증 → 정책 판단 → 신원 발급
```

**규칙 하나로 요약된다: 클라이언트의 주장은 받지 않고, 하드웨어가 서명한 증거만 받는다.**
앱이 요청 본문에 적어 보내는 "나는 정품이다" 류의 값은 서버가 읽지 않는다. 보안 수준도,
패키지명도, 부팅 상태도 전부 체인에서 읽는다.

신뢰의 뿌리가 우리 인프라가 아니라는 점이 SPIFFE와 갈리는 지점이다. 우리는 발급자가 아니라
**검증자**다.

## 4. 등록 프로토콜

### 4.1 언제 일어나는가

**앱 최초 실행 시 자동으로.** 대화도 모델도 관여하지 않는다. 에이전트 신원은 사용자와 무관하게
무조건 부여되는 인프라이지, 대화로 얻는 것이 아니다. 화면에는 모델 로드 메시지와 같은
시스템 줄로 결과만 표시한다.

이번 사이클에 툴은 추가하지 않는다. 툴이 자격증명을 들고 나가는 것은 에이전트가 사용자를
대신해 무언가 할 때(②·③번)이지, 신원을 갖는 단계가 아니다.

### 4.2 메시지

**① 서버가 challenge 발급**

```
POST /agent/registration/challenge
→ 200 { "challenge": "<32바이트 base64url>", "expiresIn": 300 }
```

서버가 저장하고 **1회용**으로 소비한다. 오래된 attestation 체인의 재사용을 막는다.

**② 기기가 키 생성**

Android Keystore에 EC P-256 키쌍을 만들며 그 challenge를 attestation challenge로 넣는다.
StrongBox를 먼저 시도하고 `StrongBoxUnavailableException`이면 TEE로 내려간다. 개인키는
어떤 경로로도 앱에 노출되지 않는다.

**③ 기기가 등록 요청**

```
POST /agent/registration
{
  "attestationChain": ["<leaf DER base64>", ..., "<root>"],
  "pop": "<challenge에 대한 JWS, 그 키로 서명>"
}
```

체인이 이미 challenge를 품고 있지만 소유 증명(PoP)을 따로 붙인다. 나중에 쓸 DPoP와 같은
원시 도구를 재사용하는 것이고, 키가 지금 살아 있음을 보인다.

**④ 서버가 검증**

체인을 leaf→root로 서명 검증하고 루트가 구글 attestation 루트인지 확인한 뒤, 확장
(OID `1.3.6.1.4.1.11129.2.1.17`)에서 읽는다.

| 읽는 것 | 판단 | 정책 |
|---|---|---|
| `attestationChallenge` | 우리가 준 것, 미사용, 미만료 | 고정 |
| `attestationSecurityLevel` | TEE / StrongBox | **설정** |
| `origin` | `GENERATED` (기기에서 생성) | 고정 |
| `purpose` | SIGN 포함 | 고정 |
| `attestationApplicationId` | 우리 패키지명·서명키 다이제스트 | **설정** |
| `rootOfTrust.verifiedBootState` | `Verified` | **설정** |
| `rootOfTrust.deviceLocked` | `true` | **설정** |

**⑤ 서버가 신원 발급**

```
→ 200 {
  "agentId": "spiffe://agent.samsung.example/agent/<package>/<uuid>",
  "credential": "<서버 서명 JWT>",
  "expiresIn": 900
}
```

JWT의 `sub`는 agentId, `cnf.jkt`는 에이전트 공개키 지문(RFC 7638)이다. 즉 이 토큰은 그 키를
가진 자만 쓸 수 있다.

수명은 짧게 둔다. **별도의 갱신 엔드포인트는 이번 범위 밖이다** — 만료되면 클라이언트가
①부터 다시 밟는다. 키는 이미 있으므로 새로 만들지 않고 재사용하며, attestation만 새 challenge로
다시 제출한다. 갱신 프로토콜은 폐기·순환 정책과 함께 다뤄야 의미가 있어 뒤 사이클로 미룬다.

### 4.3 자격증명 사용

```
GET /agent/whoami
Authorization: DPoP <credential>
DPoP: <RFC 9449 proof JWS>
```

서버는 proof의 `htm`/`htu`/`iat`/`jti`를 검증하고, proof를 서명한 키의 지문이 토큰의
`cnf.jkt`와 일치하는지 확인한다. 일치하면 agentId를 돌려준다.

## 5. 서버 구조

Spring Boot 3.x / Java 17. 저장소 루트의 `server/`에 독립 Gradle 빌드로 둔다.

| 패키지 | 책임 | 무엇을 모르는가 |
|---|---|---|
| `attestation` | 체인 검증, 확장 파싱 | HTTP도 JWT도 모름 |
| `policy` | 수용/거절 판단 + 사유 | 어디서 불리는지 모름 |
| `registration` | challenge 발급, 등록 오케스트레이션 | 검증 방법을 모름 |
| `identity` | 신원 저장, 자격증명 발급 | attestation을 모름 |
| `dpop` | RFC 9449 proof 검증 | 도메인을 모름 |
| `api` | 엔드포인트 | 위를 조립만 |

가장 두꺼운 곳은 `attestation`이다. 구글 확장을 ASN.1로 파싱해야 하므로 BouncyCastle이
들어온다. **이 패키지가 HTTP를 모르게 두는 것이 중요하다** — 그래야 실기기에서 뽑은 체인을
픽스처로 단위 테스트할 수 있다.

### 5.1 저장 모델

H2 파일 기반. 연구용 저장소에서 외부 DB를 세우게 하면 재현 비용만 오른다.

```
agent_identity
  id                 spiffe URI (PK)
  jwk_thumbprint     unique
  public_key
  package_name
  signing_digest
  security_level     TRUSTED_ENVIRONMENT | STRONGBOX
  verified_boot      Verified | SelfSigned | Unverified | Failed
  device_locked      boolean
  device_binding     nullable  ← 7장. 이번엔 항상 null
  subject            nullable  ← ②번에서 사용자가 들어올 자리
  created_at
  status             ACTIVE | REVOKED

challenge
  value (PK) | issued_at | expires_at | consumed_at
```

`device_binding`과 `subject`는 **지금 비워두되 자리를 만든다.** 나중에 스키마를 뜯지 않기
위해서다.

### 5.2 정책 설정

```yaml
agent-registration:
  require-security-level: TRUSTED_ENVIRONMENT   # STRONGBOX 로 올리면 A36은 거절된다
  require-verified-boot: true
  require-device-locked: true
  allowed-packages: [dev.starryeye.ondeviceagent]
  allowed-signing-digests: []                   # 비면 검사하지 않음(개발용)
  require-device-binding: false                 # 7장. 1st-party 배포에서 true 로 올린다
  challenge-ttl: 5m
  credential-ttl: 15m
```

## 6. 클라이언트 구조 (Android)

기존 앱에 패키지 하나를 더한다.

| 파일 | 책임 |
|---|---|
| `identity/AgentKeyStore.kt` | 키 생성(StrongBox→TEE 폴백), 체인 추출, 키 존재 확인 |
| `identity/DeviceBindingProvider.kt` | **이음매**. 7장 참조. 지금은 `NoDeviceBinding` |
| `identity/AgentRegistrar.kt` | 등록 흐름 (challenge → 키 → 등록 → 자격증명) |
| `identity/AgentCredential.kt` | 자격증명 보관과 만료 판단 |
| `net/DpopSigner.kt` | RFC 9449 proof 생성 |

`AgentViewModel`은 모델 로드와 나란히 등록을 시작하고, 결과를 시스템 메시지로 표시한다.
등록 실패가 채팅을 막지는 않는다 — 이번 사이클에서 자격증명을 쓰는 툴이 없기 때문이다.

## 7. 1st-party 경로 — 열렸을 때 무엇을 해야 하는가

이 앱이 삼성 공식 앱이 되면 지금 못 하는 것들이 열린다. **코드는 그 자리를 비워둔 채로 짠다.**

### 7.1 지금 설계의 한계

키가 앱 전용 저장소의 Keystore에 있으므로 **재설치하면 키가 사라지고 새 에이전트 신원이 된다.**
연구용으로는 깔끔하지만 정식 서비스에서는 문제다.

- 악용 에이전트를 폐기해도 재설치 한 번이면 새 신원을 받는다
- "이 기기에서는 에이전트 하나만" 같은 기기 단위 정책이 불가능하다
- 같은 폰인데 서버는 매번 모르는 기기로 본다

### 7.2 열리는 것

**Device ID Attestation** — attestation 레코드에 기기 식별자(시리얼 등)를 담는다. 문서상
device owner 또는 profile owner 자격이 필요하다(구현 시 확인 필요). 열리면 "같은 기기,
새 설치"를 알아볼 수 있다.

**Knox Attestation** — 이 기기에 `ISamsungAttestation`과
`com.samsung.android.knox.attestation`이 존재한다. Device Owner 없이 기기 고유 증명을
제공하지만 KPE 라이선스 영역이다.

**특권/시스템 앱** — 플랫폼 키로 서명되면 위 제약이 대부분 사라진다.

셋 다 **소매 기기에서 재현할 수 없다.** 그래서 이번 사이클의 코드 경로에는 넣지 않는다.

### 7.3 이음매 설계

클라이언트에 인터페이스를 하나 둔다.

```kotlin
/** 기기 자체를 가리키는 증거. 1st-party 경로가 열리면 구현이 들어온다. */
fun interface DeviceBindingProvider {
  /** 서버에 보낼 기기 증명. 확보할 수 없으면 null. */
  suspend fun deviceBinding(): DeviceBinding?
}

/** 지금 유일한 구현. 소매 기기에서는 기기 증명을 얻을 수 없다. */
object NoDeviceBinding : DeviceBindingProvider {
  override suspend fun deviceBinding(): DeviceBinding? = null
}
```

등록 요청 본문에는 `deviceBinding` 필드를 **지금부터 넣되 항상 null**로 보낸다. 서버는 null을
허용하고 `agent_identity.device_binding`에 null을 기록한다.

서버 정책에도 손잡이를 미리 만든다.

```yaml
agent-registration:
  require-device-binding: false   # 1st-party 배포에서 true 로 올린다
```

**이 세 가지(인터페이스, 필드, 정책 키)를 지금 만들어 두는 것이 이 절의 핵심이다.** 나중에
구현체 하나를 더하고 설정을 켜면 되도록 한다.

### 7.4 그때 해야 할 개발

1. `DeviceIdBindingProvider` 구현 — `setAttestationIds`로 기기 식별자를 attestation에 포함
2. 서버 `attestation` 패키지에 기기 식별자 필드 파싱 추가
3. `policy`에 `require-device-binding` 판단 추가
4. 재설치 시나리오 처리 — 같은 `device_binding`으로 새 키가 오면 **기존 신원을 폐기하고 새로
   발급할지, 기존 신원에 새 키를 묶을지** 결정해야 한다. 이건 정책 결정이며 별도 설계가 필요하다
5. 프라이버시 검토 — 영구 식별자이므로 수집 근거와 보존 기간이 필요하다. 공식 앱이라고 자동으로
   정당화되지 않는다

### 7.5 그때 해야 할 테스트

- device owner로 프로비저닝한 기기에서 `setAttestationIds`가 실제로 통과하는지
- 재설치 후 같은 `device_binding`이 나오는지 (이게 성립해야 4번 정책이 의미를 갖는다)
- 공장 초기화 후에는 어떻게 되는지
- `require-device-binding: true`에서 소매 기기가 거절되는지 (지금 기기로 확인 가능)
- Knox 경로를 쓸 경우 라이선스 없는 빌드에서 우아하게 실패하는지

## 8. 실패 처리

거절에는 **반드시 사유 코드를 붙인다.** "거절됨"만으로는 아무것도 배우지 못한다.

| 상황 | 응답 | 클라이언트 동작 |
|---|---|---|
| challenge 만료/재사용 | 400 `CHALLENGE_INVALID` | ①부터 재시도 |
| 체인 서명 검증 실패 | 403 `CHAIN_UNTRUSTED` | 재시도 무의미 |
| 루트가 구글 루트가 아님 | 403 `CHAIN_UNTRUSTED` | 재시도 무의미 |
| 보안 수준 미달 | 403 `POLICY_SECURITY_LEVEL` | 재시도 무의미 |
| 부팅 검증 실패 | 403 `POLICY_VERIFIED_BOOT` | 재시도 무의미 |
| 패키지/서명키 불일치 | 403 `POLICY_APPLICATION` | 재시도 무의미 |
| 기기 증명 없음 (정책이 요구할 때) | 403 `POLICY_DEVICE_BINDING` | 재시도 무의미 |
| 자격증명 만료 | 401 `CREDENTIAL_EXPIRED` | 기존 키로 재등록 (①부터) |
| DPoP proof 불량 | 401 `DPOP_INVALID` | proof 재생성 |
| 키 소실 (재설치) | — | 새로 등록 |

클라이언트는 재시도가 무의미한 사유에 대해 **반복 시도하지 않는다.** 시스템 메시지로 사유를
보여주고 멈춘다.

## 9. 테스트 전략

### 9.1 TDD로 갈 것 (서버, 순수 JVM)

여기가 대부분이고, 이 사이클에서 자동 테스트가 실제로 의미를 갖는 영역이다.

1. **attestation 확장 파싱** — A36에서 실제 체인을 한 번 뽑아 픽스처로 고정한다. 기기 없이도
   진짜 데이터로 테스트된다.
2. **변조 픽스처가 거절되는가** — 다른 패키지명, 틀린 challenge, 낮춘 보안 수준, 깨진 서명,
   만료된 인증서. **음성 테스트가 양성 테스트보다 중요하다.**
3. **challenge 1회용·만료**
4. **정책 판단** — 각 설정 조합에서 기대한 사유 코드가 나오는가
5. **DPoP proof 검증** — 재생 공격(`jti` 중복), `htm`/`htu` 불일치, 시계 오차 경계,
   `cnf.jkt` 불일치

### 9.2 기기가 필요한 것

안드로이드 계측 테스트에서 키 생성 → 등록 → whoami. 맥에서 도는 서버로는
`adb reverse tcp:8080 tcp:8080`으로 닿는다.

### 9.3 진짜 실험

정책을 `STRONGBOX`로 올리고 A36을 등록시켜 **거절되는 것을 확인**한 뒤,
`TRUSTED_ENVIRONMENT`로 내려 통과시킨다. 같은 기기, 같은 코드, 정책만 바꿔 갈리는 것을 보는
것이 이 사이클에서 가장 배울 것이 많은 지점이다.

`require-device-binding: true`도 같은 방식으로 거절을 관찰할 수 있다.

## 10. 범위 밖

- 사용자 위임 (`act` 클레임, OBO) — ②번
- 행동별 인가, step-up 인증 — ③번
- 신원 폐기·순환 정책 — 저장 모델에 `status`만 두고 운영은 다루지 않는다
- 자격증명 갱신 엔드포인트 — 만료 시 재등록으로 대체한다 (4.2 ⑤ 참조)
- Device ID / Knox attestation **구현** — 7장의 이음매까지만
- AppFunctions 노출 — 2.1절의 이유로 제외
- 실제 삼성 계정 연동 — `server/`가 그 역할을 흉내 낼 뿐이다
- 프로덕션 배포, TLS 인증서 관리, 비밀 관리

## 11. 완료 기준

1. `server/`가 빌드되고 단위 테스트가 통과한다
2. 앱을 처음 실행하면 **대화 없이** 등록이 일어나고, 화면에 발급된 `spiffe://...` 신원이 보인다
3. 그 자격증명으로 `GET /agent/whoami`가 DPoP 검증을 통과하고 같은 agentId를 돌려준다
4. 정책을 `require-security-level: STRONGBOX`로 올리면 A36의 등록이
   `POLICY_SECURITY_LEVEL`로 거절된다
5. 정책을 `require-device-binding: true`로 올리면 `POLICY_DEVICE_BINDING`으로 거절된다
6. 변조 픽스처(패키지명·challenge·보안수준)가 모두 거절된다
7. DPoP proof 재생 공격이 거절된다

## 12. 리스크

| 리스크 | 대응 |
|---|---|
| **attestation 체인 파싱이 이 사이클의 대부분을 먹는다.** 구글 확장의 ASN.1 구조가 KeyMint 버전마다 다르다 | 실기기 체인을 먼저 뽑아 구조를 확인하고 시작한다. 파싱을 HTTP와 분리해 픽스처로 반복 검증한다 |
| 구글 attestation 루트 인증서 확보와 폐기 목록 확인 | 루트는 공개돼 있다. 폐기 목록 확인은 이번 범위에서 제외하고 사유를 기록한다 |
| 안드로이드에서 맥의 서버로 닿는 경로 | `adb reverse`. 실기기 검증에서 확인한다 |
| A36이 TEE 전용이라 StrongBox 수용 경로를 실제로 통과시켜 볼 수 없다 | 거절 쪽만 관찰한다. 수용 경로는 픽스처로 테스트한다 |
| 서버가 신규 프로젝트라 Spring 스캐폴딩 자체가 한 덩어리 | 안드로이드 때처럼 빈 앱 빌드를 먼저 통과시킨 뒤 코드를 얹는다 |
