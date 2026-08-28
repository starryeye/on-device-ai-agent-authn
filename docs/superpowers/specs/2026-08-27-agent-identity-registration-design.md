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

### 2.4 Attestation 신뢰 앵커 — 실측

체인 검증을 "구글 루트 하나를 믿는다"로 짜면 실기기에서 깨진다. 확인한 사실이다.

**루트는 하나가 아니고 움직인다.** `https://android.googleapis.com/attestation/root`에서 받은
현재 목록은 둘이다.

| | subject | 유효기간 |
|---|---|---|
| 레거시 | `serialNumber=f92009e853b6b045` | 2022-03 ~ 2042-03 |
| 신규 | `CN=Key Attestation CA1, OU=Android, O=Google LLC, C=US` | 2025-07 ~ 2035-07 |

신규 루트는 2026-02-01부터 체인 서명을 시작했다. 즉 **지금 이미 유효**하다.

**Android 16 기기는 RKP 전용이다.** 원격 키 프로비저닝이 공장 키를 대체했고, 우리 대상 기기
(A36, Android 16)가 여기 해당한다. 실제로 `IRemotelyProvisionedComponent`가 서비스 목록에
있다. 이것이 유효기간 처리를 가른다.

| 키 종류 | 유효기간 |
|---|---|
| RKP 인증서 | **반드시 검사한다.** 짧은 만료가 위협 모델의 일부이며, 침해 대응 속도를 위한 장치다 |
| 레거시 공장 키 | 만료돼도 신뢰한다. 2021년 이전 기기는 인증서가 이미 만료돼 있다 |

**폐기 목록 확인은 선택이 아니다.** `https://android.googleapis.com/attestation/status`에
공개되며, 확인 시점에 **1,742건이 등재**돼 있었다. 폐기된 attestation 키로 만든 체인은
하드웨어 보증이 무효라는 뜻이므로, 신원을 발급하는 시스템에서 이를 건너뛰면 설계의 근거가
사라진다. `Cache-Control`을 따르면 매 요청마다 네트워크를 타지 않아도 된다.

**검증기는 직접 짜지 않는 쪽을 먼저 검토한다.** 구글이 공식 Kotlin 검증 라이브러리
([github.com/android/keyattestation](https://github.com/android/keyattestation))를 제공하며
자체 검증기 대신 쓰기를 권한다 — *"covers edge cases that are often missed by custom
verifiers"*. 다만 **Maven Central에서 찾지 못했다.** 소스에서 빌드하거나 벤더링해야 한다.
그 비용과 자체 파서의 위험을 구현 첫 단계에서 비교해 정한다(§5).

### 2.4.1 실측한 체인 하나 (표본이지 규격이 아니다)

`AttestationProbeTest`로 A36에서 체인을 뽑아 호스트에서 검증했다. **이 값들은 설계가 성립하는지
확인하는 표본이며, 이 기기에 맞추라는 뜻이 아니다.**

검증 결과: `openssl verify -CAfile <구글 공개 루트> -untrusted <중간> leaf` → **OK**.
체인 5장의 시리얼 모두 CRL에 없음.

```
[0] CN=Android Keystore Key          issuer: CN=<hex>, O=TEE     확장 …2.1.17
[1] CN=<hex>, O=TEE                  issuer: CN=Droid CA3        2026-08-21 ~ 09-03  ← 13일
[2] CN=Droid CA3, O=Google LLC       issuer: CN=Droid CA2        2026-07-30 ~ 10-08
[3] CN=Droid CA2, O=Google LLC       issuer: Key Attestation CA1 2026-02-10 ~ 2029-02-09
[4] CN=Key Attestation CA1 (자기서명, 공개 루트 목록과 바이트 일치)  2025-07 ~ 2035-07
```

확장(`1.3.6.1.4.1.11129.2.1.17`) 내부에서 **리스트가 둘로 갈리는 것을 실물로 확인했다.**

```
attestationVersion=300, securityLevel=1(TrustedEnvironment)
attestationChallenge = 우리가 넣은 32바이트 그대로  ← 하드웨어 서명 안에 박힌다

softwareEnforced : [701] 생성시각, [709] attestationApplicationId
                             └ "dev.starryeye.ondeviceagent" + 서명 다이제스트
teeEnforced      : [1] purpose, [2] algorithm, [3] keySize, [5] digest,
                   [10] ecCurve, [702] origin=0(GENERATED), [704] rootOfTrust
```

**§4.2④의 하드웨어/소프트웨어 구분이 실물로 확인됐다.** 앱 신원은 소프트웨어 목록에 있고,
`origin`과 `rootOfTrust`는 하드웨어 목록에 있다.

**절대 하드코딩하지 말 것.** 아래는 이 기기의 값이지 규격이 아니다.

| 값 | 이 표본 | 다른 기기에서 |
|---|---|---|
| 체인 길이 | 5 | 다르다. 레거시 공장 키 기기는 보통 더 짧다 |
| `attestationVersion` | 300 | 1·2·3·4·100·200·300… 파서가 버전을 분기해야 한다 |
| `securityLevel` | 1 (TEE) | StrongBox(2)도, Software(0)도 온다 |
| 중간 CN | `Droid CA2`/`CA3`, `O=TEE` | 구글·OEM 구현 세부이며 바뀔 수 있다 |
| 루트 | `Key Attestation CA1` | 레거시 루트로 오는 기기가 여전히 있다 |

검증은 **구조와 공개 목록**에 기대야 하고, 관찰된 문자열에 기대면 안 된다.

### 2.5 표준 지형

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
| SPIFFE / WIMSE 식별자 | CNCF / draft | **쓰지 않음** (사유는 아래) |

**SPIFFE도 WIMSE도 식별자로 쓰지 않는다.** 둘 다 검토했고, 각각 다른 이유로 물렸다.

`spiffe://`는 쓰면 안 된다. 그 스킴은 SPIFFE의 신뢰 모델(trust bundle, SVID, Workload API)을
함의하는데 우리는 그중 아무것도 구현하지 않는다. 식별자만 빌리고 "SPIFFE를 쓴다"고 말하는 것은
오용이다.

`wimse://`는 오용은 아니다 — [식별자 draft](https://datatracker.ietf.org/doc/draft-ietf-wimse-identifier/)가
`wimse://` 스킴을 따로 정의하고 독립 사용을 명시적으로 전제한다(*"does not prescribe how
identifiers are issued or verified"*). 그러나 **WIMSE가 상정하는 대상이 우리가 아니다.** WIMSE는
워크로드를 "마이크로서비스, 컨테이너, VM, 서버리스 함수"로 정의하고 운영자가 노드를 통제하는
환경을 전제한다. 우리 에이전트는 사용자 소유 폰의 앱 인스턴스이고 사용자가 공격자일 수 있다.
성격상 워크로드보다 **클라이언트 측 주체**에 가깝다.

그리고 `wimse://`를 택할 유일한 실질적 우위는 "표준이 그쪽으로 갈 테니 미리 맞춰두면 이득"
이었는데, 이는 검증할 수 없는 베팅이다. draft 의존까지 지면서 살 만한 값이 아니다.

**대신 자체 식별자를 쓴다.**

```
urn:samsung:agent:<product>:<instance-uuid>
예) urn:samsung:agent:galaxy-personal-agent:3f2a8b11-4c7d-...
```

`RFC 8141` URN **문법**을 따른다. 다만 **NID `samsung`은 IANA에 등록되지 않은 사설
네임스페이스**다. 등록된 URN을 쓰는 것이 아니라 URN 문법으로 우리 이름을 짓는 것이며, 이
문서는 그 이상을 주장하지 않는다.

구조화된 식별자를 쓰는 이유는 하나로 좁혀진다 — **②번의 `act.sub`**. 위임 토큰에서 행위자를
가리키는 그 자리에 불투명 UUID가 놓이면 리소스 서버가 그것이 무엇인지, 어느 발급자의 것인지
알 수 없다. 네임스페이스와 종류가 문자열에 들어 있으면 그 자리에서 실제로 일을 한다.

형식과 무관하게 지킬 규칙 둘은 남긴다.

1. 네임스페이스 안에서 **유일**해야 한다
2. 인가 판단에서 **전체 문자열을 비교**한다. 접두어 비교는 `...:agent:x`가 `...:agent:xyz`를
   통과시키는 우회가 되므로 금지한다

식별자 조립은 **한 클래스에 가둔다.** 나중에 표준 형식으로 옮길 이유가 생기면 교체가 한 줄이
되게 한다.

SPIFFE에서는 **단명 자격증명 자동 갱신** 원칙만 개념으로 가져온다.

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

**규칙 하나로 요약된다: 클라이언트의 주장은 받지 않고, 서명된 증거만 받는다.** 앱이 요청
본문에 적어 보내는 "나는 정품이다" 류의 값은 서버가 읽지 않는다. 보안 수준도, 패키지명도,
부팅 상태도 전부 체인에서 읽는다.

다만 **체인 안에서도 층이 갈린다.** 보안 수준과 부팅 상태는 TEE가 강제하지만, 앱 신원
(`attestationApplicationId`)은 **안드로이드 플랫폼이 채워 넣는 소프트웨어 강제 값**이다.
장악된 시스템에서는 하드웨어 강제 필드가 멀쩡한 채로 이 값만 위조될 수 있다. 이 구분과
그로부터 나오는 논리 사슬은 §4.2④에서 다룬다.

신뢰의 뿌리가 우리 인프라가 아니라는 점이 SPIFFE와 갈리는 지점이다. 우리는 발급자가 아니라
**검증자**다.

### 3.3 Key Attestation은 Agent Attestation이 아니다

혼동하기 쉬운 지점이라 못박는다. Android가 증명해 주는 것은 이것이다.

> 이 키는 특정 Android 보안 환경에서 생성되었고, 이런 특성(보안 수준, 생성 주체 앱, 부팅
> 상태)을 갖는다.

Android는 **"이것이 에이전트 A789다"라고 증명하지 않는다.** 그런 개념 자체가 없다. Android가
제공하는 것은 앱/패키지 신원, 서명 인증서, UID, Keystore, TEE/StrongBox, Key Attestation이며,
`agent_id`나 `agent credential` 같은 원시 요소는 제공되지 않는다.

따라서 역할은 이렇게 갈린다.

```
Android          → 키와 실행 환경에 대한 신뢰 증거(evidence)를 준다
발급 서버(우리)  → 그 증거를 검증하고, 에이전트 신원을 만들어 키에 묶는다
```

**신원을 만드는 주체는 서버다.** Android는 증거만 준다. 이 구분이 흐려지면 "안드로이드가
에이전트를 인증해 준다"는 잘못된 기대가 생긴다.

### 3.4 위협 모델 — 막는 것과 수용하는 것

**막는다**

- 토큰 탈취 후 다른 기기·프로세스에서 사용 → `cnf.jkt` + PoP. 키 없이는 무용
- 오래된 attestation 체인 재사용 → 1회용 challenge가 체인에 박힌다
- 다른 앱이 우리 에이전트를 사칭 → `attestationApplicationId`, 단 아래 사슬을 전제로
- 소프트웨어 키로 위조 → `origin=GENERATED` + 보안 수준(하드웨어 강제)
- 루팅·언락 기기 → `verifiedBootState`/`deviceLocked` (하드웨어 강제)
- 유출된 attestation 키 → CRL 확인 (§2.4)

**수용한다 (막지 못한다)**

- **TEE 자체가 침해된 경우.** 하드웨어 강제 필드도 신뢰할 수 없게 된다. 이 설계의 신뢰
  근거가 통째로 무너지며, 대응 수단은 CRL 등재를 기다리는 것뿐이다
- **재설치를 통한 폐기 우회.** 폐기된 에이전트가 앱을 지우고 다시 깔면 새 키·새 신원을 받는다
  (§5.2). 기기 증명 없이는 막을 수 없다(§7)
- **등록 이후의 기기 상태 악화** — 완화하되 없애지 못한다(§4.3의 재-attestation 상한)

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
→ 200 {
  "registrationId": "<등록 거래 식별자>",
  "challenge": "<32바이트 base64url>",
  "expiresIn": 300
}
```

서버가 저장하고 **1회용**으로 소비한다. 오래된 attestation 체인의 재사용을 막는다.

**`registrationId`와 `agentId`는 다른 것이다.** 전자는 이 등록 시도 하나를 가리키는 **거래
식별자**로, 실패해도 남고 로그·디버깅에 쓰인다. 후자는 등록이 성공해야 비로소 생기는
**신원**이다. 둘을 섞으면 실패한 시도가 신원처럼 보이거나, 재시도마다 신원이 늘어난다.

**② 기기가 키 생성**

Android Keystore에 EC P-256 키쌍을 만들며 그 challenge를 attestation challenge로 넣는다.
StrongBox를 먼저 시도하고 `StrongBoxUnavailableException`이면 TEE로 내려간다. 개인키는
어떤 경로로도 앱에 노출되지 않는다.

**③ 기기가 등록 요청**

```
POST /agent/registration
{
  "registrationId": "<①에서 받은 값>",
  "attestationChain": ["<leaf DER base64>", ..., "<root>"],
  "pop": "<DPoP 형태 JWS, challenge 포함, 그 키로 서명>",
  "deviceBinding": null,
  "playIntegrityToken": null
}
```

체인이 이미 challenge를 품고 있지만 소유 증명(PoP)을 따로 붙인다. 키가 지금 살아 있음을
보이기 위해서다.

**자체 규격을 만들지 않는다.** PoP는 `RFC 9449` DPoP proof와 **같은 형태**로 만든다 — 같은
JWS 구조, 같은 `htm`/`htu`/`iat`/`jti` 클레임에 challenge를 담는 클레임 하나를 더한다.
파싱·서명검증 계층을 그대로 재사용할 수 있다.

**단, `typ`은 반드시 다르게 한다.** RFC 9449는 DPoP proof의 `typ`을 `dpop+jwt`로 **강제**한다
(§4.3). 등록 PoP가 같은 `typ`을 쓰면 교차 프로토콜 혼동이 열린다 — 한쪽에서 얻은 proof를
다른 쪽에 재생할 여지가 생기고, 등록 PoP는 신원이 없는 상태에서, 런타임 DPoP는 신원이 확정된
뒤에 오므로 서로 통과시키면 안 된다. 등록 PoP는 `typ: agent-reg-pop+jwt`를 쓰고, 검증기는
용도별로 `typ`을 강제한다. **재사용은 파싱까지이고 수용 조건은 분리한다.**

**④ 서버가 검증**

검증은 **체인 자체**와 **확장 안의 값** 두 층으로 나뉜다.

**층 1 — 체인** (§2.4의 실측에 근거한다)

1. leaf→root 서명 검증
2. 루트가 `https://android.googleapis.com/attestation/root`의 **루트 집합**에 속하는가.
   현재 둘이며 목록은 갱신된다. 하드코딩하지 않고 캐시한다
3. **RKP 인증서는 유효기간을 검사한다.** 레거시 공장 키(레거시 루트로 체인)는 만료돼도 통과시킨다
4. 체인의 **모든 인증서를 CRL로 확인**한다
   (`https://android.googleapis.com/attestation/status`, `Cache-Control` 준수)

**층 2 — 확장** (OID `1.3.6.1.4.1.11129.2.1.17`)

여기서 **하드웨어 강제와 소프트웨어 강제를 구분해야 한다.** 전자는 TEE/StrongBox가, 후자는
안드로이드 플랫폼 코드가 채운다.

| 읽는 것 | 강제 주체 | 판단 | 정책 |
|---|---|---|---|
| `attestationChallenge` | **하드웨어** | 우리가 준 것, 미사용, 미만료 | 고정 |
| `attestationSecurityLevel` | **하드웨어** | TEE / StrongBox | **설정** |
| `origin` | **하드웨어** | `GENERATED` | 고정 |
| `purpose` | **하드웨어** | SIGN 포함 | 고정 |
| `rootOfTrust.verifiedBootState` | **하드웨어** | `Verified` | **설정** |
| `rootOfTrust.deviceLocked` | **하드웨어** | `true` | **설정** |
| `attestationApplicationId` | *소프트웨어* | 우리 패키지명·서명키 다이제스트 | **설정** |

**앱 신원 검사는 부팅 검증에 의존한다.** `attestationApplicationId`는 장악된 시스템에서 위조될
수 있으므로 그 자체로는 사칭을 막지 못한다. 막아 주는 것은 앞의 논리 사슬이다 — 부팅이 검증되고
부트로더가 잠긴 기기에서만 플랫폼 코드가 정상 동작한다고 볼 수 있고, **그 조건 아래에서만**
소프트웨어 강제 값을 신뢰한다.

따라서 `require-verified-boot: false`로 내리면 **앱 신원 검사도 함께 무의미해진다.** 설정을
낮출 때 무엇이 같이 무너지는지 알고 낮춰야 한다.

**⑤ 서버가 신원 발급**

```
→ 200 {
  "agentId": "urn:samsung:agent:<product>:<uuid>",
  "credential": "<서버 서명 JWT>",
  "expiresIn": 900
}
```

JWT는 `iss`(발급 서버), `aud`(대상 리소스 서버), `sub`(agentId), `cnf.jkt`(에이전트 공개키
지문, RFC 7638), `exp`를 담는다. 즉 이 토큰은 **그 키를 가진 자만**, **그 대상에게만** 쓸 수
있다.

`aud`를 지금 넣는 이유는 ①번에 리소스 서버가 하나뿐이어서가 아니다. 둘 이상이 되는 순간 A용
자격증명을 B에 제시하는 혼동이 생기고, 그때 모양을 바꾸면 이미 발급된 토큰과 호환이 깨진다.
②번에서 `RFC 8707` 대상 지시자로 확장할 자리이기도 하다.

**신원과 자격증명의 수명은 다르다.**

| | 수명 | 무엇에 묶이는가 |
|---|---|---|
| agentId (신원) | 키가 사는 동안 | 하드웨어 키 |
| credential (자격증명) | 15분 | 그 신원 + 그 키 |

**등록은 키 지문에 대해 멱등이다.** 서버는 `jwk_thumbprint`로 기존 신원을 먼저 찾는다.
있으면 **같은 agentId를 그대로 돌려주고** 자격증명만 새로 발급한다. 없을 때만 새 agentId를
만든다. 그래서 재등록해도 신원은 바뀌지 않는다.

새 agentId가 생기는 경우는 하나뿐이다 — **키가 바뀔 때**(앱 재설치 등). 새 설치본은 새 에이전트
인스턴스이므로 의도된 동작이다.

### 4.3 자격증명 갱신

만료마다 attestation을 다시 하는 것은 낭비이고, 신원을 흔들 위험도 있다. 갱신은 **하드웨어 키
자체를 자격증명으로 쓴다.**

```
POST /agent/credential
DPoP: <RFC 9449 proof, 에이전트 키로 서명>
→ 200 { "agentId": "...", "credential": "...", "expiresIn": 900 }
```

서버는 proof를 검증하고 그 키 지문으로 신원을 찾는다. `ACTIVE`면 자격증명을 새로 발급한다.

**갱신에는 상한이 있다.** 등록 시점에 부팅이 검증된 기기였더라도 이후 언락·루팅될 수 있는데,
attestation은 등록 때 한 번뿐이므로 서버는 그 변화를 영원히 모른다. 갱신만으로 무기한 연장되는
것을 막기 위해 마지막 attestation 이후 경과 시간에 상한을 둔다.

```yaml
  max-attestation-age: 7d   # 초과하면 갱신을 거부하고 재등록을 요구한다
```

초과 시 `401 REATTESTATION_REQUIRED`를 돌려주고, 클라이언트는 ①부터 다시 밟는다. 키는 그대로
재사용하므로 **신원은 유지된다**(등록 멱등성).
**체인 검증도 challenge도 필요 없다** — 등록 시점에 이미 검증했고, 그 키를 지금 쥐고 있다는
사실이 proof로 증명되기 때문이다.

이 구조에서 **오래가는 자격증명은 하드웨어 키이고, JWT는 그것에 묶인 단명 토큰**이다. DPoP
검증은 `whoami`에도 어차피 필요하므로 추가 비용이 거의 없다.

### 4.4 자격증명 사용

```
GET /agent/whoami
Authorization: DPoP <credential>
DPoP: <RFC 9449 proof JWS>
```

서버는 proof의 `htm`/`htu`/`iat`/`jti`를 검증하고, proof를 서명한 키의 지문이 토큰의
`cnf.jkt`와 일치하는지 확인한다. 일치하면 agentId를 돌려준다.

## 5. 서버 구조

Spring Boot 4.1.1 / **Java 21**. 저장소 루트의 `server/`에 독립 Gradle 빌드로 둔다.

Java 17이 아니라 21인 이유는 공식 검증 라이브러리가 Java 21 툴체인을 요구하기 때문이다.
**언어는 Kotlin으로 쓴다**(사용자 결정, 2026-08-28). 안드로이드가 이미 Kotlin이라 저장소가 한
언어로 통일되고, 검증 라이브러리도 Kotlin이라 nullable·sealed·`fun interface` 를 그대로 받는다.

| 패키지 | 책임 | 무엇을 모르는가 |
|---|---|---|
| `attestation` | 체인 검증, 확장 파싱 | HTTP도 JWT도 모름 |
| `policy` | 수용/거절 판단 + 사유 | 어디서 불리는지 모름 |
| `registration` | challenge 발급, 등록 오케스트레이션 | 검증 방법을 모름 |
| `identity` | 신원 저장, 자격증명 발급, **식별자 조립(한 클래스)** | attestation을 모름 |
| `dpop` | RFC 9449 proof 검증 | 도메인을 모름 |
| `api` | 엔드포인트 | 위를 조립만 |

가장 두꺼운 곳은 `attestation`이다. **다만 직접 짜는 것을 기본값으로 두지 않는다.**

구글이 공식 Kotlin 검증 라이브러리를 제공하며 자체 검증기 대신 쓰기를 권한다(§2.4). 체인 검증,
루트 집합, RKP 유효기간, CRL, 확장 파싱은 모두 놓치기 쉬운 구석이 있는 영역이고, 우리가
새로 짜서 더 잘할 이유가 없다. 문제는 **Maven Central에 없다**는 것이다.

구현 첫 단계에서 이 순서로 정한다.

1. 공식 라이브러리를 소스에서 빌드하거나 벤더링해 쓸 수 있는가 → 가능하면 그것을 쓴다
2. 불가하면 BouncyCastle로 최소한만 직접 파싱한다. 이 경우 **§2.4의 항목(루트 집합, RKP
   유효기간, CRL, 하드웨어/소프트웨어 구분)을 하나도 빠뜨리지 않았음을 테스트로 증명**해야 한다

어느 쪽이든 **이 패키지가 HTTP를 모르게 두는 것이 중요하다** — 그래야 실기기에서 뽑은 체인을
픽스처로 단위 테스트할 수 있다. 다만 CRL과 루트 목록은 네트워크를 타므로, 그 둘은 조회기를
인터페이스 뒤로 두고 테스트에서는 고정 응답을 넣는다.

### 5.1 저장 모델

H2 파일 기반. 연구용 저장소에서 외부 DB를 세우게 하면 재현 비용만 오른다.

```
agent_identity
  id                    URN 문자열 (PK)
  agent_product_id      어떤 종류의 에이전트인가 (예: galaxy-personal-agent)
  jwk_thumbprint        unique  ← 신원의 실질적 키. 등록은 이 값에 대해 멱등
  public_key
  package_name
  signing_digest
  security_level        TRUSTED_ENVIRONMENT | STRONGBOX
  verified_boot         Verified | SelfSigned | Unverified | Failed
  device_locked         boolean
  integrity_verdict     nullable  ← Play Integrity 결과. 보조 증거
  device_binding        nullable  ← 7장. 이번엔 항상 null
  subject               nullable  ← ②번에서 사용자가 들어올 자리
  created_at
  last_authenticated_at ← 마지막 PoP 성공 시각. 휴면 에이전트 식별에 쓴다
  status                (아래 라이프사이클 참조)

challenge
  registration_id (PK) | value | issued_at | expires_at | consumed_at
```

`device_binding`과 `subject`는 **지금 비워두되 자리를 만든다.** 나중에 스키마를 뜯지 않기
위해서다.

**`device_binding`의 역할을 못박는다.** 이것은 **인증 자격이 아니다.** 에이전트 인증은
`agentId + 키 소유 증명(PoP)`만으로 성립한다. `device_binding`은 두 가지 용도다.

- **정책 입력** — "기기 증명이 없으면 거절" 같은 판단의 재료 (`require-device-binding`)
- **관계 맥락** — "이 에이전트가 어느 기기에 등록됐는가". 폐기 전파나 기기 단위 정책의 근거

즉 Device ID가 있다고 키가 덜 중요해지지 않고, 키가 있다고 Device ID가 불필요해지지도 않는다.
답하는 질문이 다르다 — 키는 *이 에이전트가 등록 당시의 키를 쥐고 있는가*, 기기 증명은
*어느 갤럭시인가*.

### 5.2 신원의 수명과 연속성

`status`는 이번 사이클에서 **둘만** 쓴다.

```
ACTIVE → REVOKED
```

등록이 성공하면 곧바로 쓸 수 있으므로 중간 상태가 없다. `PROVISIONED`/`SUSPENDED` 같은 상태는
그것을 만드는 전이(관리자 정지, 단계적 활성화)가 생길 때 함께 정의한다. **전이가 없는 상태를
미리 만들면 구현자가 임의로 해석한다.**

**연속성의 기준은 하나다 — 신원은 키다.** 키가 살아 있으면 같은 에이전트이고, 키가 사라지면
다른 에이전트다. 여기서 아래 표가 전부 도출된다.

| 사건 | 키 | 신원 | 근거 |
|---|---|---|---|
| 앱 프로세스 재시작 | 유지 | **같음** | Keystore는 프로세스와 무관 |
| 기기 재부팅 | 유지 | **같음** | 키는 TEE에 영속 |
| 앱 업데이트 | 유지 | **같음** | 서명키가 같으면 Keystore 접근 유지 |
| 앱 재설치 | **소실** | **새 신원** | 앱 삭제 시 Keystore 항목도 삭제 |
| 공장 초기화 | **소실** | **새 신원** | TEE 초기화 |
| 키 회전(의도적) | 교체 | **새 신원** | 이번 범위 밖. ②번에서 회전 정책과 함께 |

**앱 업데이트로 신원이 바뀌지 않는 것**이 중요하다. 버전이 올랐다고 새 에이전트가 되면 감사
기록이 끊기고 폐기가 무의미해진다.

재설치·초기화로 새 신원이 되는 것은 **의도된 동작이되 약점이기도 하다** — 폐기된 에이전트가
재설치 한 번으로 깨끗한 신원을 받는다. 이 구멍은 기기 증명 없이는 막을 수 없다(7장).

### 5.3 Play Integrity — 보조 증거

Key Attestation이 답하지 못하는 질문이 있다. *앱이 변조되지 않았는가, 구글 플레이가 인정하는
기기인가, 실행 환경이 정상인가.*

**Play Integrity를 신원으로 쓰지 않는다.** 에이전트 신원은 어디까지나 키에 묶이고, Play
Integrity는 **정책 판단의 보조 재료**다. 등록 요청에 `playIntegrityToken` 자리를 두고,
서버는 검증 결과를 `integrity_verdict`에 기록한다.

이번 사이클에서는 **자리와 기록까지만** 한다. 판정을 거절 사유로 쓸지는 정책 손잡이로 두되
기본값은 끈다. 구글 API 연동이 이 사이클의 무게중심을 흔들면 안 되기 때문이다.

삼성 1st-party 환경이라면 Play Integrity 대신(또는 함께) 삼성 자체 기기·앱 신뢰 증거를 쓸 수
있다. 7장에 함께 적는다.

### 5.4 정책 설정

```yaml
agent-registration:
  require-security-level: TRUSTED_ENVIRONMENT   # STRONGBOX 로 올리면 A36은 거절된다
  require-verified-boot: true
  require-device-locked: true
  allowed-packages: [dev.starryeye.ondeviceagent]
  allowed-signing-digests: []                   # 비면 검사하지 않음(개발용)
  require-device-binding: false                 # 7장. 1st-party 배포에서 true 로 올린다
  require-play-integrity: false                 # 5.3. 이번 사이클 기본 off
  agent-product-id: galaxy-personal-agent
  identifier-namespace: samsung                 # URN NID. 사설(미등록) 네임스페이스
  challenge-ttl: 5m
  credential-ttl: 15m
  max-attestation-age: 7d                       # 4.3. 초과 시 재등록 요구
  dpop:
    iat-skew: 60s                               # RFC 9449 는 값을 정하지 않는다. 우리가 정한다
    jti-cache-ttl: 120s                         # 허용 창의 두 배
  attestation:
    roots-url: https://android.googleapis.com/attestation/root
    crl-url: https://android.googleapis.com/attestation/status
    require-crl-check: true                     # 2.4. 끄면 하드웨어 보증의 근거가 사라진다
```

## 6. 클라이언트 구조 (Android)

기존 앱에 패키지 하나를 더한다.

| 파일 | 책임 |
|---|---|
| `identity/AgentKeyStore.kt` | 키 생성(StrongBox→TEE 폴백), 체인 추출, 키 존재 확인 |
| `identity/DeviceBindingProvider.kt` | **이음매**. 7장 참조. 지금은 `NoDeviceBinding` |
| `identity/IntegrityTokenProvider.kt` | **이음매**. 5.3 참조. 지금은 `NoIntegrityToken` |
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

**삼성 자체 무결성 증거** — 5.3의 Play Integrity 자리를 삼성 자체 기기·앱 신뢰 증거로 대체하거나
병행할 수 있다. 구글 인프라 의존을 줄이고 OEM이 아는 정보(정품 펌웨어 여부 등)를 쓸 수 있다.
`integrity_verdict` 컬럼과 `require-play-integrity` 손잡이를 **증거 출처와 무관한 이름으로
두었으므로**, 출처가 바뀌어도 스키마와 정책 구조는 그대로다.

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
| 루트가 알려진 구글 루트 집합에 없음 | 403 `CHAIN_UNTRUSTED` | 재시도 무의미 |
| 체인 인증서가 CRL에 등재됨 | 403 `CHAIN_REVOKED` | 재시도 무의미 |
| RKP 인증서 만료 | 403 `CHAIN_EXPIRED` | 키를 새로 만들어 재등록 |
| PoP 의 `typ` 불일치 | 400 `POP_INVALID` | proof 재생성 |
| 보안 수준 미달 | 403 `POLICY_SECURITY_LEVEL` | 재시도 무의미 |
| 부팅 검증 실패 | 403 `POLICY_VERIFIED_BOOT` | 재시도 무의미 |
| 패키지/서명키 불일치 | 403 `POLICY_APPLICATION` | 재시도 무의미 |
| 기기 증명 없음 (정책이 요구할 때) | 403 `POLICY_DEVICE_BINDING` | 재시도 무의미 |
| 무결성 판정 미달 (정책이 요구할 때) | 403 `POLICY_INTEGRITY` | 재시도 무의미 |
| 자격증명 만료 | 401 `CREDENTIAL_EXPIRED` | `POST /agent/credential` 로 갱신 (4.3) |
| attestation 이 너무 오래됨 | 401 `REATTESTATION_REQUIRED` | ①부터 재등록 (신원은 유지) |
| DPoP proof 불량 | 401 `DPOP_INVALID` | proof 재생성 |
| 키 소실 (재설치) | — | 새로 등록 |

클라이언트는 재시도가 무의미한 사유에 대해 **반복 시도하지 않는다.** 시스템 메시지로 사유를
보여주고 멈춘다.

## 9. 테스트 전략

### 9.1 TDD로 갈 것 (서버, 순수 JVM)

여기가 대부분이고, 이 사이클에서 자동 테스트가 실제로 의미를 갖는 영역이다.

1. **attestation 확장 파싱** — 실기기에서 뽑은 체인을 픽스처로 고정한다. 기기 없이도 진짜
   데이터로 테스트된다.

   **다만 픽스처는 만료된다.** 실측한 RKP 중간 인증서의 유효기간이 13일이었다(§2.4.1). 시스템
   시각으로 검증하면 픽스처가 2주도 못 가 저절로 실패하고, 어느 날 갑자기 깨진 빌드의 원인을
   찾느라 시간을 쓰게 된다. **검증기는 "현재 시각"을 주입받아야 하고**, 픽스처 테스트는 체인이
   유효했던 시점을 고정해 넣는다. 만료 거절 테스트는 그 시각을 뒤로 밀어 만든다.
2. **변조 픽스처가 거절되는가** — 다른 패키지명, 틀린 challenge, 낮춘 보안 수준, 깨진 서명,
   만료된 인증서. **음성 테스트가 양성 테스트보다 중요하다.**
3. **challenge 1회용·만료**
4. **정책 판단** — 각 설정 조합에서 기대한 사유 코드가 나오는가
5. **DPoP proof 검증** — 재생 공격(`jti` 중복), `htm`/`htu` 불일치, 시계 오차 경계,
   `cnf.jkt` 불일치
6. **등록 멱등성** — 같은 키로 두 번 등록하면 같은 agentId가 나오는가. 다른 키면 다른 agentId가
   나오는가. 이게 깨지면 신원이 자격증명 수명에 끌려다닌다
7. **체인 검증 층** — 알려지지 않은 루트 거절, CRL 등재 인증서 거절, RKP 만료 거절,
   레거시 만료는 **통과**. 넷 다 픽스처로 고정한다
8. **하드웨어/소프트웨어 강제 구분** — `attestationApplicationId`만 위조한 픽스처가
   `verified_boot=Verified` 정책에서 어떻게 다뤄지는지 명시적으로 테스트한다
9. **`typ` 혼동** — 등록 PoP를 런타임 DPoP 자리에 제시하면 거절되는가, 그 반대도 거절되는가
10. **재-attestation 상한** — `max-attestation-age` 초과 시 갱신이 거부되고 재등록 후 **같은
    agentId**가 유지되는가
11. **식별자 규칙** — `urn:samsung:agent:<product>:<uuid>` 형식으로 조립되는가, 네임스페이스
   안에서 유일한가. 그리고 **인가 비교가 전체 문자열 일치인가** — 접두어로 비교하면
   `...:agent:x`가 `...:agent:xyz`를 통과시키는 우회가 생긴다. 이 음성 테스트를 넣는다

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
- 신원 폐기·순환 정책(운영) — `status` 컬럼과 전이만 두고 폐기 조작 API·순환 주기는 다루지 않는다.
  **단 attestation 체인의 CRL 확인은 범위 안이다**(§2.4)
- Device ID / Knox attestation **구현** — 7장의 이음매까지만
- AppFunctions 노출 — 2.1절의 이유로 제외
- 실제 삼성 계정 연동 — `server/`가 그 역할을 흉내 낼 뿐이다
- 프로덕션 배포, TLS 인증서 관리, 비밀 관리
- **관계 기반 신원 모델** — 지금은 `agent_identity` 한 테이블에 `subject`·`device_binding`을
  컬럼으로 붙인 평면 구조다. User·Device·Agent를 독립 엔티티로 두고 관계를 별도 테이블로 빼는
  것은 ②번에서 위임이 들어올 때 필요해진다. 그때 마이그레이션한다
- **기기 폐기 → 에이전트 폐기 전파** — 기기 증명이 없으면 성립하지 않는다(7장). ③번
- **한 기기·한 앱에 여러 에이전트** — 지금은 키 하나에 에이전트 하나를 가정한다. 다중
  에이전트는 키 별칭 체계와 `agent_product_id` 조합으로 확장할 수 있으나 이번엔 다루지 않는다
- **외부 에이전트 페더레이션** — 외부 제공자가 서명한 에이전트 주장을 받아 신원을 연합하는
  구조. 우리가 통제하지 않는 에이전트를 다루는 별개 주제다

## 11. 완료 기준

1. `server/`가 빌드되고 단위 테스트가 통과한다
2. 앱을 처음 실행하면 **대화 없이** 등록이 일어나고, 화면에 발급된 `urn:samsung:agent:...` 신원이 보인다
3. 그 자격증명으로 `GET /agent/whoami`가 DPoP 검증을 통과하고 같은 agentId를 돌려준다
4. 앱을 재시작해도 **같은 agentId**가 유지된다. 재등록해도 신원은 바뀌지 않는다
5. `POST /agent/credential`이 attestation 없이 새 자격증명을 발급한다
6. 정책을 `require-security-level: STRONGBOX`로 올리면 A36의 등록이
   `POLICY_SECURITY_LEVEL`로 거절된다
7. 정책을 `require-device-binding: true`로 올리면 `POLICY_DEVICE_BINDING`으로 거절된다
8. 변조 픽스처(패키지명·challenge·보안수준)가 모두 거절된다
9. DPoP proof 재생 공격이 거절된다
10. **CRL에 등재된 인증서를 포함한 체인이 거절된다**
11. **등록 PoP와 런타임 DPoP가 서로의 자리에서 거절된다** (`typ` 분리)
12. **`max-attestation-age` 초과 시 갱신이 거부되고, 재등록 후 같은 agentId가 유지된다**

## 12. 리스크

| 리스크 | 대응 |
|---|---|
| **attestation 체인 검증이 이 사이클의 대부분을 먹는다.** 루트 집합·RKP 유효기간·CRL·확장 파싱이 모두 놓치기 쉽다 | 공식 라이브러리 사용을 먼저 검토한다(§5). 직접 짜게 되면 §2.4 항목을 빠뜨리지 않았음을 테스트로 증명한다 |
| 루트 목록과 CRL이 **네트워크 의존**이다. 서버가 이를 못 받으면 등록이 막힌다 | 캐시하고 `Cache-Control`을 따른다. 조회 실패 시 **등록을 거절**한다(열어두면 CRL 우회가 된다). 이 선택을 문서에 남긴다 |
| 공식 검증 라이브러리가 Maven Central 에 없다 | 소스 빌드/벤더링 비용을 구현 첫 단계에서 재고 결정한다 |
| 안드로이드에서 맥의 서버로 닿는 경로 | `adb reverse`. 실기기 검증에서 확인한다 |
| A36이 TEE 전용이라 StrongBox 수용 경로를 실제로 통과시켜 볼 수 없다 | 거절 쪽만 관찰한다. 수용 경로는 픽스처로 테스트한다 |
| 서버가 신규 프로젝트라 Spring 스캐폴딩 자체가 한 덩어리 | 안드로이드 때처럼 빈 앱 빌드를 먼저 통과시킨 뒤 코드를 얹는다 |
| 자체 식별자라 외부 표준과의 상호운용성이 없다 | 지금 연동 대상이 없으므로 비용이 아니다. 생기면 조립 클래스 한 곳만 바꾼다 |
| 재설치로 폐기를 우회할 수 있다 | 이번 사이클에서는 막을 수 없다. 5.2에 한계로 명시하고 7장의 기기 증명으로 넘긴다 |
