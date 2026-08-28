# 에이전트 신원 등록 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 온디바이스 에이전트가 하드웨어에 묶인 신원을 발급받고, 그 키로 서버에 자기를 증명한다.

**Architecture:** 안드로이드 앱이 Keystore에 추출 불가능한 키를 만들고 Key Attestation 체인을 서버에 보낸다. 서버(Spring, Java)는 구글 공식 검증 라이브러리로 체인을 검증하고, 정책을 통과하면 `urn:samsung:agent:...` 신원과 DPoP에 묶인 단명 자격증명을 발급한다. 등록은 키 지문에 대해 멱등이라 재등록해도 신원이 바뀌지 않는다.

**Tech Stack:** Spring Boot 4.1.1 / Java 21 / H2 / Nimbus JOSE+JWT 10.9.1 / `com.android.keyattestation:keyattestation`(공식 검증기, git submodule + 컴포지트 빌드) / 안드로이드는 기존 Kotlin 앱

**Spec:** [docs/superpowers/specs/2026-08-27-agent-identity-registration-design.md](../specs/2026-08-27-agent-identity-registration-design.md)

## Global Constraints

- 서버는 **Java**로 쓴다. Kotlin 아님. (검증 라이브러리는 Kotlin이지만 JVM 라이브러리이므로 Java에서 그대로 쓴다)
- 서버 **Java 21**. 공식 검증 라이브러리가 Java 21 툴체인을 요구한다
- 서버는 `server/`에 **독립 Gradle 빌드**. `android/`와 섞지 않는다
- 식별자: `urn:samsung:agent:<product>:<uuid>`. **조립은 한 클래스에만** 둔다
- 인가 판단은 **전체 문자열 비교**. 접두어 비교 금지
- 등록은 **키 지문(`jwk_thumbprint`)에 대해 멱등**. 같은 키면 같은 agentId
- 등록 PoP `typ`은 `agent-reg-pop+jwt`, 런타임 DPoP `typ`은 `dpop+jwt`. **서로 통과시키지 않는다**
- attestation 검증기는 **현재 시각을 주입**받는다. 시스템 시각을 직접 읽지 않는다
- 거절에는 **반드시 사유 코드**를 붙인다
- 커밋마다 `git push` (사용자 요구사항)

---

## 왜 이 순서인가

가장 큰 미지수를 먼저 없앤다. 서버가 신규 프로젝트이고 공식 검증 라이브러리를 컴포지트 빌드로
붙여야 하는데, 그게 안 되면 뒤 태스크가 전부 막힌다. 그래서 Task 1은 **빈 앱이 뜨고 검증기가
import되는 것**만 증명한다.

그다음은 안쪽에서 바깥쪽으로 간다 — 검증(2) → 정책(3) → 등록(4) → 자격증명(5) → 클라이언트(6)
→ 실기 검증(7).

## File Structure

**서버** (`server/`)

| 파일 | 책임 |
|---|---|
| `settings.gradle.kts` | 컴포지트 빌드로 `keyattestation` 연결 |
| `build.gradle.kts` | Spring Boot, Java 21 |
| `third_party/keyattestation/` | git submodule (수정하지 않는다) |
| `src/main/java/.../ServerApplication.java` | 진입점 |
| `.../attestation/AttestationVerifier.java` | 공식 검증기 래핑. HTTP를 모른다 |
| `.../attestation/TrustAnchorSource.java` | 구글 루트 목록 조회·캐시 |
| `.../attestation/RevocationSource.java` | CRL 조회·캐시 |
| `.../attestation/AttestationResult.java` | 검증 결과 (우리 도메인 타입) |
| `.../policy/RegistrationPolicy.java` | 수용/거절 판단 + 사유 코드 |
| `.../policy/PolicyProperties.java` | 설정 바인딩 |
| `.../policy/RejectionReason.java` | 사유 코드 enum |
| `.../identity/AgentIdentifier.java` | **식별자 조립 전담** |
| `.../identity/AgentIdentity.java` | 엔티티 |
| `.../identity/AgentIdentityRepository.java` | 저장 |
| `.../identity/CredentialIssuer.java` | JWT 발급 (`iss`/`aud`/`sub`/`cnf.jkt`) |
| `.../registration/ChallengeStore.java` | challenge 발급·1회 소비 |
| `.../registration/RegistrationService.java` | 등록 오케스트레이션 (멱등) |
| `.../proof/JwsProofVerifier.java` | DPoP/PoP 공통 검증 (`typ` 강제, `jti` 재생 방지) |
| `.../api/RegistrationController.java` | `/agent/registration/*` |
| `.../api/CredentialController.java` | `/agent/credential`, `/agent/whoami` |

**클라이언트** (`android/app/src/main/kotlin/dev/starryeye/ondeviceagent/`)

| 파일 | 책임 |
|---|---|
| `identity/AgentKeyStore.kt` | 키 생성(StrongBox→TEE 폴백), 체인 추출, 서명 |
| `identity/DeviceBindingProvider.kt` | 이음매. 지금은 `NoDeviceBinding` |
| `identity/IntegrityTokenProvider.kt` | 이음매. 지금은 `NoIntegrityToken` |
| `identity/JwsProofSigner.kt` | PoP/DPoP proof 생성 |
| `identity/AgentRegistrar.kt` | 등록 흐름 |
| `identity/AgentIdentityState.kt` | 등록 상태 |
| `AgentViewModel.kt` (수정) | 시작 시 등록을 함께 시작 |

---

## Task 1: 서버 스캐폴딩 + 공식 검증기 컴포지트 빌드

이 태스크의 목적은 기능이 아니라 **가장 큰 미지수 제거**다. 공식 검증 라이브러리는 Maven
Central에 없고 group 좌표도 없어서, git submodule + 컴포지트 빌드 + 명시적 의존성 치환으로
붙여야 한다. 이 조합이 도는지 먼저 증명한다.

**Files:**
- Create: `server/settings.gradle.kts`, `server/build.gradle.kts`, `server/gradle.properties`, `server/.gitignore`
- Create: `server/gradlew`, `server/gradlew.bat`, `server/gradle/wrapper/` (`gradle wrapper`가 생성)
- Create: `server/src/main/java/dev/starryeye/agentidentity/ServerApplication.java`
- Create: `server/src/main/resources/application.yml`
- Create: `server/src/test/java/dev/starryeye/agentidentity/VerifierLinkageTest.java`
- Modify: `.gitmodules` (submodule 추가로 생성됨)
- Modify: `server/README.md` (자리표시를 실제 안내로)

**Interfaces:**
- Consumes: 없음
- Produces: 빌드 가능한 Spring Boot 앱. 이후 모든 서버 태스크가 여기에 얹는다

- [ ] **Step 1: git submodule 추가**

```bash
git submodule add https://github.com/android/keyattestation.git server/third_party/keyattestation
git submodule update --init --recursive
```

- [ ] **Step 2: `server/.gitignore` 작성**

```gitignore
.gradle/
build/
*.log
data/
```

- [ ] **Step 3: `server/gradle.properties` 작성**

```properties
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
org.gradle.caching=true
```

- [ ] **Step 4: `server/settings.gradle.kts` 작성**

컴포지트 빌드의 핵심은 **명시적 의존성 치환**이다. 포함된 빌드에 group 좌표가 없으므로,
치환 규칙으로 좌표를 우리가 붙여 준다.

```kotlin
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0" }

rootProject.name = "agent-identity-server"

// 공식 Key Attestation 검증기. Maven Central 에 없고 group 좌표도 없어서
// 명시적 치환으로 붙인다. 서브모듈은 수정하지 않는다.
includeBuild("third_party/keyattestation") {
  dependencySubstitution {
    substitute(module("com.android.keyattestation:keyattestation")).using(project(":"))
  }
}
```

- [ ] **Step 5: `server/build.gradle.kts` 작성**

```kotlin
plugins {
  java
  id("org.springframework.boot") version "4.1.1"
  id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.starryeye"
version = "0.1.0"

// Java 21: 공식 검증 라이브러리가 21 툴체인을 요구한다.
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories { mavenCentral() }

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("com.nimbusds:nimbus-jose-jwt:10.9.1")
  implementation("com.android.keyattestation:keyattestation")
  runtimeOnly("com.h2database:h2")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> { useJUnitPlatform() }
```

- [ ] **Step 6: `ServerApplication.java` 작성**

```java
package dev.starryeye.agentidentity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ServerApplication {
  public static void main(String[] args) {
    SpringApplication.run(ServerApplication.class, args);
  }
}
```

- [ ] **Step 7: `application.yml` 작성**

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:h2:file:./data/agent-identity;AUTO_SERVER=TRUE
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: update
```

- [ ] **Step 8: 검증기 연결을 증명하는 테스트 작성**

이 태스크의 실질적 산출물이다. 컴파일만으로도 컴포지트 빌드가 동작함을 증명하지만,
생성자까지 호출해 Kotlin → Java 상호운용도 함께 확인한다.

`server/src/test/java/dev/starryeye/agentidentity/VerifierLinkageTest.java`:

```java
package dev.starryeye.agentidentity;

import static org.assertj.core.api.Assertions.assertThat;

import com.android.keyattestation.verifier.ConstraintConfig;
import com.android.keyattestation.verifier.InstantSource;
import com.android.keyattestation.verifier.Verifier;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 공식 검증 라이브러리가 컴포지트 빌드로 실제로 붙는지 증명한다.
 *
 * 이 라이브러리는 Maven Central 에 없고 group 좌표도 없어서 settings.gradle.kts 의 명시적
 * 치환에 의존한다. 그 배선이 깨지면 여기서 컴파일이 실패한다. 또한 Kotlin 라이브러리를
 * Java 에서 쓰므로, 생성자 호출까지 해서 상호운용도 함께 본다.
 */
class VerifierLinkageTest {

  @Test
  void 공식_검증기를_Java에서_생성할_수_있다() {
    InstantSource clock = () -> Instant.parse("2026-08-28T00:00:00Z");

    Verifier verifier =
        new Verifier(
            () -> Set.of(), // 신뢰 앵커는 Task 2 에서 실제 값을 넣는다
            () -> Set.of(), // 폐기 목록도 Task 2 에서
            clock,
            ConstraintConfig.testDefault());

    assertThat(verifier).isNotNull();
  }
}
```

- [ ] **Step 9: Gradle wrapper 생성**

```bash
cd server && gradle wrapper --gradle-version 8.14
```

- [ ] **Step 10: 빌드와 테스트 — 이 태스크의 검증점**

```bash
cd server && ./gradlew build
```
Expected: `BUILD SUCCESSFUL`, `VerifierLinkageTest` 통과.

실패하면 **버전을 임의로 바꾸지 말고** 오류를 그대로 보고할 것. 흔한 원인은 (a) 서브모듈이
초기화되지 않음, (b) `dependencySubstitution` 좌표 오타, (c) Java 21 툴체인을 못 찾음
(foojay resolver가 자동 프로비저닝해야 한다).

- [ ] **Step 11: 앱이 실제로 뜨는지 확인**

```bash
cd server && (./gradlew bootRun &) && sleep 40 && curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/ ; pkill -f bootRun
```
Expected: HTTP 코드가 돌아온다(404여도 좋다 — 컨트롤러가 아직 없다). 기동 자체가 확인점이다.

- [ ] **Step 12: `server/README.md` 갱신**

```markdown
# 에이전트 신원 발급 서버

온디바이스 에이전트에게 하드웨어에 묶인 신원을 발급한다.
설계: [../docs/superpowers/specs/2026-08-27-agent-identity-registration-design.md](../docs/superpowers/specs/2026-08-27-agent-identity-registration-design.md)

## 사전 준비

Key Attestation 검증에 구글 공식 라이브러리를 쓴다. Maven Central 에 없어 서브모듈로 받는다.

```bash
git submodule update --init --recursive
```

Java 21 이 필요하다(라이브러리 요구사항). Gradle 툴체인이 자동으로 받아온다.

## 빌드와 실행

```bash
./gradlew build
./gradlew bootRun
```

## 안드로이드에서 접속

기기에서 맥의 서버로 닿게 한다.

```bash
adb reverse tcp:8080 tcp:8080
```
```

- [ ] **Step 13: 커밋 & 푸시**

```bash
git add -A && git commit -m "build: 신원 발급 서버 스캐폴딩과 공식 검증기 컴포지트 빌드" && git push
```

---

## Task 2: Attestation 체인 검증

공식 검증기를 우리 도메인 타입 뒤로 감싼다. 이 패키지는 **HTTP도 JPA도 모른다** — 그래야
실기기에서 뽑은 체인을 픽스처로 반복 검증할 수 있다.

**Files:**
- Create: `server/src/main/java/dev/starryeye/agentidentity/attestation/AttestationResult.java`
- Create: `server/src/main/java/dev/starryeye/agentidentity/attestation/AttestationVerifier.java`
- Create: `server/src/main/java/dev/starryeye/agentidentity/attestation/TrustAnchorSource.java`
- Create: `server/src/main/java/dev/starryeye/agentidentity/attestation/RevocationSource.java`
- Create: `server/src/test/resources/fixtures/attestation-chain-a36.pem`
- Test: `server/src/test/java/dev/starryeye/agentidentity/attestation/AttestationVerifierTest.java`

**Interfaces:**
- Consumes: Task 1의 빌드
- Produces:
  - `interface TrustAnchorSource { Set<TrustAnchor> anchors(); }`
  - `interface RevocationSource { Set<String> revokedSerials(); }`
  - `sealed interface AttestationResult` — `Verified` / `Rejected`
    - `record Verified(PublicKey publicKey, byte[] challenge, String securityLevel, String verifiedBootState, boolean deviceLocked, String packageName, List<String> signingDigests)`
    - `record Rejected(String detail)`
  - `class AttestationVerifier { AttestationResult verify(List<X509Certificate> chain, byte[] expectedChallenge); }`

- [ ] **Step 1: 픽스처를 테스트 리소스로 복사**

실기기(A36)에서 `AttestationProbeTest`로 뽑은 체인이다. `/tmp/fixture-chain.pem`에 준비돼 있다.

```bash
mkdir -p server/src/test/resources/fixtures
cp /tmp/fixture-chain.pem server/src/test/resources/fixtures/attestation-chain-a36.pem
```

체인의 RKP 중간 인증서 유효기간은 **2026-08-21 ~ 2026-09-03**이다. 테스트는 이 창 안의
시각을 고정해 쓴다. 픽스처가 개발 기기의 인증서를 담고 있다는 점은 알고 커밋한다.

- [ ] **Step 2: 실패하는 테스트 작성**

`AttestationVerifierTest.java`:

```java
package dev.starryeye.agentidentity.attestation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 실기기에서 뽑은 체인으로 검증기를 시험한다.
 *
 * 시각을 고정하는 것이 핵심이다. RKP 중간 인증서의 유효기간이 13일이라, 시스템 시각으로
 * 검증하면 이 테스트는 2주도 못 가 저절로 실패한다.
 */
class AttestationVerifierTest {

  /** 픽스처 체인이 유효했던 시점. 2026-08-21 ~ 09-03 창 안이다. */
  private static final Instant VALID_AT = Instant.parse("2026-08-28T12:00:00Z");

  /** 우리가 기기에 넣었던 challenge (AttestationProbeTest 가 0..31 을 넣는다). */
  private static byte[] probeChallenge() {
    byte[] challenge = new byte[32];
    for (int i = 0; i < 32; i++) challenge[i] = (byte) i;
    return challenge;
  }

  private static List<X509Certificate> fixtureChain() throws Exception {
    try (InputStream in =
        AttestationVerifierTest.class.getResourceAsStream(
            "/fixtures/attestation-chain-a36.pem")) {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      List<X509Certificate> chain = new ArrayList<>();
      for (var certificate : factory.generateCertificates(in)) {
        chain.add((X509Certificate) certificate);
      }
      return chain;
    }
  }

  /** 체인의 루트(자기서명)를 앵커로 쓴다. 루트가 공개 목록에 있는지는 TrustAnchorSource 의 몫이다. */
  private static TrustAnchorSource anchorsFromFixture() throws Exception {
    List<X509Certificate> chain = fixtureChain();
    TrustAnchor anchor = new TrustAnchor(chain.get(chain.size() - 1), null);
    return () -> Set.of(anchor);
  }

  private static AttestationVerifier verifierAt(Instant now, RevocationSource revocation)
      throws Exception {
    return new AttestationVerifier(anchorsFromFixture(), revocation, () -> now);
  }

  @Test
  void 유효한_체인은_검증되고_기기_속성을_돌려준다() throws Exception {
    AttestationResult result =
        verifierAt(VALID_AT, Set::of).verify(fixtureChain(), probeChallenge());

    assertThat(result).isInstanceOf(AttestationResult.Verified.class);
    AttestationResult.Verified verified = (AttestationResult.Verified) result;
    assertThat(verified.securityLevel()).isEqualTo("TRUSTED_ENVIRONMENT");
    assertThat(verified.packageName()).isEqualTo("dev.starryeye.ondeviceagent");
    assertThat(verified.deviceLocked()).isTrue();
  }

  @Test
  void challenge_가_다르면_거절한다() throws Exception {
    AttestationResult result =
        verifierAt(VALID_AT, Set::of).verify(fixtureChain(), new byte[32]);

    assertThat(result).isInstanceOf(AttestationResult.Rejected.class);
  }

  @Test
  void 유효기간이_지난_뒤에는_거절한다() throws Exception {
    // RKP 중간 인증서는 2026-09-03 에 만료된다. 그 뒤 시각으로 보면 통과하면 안 된다.
    AttestationResult result =
        verifierAt(Instant.parse("2026-10-01T00:00:00Z"), Set::of)
            .verify(fixtureChain(), probeChallenge());

    assertThat(result).isInstanceOf(AttestationResult.Rejected.class);
  }

  @Test
  void 체인의_인증서가_폐기목록에_있으면_거절한다() throws Exception {
    List<X509Certificate> chain = fixtureChain();
    String revokedSerial = chain.get(1).getSerialNumber().toString(16);

    AttestationResult result =
        verifierAt(VALID_AT, () -> Set.of(revokedSerial)).verify(chain, probeChallenge());

    assertThat(result).isInstanceOf(AttestationResult.Rejected.class);
  }

  @Test
  void 알려지지_않은_루트는_거절한다() throws Exception {
    AttestationVerifier verifier =
        new AttestationVerifier(Set::of, Set::of, () -> VALID_AT); // 앵커 없음

    assertThat(verifier.verify(fixtureChain(), probeChallenge()))
        .isInstanceOf(AttestationResult.Rejected.class);
  }
}
```

- [ ] **Step 3: 테스트를 돌려 실패를 확인**

```bash
cd server && ./gradlew test --tests '*AttestationVerifierTest*'
```
Expected: 컴파일 실패 — `AttestationVerifier` 없음.

- [ ] **Step 4: 소스 인터페이스 두 개 작성**

`TrustAnchorSource.java`:

```java
package dev.starryeye.agentidentity.attestation;

import java.security.cert.TrustAnchor;
import java.util.Set;

/**
 * 신뢰할 구글 attestation 루트. 하나가 아니고 목록이 갱신되므로 조회기로 둔다.
 * 테스트는 고정 집합을, 운영은 공개 목록을 캐시해 돌려준다.
 */
@FunctionalInterface
public interface TrustAnchorSource {
  Set<TrustAnchor> anchors();
}
```

`RevocationSource.java`:

```java
package dev.starryeye.agentidentity.attestation;

import java.util.Set;

/**
 * 폐기된 attestation 인증서의 시리얼 번호(소문자 16진).
 *
 * 확인은 선택이 아니다 — 폐기된 키로 만든 체인은 하드웨어 보증이 무효다. 조회기로 둔 것은
 * 테스트에서 네트워크를 타지 않기 위해서다.
 */
@FunctionalInterface
public interface RevocationSource {
  Set<String> revokedSerials();
}
```

- [ ] **Step 5: 결과 타입 작성**

`AttestationResult.java`:

```java
package dev.starryeye.agentidentity.attestation;

import java.security.PublicKey;
import java.util.List;

/** 체인 검증의 결과. 정책 판단에 필요한 것만 담는다. */
public sealed interface AttestationResult {

  /**
   * 검증을 통과했다.
   *
   * @param packageName 소프트웨어 강제 값이다. 장악된 시스템에서는 위조될 수 있으므로,
   *     이 값을 믿는 근거는 verifiedBootState 가 Verified 라는 것이다.
   */
  record Verified(
      PublicKey publicKey,
      byte[] challenge,
      String securityLevel,
      String verifiedBootState,
      boolean deviceLocked,
      String packageName,
      List<String> signingDigests)
      implements AttestationResult {}

  /** 거절. detail 은 로그용이며 사용자에게 그대로 보이지 않는다. */
  record Rejected(String detail) implements AttestationResult {}
}
```

- [ ] **Step 6: 검증기 구현**

`AttestationVerifier.java`:

```java
package dev.starryeye.agentidentity.attestation;

import com.android.keyattestation.verifier.AttestationApplicationId;
import com.android.keyattestation.verifier.ConstraintConfig;
import com.android.keyattestation.verifier.InstantSource;
import com.android.keyattestation.verifier.KeyDescription;
import com.android.keyattestation.verifier.VerificationResult;
import com.android.keyattestation.verifier.Verifier;
import com.google.common.util.concurrent.Futures;
import com.google.protobuf.ByteString;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 구글 공식 검증기를 우리 도메인 타입 뒤로 감싼다.
 *
 * 직접 파싱하지 않는 이유는 체인 검증·루트 집합·RKP 유효기간·폐기 목록·확장 파싱이 모두
 * 놓치기 쉬운 영역이고, 우리가 새로 짜서 더 잘할 이유가 없기 때문이다.
 *
 * 이 클래스는 HTTP 도 JPA 도 모른다. 그래야 실기기 체인을 픽스처로 반복 검증할 수 있다.
 */
public class AttestationVerifier {

  private final Verifier verifier;

  public AttestationVerifier(
      TrustAnchorSource anchors, RevocationSource revocation, InstantSource clock) {
    this.verifier =
        new Verifier(anchors::anchors, revocation::revokedSerials, clock, new ConstraintConfig());
  }

  /**
   * 체인을 검증하고 정책이 필요로 하는 값을 뽑는다.
   *
   * @param expectedChallenge 우리가 발급한 challenge. 체인 안에 이 값이 박혀 있어야 한다
   */
  public AttestationResult verify(List<X509Certificate> chain, byte[] expectedChallenge) {
    if (chain.isEmpty()) {
      return new AttestationResult.Rejected("empty chain");
    }

    ByteString expected = ByteString.copyFrom(expectedChallenge);
    VerificationResult result =
        verifier.verify(chain, challenge -> Futures.immediateFuture(challenge.equals(expected)));

    if (!(result instanceof VerificationResult.Success success)) {
      return new AttestationResult.Rejected(result.getClass().getSimpleName());
    }

    // 앱 신원은 Success 에 담겨 오지 않는다. leaf 를 직접 파싱해 읽는다.
    // 이 값이 softwareEnforced 에 있다는 사실이 타입으로 드러난다.
    KeyDescription description = KeyDescription.parseFrom(chain.get(0));
    AttestationApplicationId application =
        description.getSoftwareEnforced().getAttestationApplicationId();
    if (application == null) {
      return new AttestationResult.Rejected("no attestationApplicationId");
    }

    return new AttestationResult.Verified(
        success.getPublicKey(),
        success.getChallenge().toByteArray(),
        success.getSecurityLevel().toString(),
        success.getVerifiedBootState().toString(),
        success.getDeviceLocked(),
        application.getPackages().keySet().iterator().next(),
        application.getSignatures().stream()
            .map(Object::toString)
            .collect(Collectors.toList()));
  }
}
```

`AttestationApplicationId`의 실제 접근자 이름이 위와 다르면 **추측으로 고치지 말고**
`server/third_party/keyattestation/src/main/kotlin/Extension.kt`에서 확인해 맞춘다.
`securityLevel`/`verifiedBootState`의 문자열 표현도 실제 enum 이름을 확인해 테스트 기대값을
맞춘다.

- [ ] **Step 7: 테스트 통과 확인**

```bash
cd server && ./gradlew test --tests '*AttestationVerifierTest*'
```
Expected: 5개 통과.

- [ ] **Step 8: 운영용 조회기와 빈 등록**

테스트는 고정 집합을 쓰지만 운영은 공개 목록을 받아야 한다. **캐시하지 않으면 등록마다
네트워크를 탄다.** 조회 실패 시에는 **등록을 거절**한다 — 열어두면 CRL 우회가 된다.

`server/src/main/java/dev/starryeye/agentidentity/attestation/AttestationConfiguration.java`:

```java
package dev.starryeye.agentidentity.attestation;

import com.android.keyattestation.verifier.GoogleRevocationListKt;
import java.io.InputStream;
import java.net.URI;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 구글 루트 목록과 폐기 목록을 받아 캐시하고, 검증기를 조립한다. */
@Configuration
public class AttestationConfiguration {

  private static final String ROOTS_URL = "https://android.googleapis.com/attestation/root";
  private static final Duration CACHE_TTL = Duration.ofHours(6);

  @Bean
  public TrustAnchorSource trustAnchorSource(Clock clock) {
    Cache<Set<TrustAnchor>> cache = new Cache<>(clock, CACHE_TTL);
    return () -> cache.get(AttestationConfiguration::fetchAnchors);
  }

  @Bean
  public RevocationSource revocationSource(Clock clock) {
    Cache<Set<String>> cache = new Cache<>(clock, CACHE_TTL);
    return () -> cache.get(GoogleRevocationListKt::getGoogleRevocationStatusFromWeb);
  }

  @Bean
  public AttestationVerifier attestationVerifier(
      TrustAnchorSource anchors, RevocationSource revocation, Clock clock) {
    return new AttestationVerifier(anchors, revocation, clock::instant);
  }

  /** 목록은 공개된 JSON 배열(PEM 문자열들)이다. */
  private static Set<TrustAnchor> fetchAnchors() {
    try (InputStream in = URI.create(ROOTS_URL).toURL().openStream()) {
      String body = new String(in.readAllBytes());
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      Set<TrustAnchor> anchors = new HashSet<>();
      for (String pem : body.split("\"")) {
        String candidate = pem.replace("\\n", "\n");
        if (!candidate.contains("BEGIN CERTIFICATE")) continue;
        X509Certificate certificate =
            (X509Certificate)
                factory.generateCertificate(new java.io.ByteArrayInputStream(candidate.getBytes()));
        anchors.add(new TrustAnchor(certificate, null));
      }
      if (anchors.isEmpty()) throw new IllegalStateException("루트 목록이 비었다");
      return anchors;
    } catch (Exception e) {
      // 열어두면 CRL·루트 검사를 우회할 수 있다. 조회 실패는 등록 실패다.
      throw new IllegalStateException("attestation 루트 목록을 받지 못했다", e);
    }
  }

  /** 아주 단순한 TTL 캐시. 실패는 캐시하지 않는다. */
  private static final class Cache<T> {
    private final AtomicReference<T> value = new AtomicReference<>();
    private final AtomicReference<Instant> fetchedAt = new AtomicReference<>();
    private final Clock clock;
    private final Duration ttl;

    Cache(Clock clock, Duration ttl) {
      this.clock = clock;
      this.ttl = ttl;
    }

    T get(java.util.function.Supplier<T> loader) {
      Instant at = fetchedAt.get();
      T cached = value.get();
      if (cached != null && at != null && Duration.between(at, clock.instant()).compareTo(ttl) < 0) {
        return cached;
      }
      T loaded = loader.get();
      value.set(loaded);
      fetchedAt.set(clock.instant());
      return loaded;
    }
  }
}
```

`GoogleRevocationListKt` 의 실제 클래스명(Kotlin 최상위 함수의 JVM 이름)이 다르면
`server/third_party/keyattestation/src/main/kotlin/GoogleRevocationList.kt` 의 `@file:JvmName`
유무를 확인해 맞춘다.

- [ ] **Step 9: 커밋 & 푸시**

```bash
git add -A && git commit -m "feat(server): attestation 체인 검증 (공식 검증기 래핑, 시각 주입)" && git push
```

---

## Task 3: 정책 판단과 사유 코드

정책은 이 연구의 실험 손잡이다. 거절에 **사유 코드**를 붙이는 것이 핵심이다 — "거절됨"만으로는
아무것도 배우지 못한다.

**Files:**
- Create: `.../policy/RejectionReason.java`, `.../policy/PolicyProperties.java`, `.../policy/RegistrationPolicy.java`
- Test: `server/src/test/java/dev/starryeye/agentidentity/policy/RegistrationPolicyTest.java`

**Interfaces:**
- Consumes: `AttestationResult.Verified` (Task 2)
- Produces: `Optional<RejectionReason> RegistrationPolicy.evaluate(Verified attestation, String deviceBinding, String integrityToken)` — 비면 수용

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package dev.starryeye.agentidentity.policy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.starryeye.agentidentity.attestation.AttestationResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RegistrationPolicyTest {

  private static AttestationResult.Verified attestation(
      String securityLevel, String bootState, boolean locked, String packageName) {
    return new AttestationResult.Verified(
        null, new byte[32], securityLevel, bootState, locked, packageName, List.of());
  }

  private static PolicyProperties defaults() {
    PolicyProperties properties = new PolicyProperties();
    properties.setRequireSecurityLevel("TRUSTED_ENVIRONMENT");
    properties.setRequireVerifiedBoot(true);
    properties.setRequireDeviceLocked(true);
    properties.setAllowedPackages(List.of("dev.starryeye.ondeviceagent"));
    properties.setRequireDeviceBinding(false);
    properties.setRequirePlayIntegrity(false);
    return properties;
  }

  @Test
  void 정상_증명은_수용한다() {
    Optional<RejectionReason> reason =
        new RegistrationPolicy(defaults())
            .evaluate(
                attestation("TRUSTED_ENVIRONMENT", "VERIFIED", true, "dev.starryeye.ondeviceagent"),
                null,
                null);

    assertThat(reason).isEmpty();
  }

  @Test
  void StrongBox_를_요구하면_TEE_기기는_거절한다() {
    PolicyProperties strict = defaults();
    strict.setRequireSecurityLevel("STRONGBOX");

    Optional<RejectionReason> reason =
        new RegistrationPolicy(strict)
            .evaluate(
                attestation("TRUSTED_ENVIRONMENT", "VERIFIED", true, "dev.starryeye.ondeviceagent"),
                null,
                null);

    assertThat(reason).contains(RejectionReason.POLICY_SECURITY_LEVEL);
  }

  @Test
  void 부팅이_검증되지_않으면_거절한다() {
    Optional<RejectionReason> reason =
        new RegistrationPolicy(defaults())
            .evaluate(
                attestation(
                    "TRUSTED_ENVIRONMENT", "UNVERIFIED", true, "dev.starryeye.ondeviceagent"),
                null,
                null);

    assertThat(reason).contains(RejectionReason.POLICY_VERIFIED_BOOT);
  }

  @Test
  void 다른_앱의_패키지명은_거절한다() {
    Optional<RejectionReason> reason =
        new RegistrationPolicy(defaults())
            .evaluate(
                attestation("TRUSTED_ENVIRONMENT", "VERIFIED", true, "com.evil.clone"), null, null);

    assertThat(reason).contains(RejectionReason.POLICY_APPLICATION);
  }

  @Test
  void 기기_증명을_요구하면_소매기기는_거절한다() {
    PolicyProperties strict = defaults();
    strict.setRequireDeviceBinding(true);

    Optional<RejectionReason> reason =
        new RegistrationPolicy(strict)
            .evaluate(
                attestation("TRUSTED_ENVIRONMENT", "VERIFIED", true, "dev.starryeye.ondeviceagent"),
                null,
                null);

    assertThat(reason).contains(RejectionReason.POLICY_DEVICE_BINDING);
  }
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인**

```bash
cd server && ./gradlew test --tests '*RegistrationPolicyTest*'
```
Expected: 컴파일 실패.

- [ ] **Step 3: 사유 코드 작성**

```java
package dev.starryeye.agentidentity.policy;

/**
 * 거절 사유. 클라이언트와 로그가 함께 쓴다.
 *
 * "거절됨" 하나로 뭉뚱그리면 정책을 바꿔가며 관찰하는 이 연구가 성립하지 않는다.
 */
public enum RejectionReason {
  CHALLENGE_INVALID,
  CHAIN_UNTRUSTED,
  CHAIN_REVOKED,
  CHAIN_EXPIRED,
  POLICY_SECURITY_LEVEL,
  POLICY_VERIFIED_BOOT,
  POLICY_DEVICE_LOCKED,
  POLICY_APPLICATION,
  POLICY_DEVICE_BINDING,
  POLICY_INTEGRITY,
  POP_INVALID,
  DPOP_INVALID,
  CREDENTIAL_EXPIRED,
  REATTESTATION_REQUIRED;
}
```

- [ ] **Step 4: 설정 바인딩 작성**

```java
package dev.starryeye.agentidentity.policy;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** `agent-registration.*` 설정. 이 값들이 실험 손잡이다. */
@ConfigurationProperties(prefix = "agent-registration")
public class PolicyProperties {

  private String requireSecurityLevel = "TRUSTED_ENVIRONMENT";
  private boolean requireVerifiedBoot = true;
  private boolean requireDeviceLocked = true;
  private List<String> allowedPackages = List.of();
  private boolean requireDeviceBinding = false;
  private boolean requirePlayIntegrity = false;
  private String agentProductId = "galaxy-personal-agent";
  private String identifierNamespace = "samsung";
  private Duration challengeTtl = Duration.ofMinutes(5);
  private Duration credentialTtl = Duration.ofMinutes(15);
  private Duration maxAttestationAge = Duration.ofDays(7);

  public String getRequireSecurityLevel() { return requireSecurityLevel; }
  public void setRequireSecurityLevel(String value) { this.requireSecurityLevel = value; }
  public boolean isRequireVerifiedBoot() { return requireVerifiedBoot; }
  public void setRequireVerifiedBoot(boolean value) { this.requireVerifiedBoot = value; }
  public boolean isRequireDeviceLocked() { return requireDeviceLocked; }
  public void setRequireDeviceLocked(boolean value) { this.requireDeviceLocked = value; }
  public List<String> getAllowedPackages() { return allowedPackages; }
  public void setAllowedPackages(List<String> value) { this.allowedPackages = value; }
  public boolean isRequireDeviceBinding() { return requireDeviceBinding; }
  public void setRequireDeviceBinding(boolean value) { this.requireDeviceBinding = value; }
  public boolean isRequirePlayIntegrity() { return requirePlayIntegrity; }
  public void setRequirePlayIntegrity(boolean value) { this.requirePlayIntegrity = value; }
  public String getAgentProductId() { return agentProductId; }
  public void setAgentProductId(String value) { this.agentProductId = value; }
  public String getIdentifierNamespace() { return identifierNamespace; }
  public void setIdentifierNamespace(String value) { this.identifierNamespace = value; }
  public Duration getChallengeTtl() { return challengeTtl; }
  public void setChallengeTtl(Duration value) { this.challengeTtl = value; }
  public Duration getCredentialTtl() { return credentialTtl; }
  public void setCredentialTtl(Duration value) { this.credentialTtl = value; }
  public Duration getMaxAttestationAge() { return maxAttestationAge; }
  public void setMaxAttestationAge(Duration value) { this.maxAttestationAge = value; }
}
```

- [ ] **Step 5: 정책 구현**

```java
package dev.starryeye.agentidentity.policy;

import dev.starryeye.agentidentity.attestation.AttestationResult;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 증명이 정책을 통과하는지 판단한다.
 *
 * 순서에 의미가 있다. 앱 신원(`packageName`)은 소프트웨어 강제 값이라 그 자체로는 사칭을 막지
 * 못한다 — 부팅이 검증된 기기에서만 플랫폼 코드를 신뢰할 수 있으므로, 부팅 검사를 먼저 한다.
 * `requireVerifiedBoot` 를 끄면 앱 신원 검사도 함께 무의미해진다는 뜻이다.
 */
@Component
public class RegistrationPolicy {

  private final PolicyProperties properties;

  public RegistrationPolicy(PolicyProperties properties) {
    this.properties = properties;
  }

  public Optional<RejectionReason> evaluate(
      AttestationResult.Verified attestation, String deviceBinding, String integrityToken) {

    if (!securityLevelSatisfied(attestation.securityLevel())) {
      return Optional.of(RejectionReason.POLICY_SECURITY_LEVEL);
    }
    if (properties.isRequireVerifiedBoot() && !"VERIFIED".equals(attestation.verifiedBootState())) {
      return Optional.of(RejectionReason.POLICY_VERIFIED_BOOT);
    }
    if (properties.isRequireDeviceLocked() && !attestation.deviceLocked()) {
      return Optional.of(RejectionReason.POLICY_DEVICE_LOCKED);
    }
    if (!properties.getAllowedPackages().isEmpty()
        && !properties.getAllowedPackages().contains(attestation.packageName())) {
      return Optional.of(RejectionReason.POLICY_APPLICATION);
    }
    if (properties.isRequireDeviceBinding() && deviceBinding == null) {
      return Optional.of(RejectionReason.POLICY_DEVICE_BINDING);
    }
    if (properties.isRequirePlayIntegrity() && integrityToken == null) {
      return Optional.of(RejectionReason.POLICY_INTEGRITY);
    }
    return Optional.empty();
  }

  /** StrongBox 를 요구하면 TEE 는 통과하지 못한다. 반대는 통과한다. */
  private boolean securityLevelSatisfied(String actual) {
    String required = properties.getRequireSecurityLevel();
    if ("STRONGBOX".equals(required)) {
      return "STRONG_BOX".equals(actual) || "STRONGBOX".equals(actual);
    }
    return !"SOFTWARE".equals(actual);
  }
}
```

`attestation.securityLevel()`의 실제 문자열은 Task 2에서 확인한 enum 이름에 맞춘다. 다르면
여기와 테스트를 함께 고친다.

- [ ] **Step 6: 테스트 통과 확인**

```bash
cd server && ./gradlew test --tests '*RegistrationPolicyTest*'
```
Expected: 5개 통과.

- [ ] **Step 7: 커밋 & 푸시**

```bash
git add -A && git commit -m "feat(server): 등록 정책과 거절 사유 코드" && git push
```

---

## Task 4: 신원 발급과 등록 (멱등)

**Files:**
- Create: `.../identity/AgentIdentifier.java`, `.../identity/AgentIdentity.java`, `.../identity/AgentIdentityRepository.java`
- Create: `.../registration/ChallengeStore.java`, `.../registration/RegistrationService.java`
- Test: `.../identity/AgentIdentifierTest.java`, `.../registration/RegistrationServiceTest.java`

**Interfaces:**
- Consumes: `AttestationResult` (T2), `RegistrationPolicy`·`RejectionReason` (T3)
- Produces:
  - `AgentIdentifier.create(namespace, product, uuid) -> String`, `AgentIdentifier.matches(a, b) -> boolean`
  - `ChallengeStore.issue() -> Challenge(registrationId, value, expiresAt)`, `consume(registrationId) -> Optional<byte[]>`
  - `RegistrationService.register(registrationId, chain, deviceBinding, integrityToken) -> RegistrationOutcome`

- [ ] **Step 1: 식별자 테스트 작성**

접두어 비교 금지가 이 테스트의 핵심이다.

```java
package dev.starryeye.agentidentity.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentIdentifierTest {

  @Test
  void 형식대로_조립한다() {
    assertThat(AgentIdentifier.create("samsung", "galaxy-personal-agent", "3f2a"))
        .isEqualTo("urn:samsung:agent:galaxy-personal-agent:3f2a");
  }

  @Test
  void 접두어가_같아도_다른_식별자다() {
    // 접두어 비교로 구현하면 x 가 xyz 를 통과시키는 우회가 생긴다.
    String shorter = AgentIdentifier.create("samsung", "p", "x");
    String longer = AgentIdentifier.create("samsung", "p", "xyz");

    assertThat(AgentIdentifier.matches(shorter, longer)).isFalse();
    assertThat(AgentIdentifier.matches(shorter, shorter)).isTrue();
  }
}
```

- [ ] **Step 2: 실패 확인 후 식별자 구현**

```bash
cd server && ./gradlew test --tests '*AgentIdentifierTest*'
```
Expected: 컴파일 실패. 이어서 구현한다.

```java
package dev.starryeye.agentidentity.identity;

/**
 * 에이전트 식별자 조립을 **전담**한다. 형식이 이 클래스 밖으로 새어 나가지 않게 한다 —
 * 나중에 표준 형식으로 옮길 이유가 생기면 교체가 여기 한 곳으로 끝나야 한다.
 *
 * `urn:` 문법을 따르지만 NID `samsung` 은 IANA 미등록 사설 네임스페이스다.
 */
public final class AgentIdentifier {

  private AgentIdentifier() {}

  public static String create(String namespace, String productId, String instanceId) {
    return "urn:" + namespace + ":agent:" + productId + ":" + instanceId;
  }

  /**
   * 인가 판단용 비교. **전체 문자열 일치만 허용한다.**
   * 접두어 비교는 `...:agent:x` 가 `...:agent:xyz` 를 통과시키는 우회가 된다.
   */
  public static boolean matches(String left, String right) {
    return left != null && left.equals(right);
  }
}
```

- [ ] **Step 3: 엔티티와 저장소 작성**

```java
package dev.starryeye.agentidentity.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;

/** 발급된 에이전트 신원. `jwkThumbprint` 가 실질적 키이며 등록은 이 값에 대해 멱등이다. */
@Entity
public class AgentIdentity {

  @Id private String id;

  @Column(nullable = false, unique = true)
  private String jwkThumbprint;

  private String agentProductId;
  private String packageName;
  private String securityLevel;
  private String verifiedBootState;
  private boolean deviceLocked;
  private String integrityVerdict;
  private String deviceBinding;
  private String subject;
  private Instant createdAt;
  private Instant lastAttestedAt;
  private Instant lastAuthenticatedAt;
  private String status;

  protected AgentIdentity() {}

  public AgentIdentity(
      String id,
      String jwkThumbprint,
      String agentProductId,
      String packageName,
      String securityLevel,
      String verifiedBootState,
      boolean deviceLocked,
      Instant now) {
    this.id = id;
    this.jwkThumbprint = jwkThumbprint;
    this.agentProductId = agentProductId;
    this.packageName = packageName;
    this.securityLevel = securityLevel;
    this.verifiedBootState = verifiedBootState;
    this.deviceLocked = deviceLocked;
    this.createdAt = now;
    this.lastAttestedAt = now;
    this.status = "ACTIVE";
  }

  public String getId() { return id; }
  public String getJwkThumbprint() { return jwkThumbprint; }
  public String getStatus() { return status; }
  public Instant getLastAttestedAt() { return lastAttestedAt; }
  public void markAttested(Instant now) { this.lastAttestedAt = now; }
  public void markAuthenticated(Instant now) { this.lastAuthenticatedAt = now; }
  public void setDeviceBinding(String value) { this.deviceBinding = value; }
  public void setIntegrityVerdict(String value) { this.integrityVerdict = value; }
}
```

```java
package dev.starryeye.agentidentity.identity;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentIdentityRepository extends JpaRepository<AgentIdentity, String> {
  Optional<AgentIdentity> findByJwkThumbprint(String jwkThumbprint);
}
```

- [ ] **Step 4: 멱등성 테스트 작성**

이 태스크의 핵심 테스트다. 깨지면 신원이 자격증명 수명에 끌려다닌다.

```java
package dev.starryeye.agentidentity.registration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RegistrationServiceTest {

  @Autowired private ChallengeStore challengeStore;

  @Test
  void challenge_는_한_번만_소비된다() {
    var challenge = challengeStore.issue();

    assertThat(challengeStore.consume(challenge.registrationId())).isPresent();
    assertThat(challengeStore.consume(challenge.registrationId())).isEmpty();
  }

  @Test
  void 없는_registrationId_는_소비되지_않는다() {
    assertThat(challengeStore.consume("모르는-값")).isEmpty();
  }
}
```

등록 멱등성(같은 키 → 같은 agentId)은 실제 체인이 필요하므로 **Task 7의 실기 검증**에서
확인한다. 픽스처 체인은 challenge 가 고정돼 있어 challenge 소비 흐름과 맞물리지 않기 때문이다.

- [ ] **Step 5: ChallengeStore 구현**

```java
package dev.starryeye.agentidentity.registration;

import dev.starryeye.agentidentity.policy.PolicyProperties;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 등록 challenge 를 발급하고 **한 번만** 소비한다.
 *
 * 1회용이라는 성질이 오래된 attestation 체인의 재사용을 막는다. 재사용을 허용하면 한 번 유출된
 * 체인으로 언제든 등록할 수 있다.
 */
@Component
public class ChallengeStore {

  /** 등록 거래 하나. registrationId 는 거래 식별자이지 신원이 아니다. */
  public record Challenge(String registrationId, byte[] value, Instant expiresAt) {}

  private final Map<String, Challenge> issued = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();
  private final PolicyProperties properties;
  private final Clock clock;

  public ChallengeStore(PolicyProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public Challenge issue() {
    byte[] value = new byte[32];
    random.nextBytes(value);
    Challenge challenge =
        new Challenge(
            UUID.randomUUID().toString(),
            value,
            clock.instant().plus(properties.getChallengeTtl()));
    issued.put(challenge.registrationId(), challenge);
    return challenge;
  }

  /** 소비하면 사라진다. 만료된 것도 사라진다. */
  public Optional<byte[]> consume(String registrationId) {
    Challenge challenge = issued.remove(registrationId);
    if (challenge == null || challenge.expiresAt().isBefore(clock.instant())) {
      return Optional.empty();
    }
    return Optional.of(challenge.value());
  }

  public static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }
}
```

- [ ] **Step 6: RegistrationService 구현**

```java
package dev.starryeye.agentidentity.registration;

import com.nimbusds.jose.jwk.ECKey;
import dev.starryeye.agentidentity.attestation.AttestationResult;
import dev.starryeye.agentidentity.attestation.AttestationVerifier;
import dev.starryeye.agentidentity.identity.AgentIdentifier;
import dev.starryeye.agentidentity.identity.AgentIdentity;
import dev.starryeye.agentidentity.identity.AgentIdentityRepository;
import dev.starryeye.agentidentity.policy.PolicyProperties;
import dev.starryeye.agentidentity.policy.RegistrationPolicy;
import dev.starryeye.agentidentity.policy.RejectionReason;
import java.security.interfaces.ECPublicKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 등록을 조립한다. 검증도 정책 판단도 직접 하지 않고 각각에 맡긴다. */
@Service
public class RegistrationService {

  /** 등록 결과. 거절이면 reason 이 채워진다. */
  public record Outcome(AgentIdentity identity, RejectionReason reason) {
    public static Outcome accepted(AgentIdentity identity) { return new Outcome(identity, null); }
    public static Outcome rejected(RejectionReason reason) { return new Outcome(null, reason); }
    public boolean isAccepted() { return reason == null; }
  }

  private final ChallengeStore challenges;
  private final AttestationVerifier verifier;
  private final RegistrationPolicy policy;
  private final AgentIdentityRepository repository;
  private final PolicyProperties properties;
  private final Clock clock;

  public RegistrationService(
      ChallengeStore challenges,
      AttestationVerifier verifier,
      RegistrationPolicy policy,
      AgentIdentityRepository repository,
      PolicyProperties properties,
      Clock clock) {
    this.challenges = challenges;
    this.verifier = verifier;
    this.policy = policy;
    this.repository = repository;
    this.properties = properties;
    this.clock = clock;
  }

  public Outcome register(
      String registrationId,
      List<X509Certificate> chain,
      String deviceBinding,
      String integrityToken) {

    Optional<byte[]> challenge = challenges.consume(registrationId);
    if (challenge.isEmpty()) {
      return Outcome.rejected(RejectionReason.CHALLENGE_INVALID);
    }

    AttestationResult result = verifier.verify(chain, challenge.get());
    if (!(result instanceof AttestationResult.Verified verified)) {
      // 사유를 뭉개면 정책을 바꿔가며 관찰하는 이 연구가 성립하지 않는다.
      String detail = ((AttestationResult.Rejected) result).detail();
      RejectionReason reason =
          switch (detail) {
            case "ChallengeMismatch" -> RejectionReason.CHALLENGE_INVALID;
            default -> RejectionReason.CHAIN_UNTRUSTED;
          };
      return Outcome.rejected(reason);
    }

    Optional<RejectionReason> rejected = policy.evaluate(verified, deviceBinding, integrityToken);
    if (rejected.isPresent()) {
      return Outcome.rejected(rejected.get());
    }

    String thumbprint = thumbprintOf(verified);

    // 멱등: 같은 키면 같은 신원. 새 신원은 키가 바뀔 때만 생긴다.
    Optional<AgentIdentity> existing = repository.findByJwkThumbprint(thumbprint);
    if (existing.isPresent()) {
      AgentIdentity identity = existing.get();
      identity.markAttested(clock.instant());
      return Outcome.accepted(repository.save(identity));
    }

    AgentIdentity identity =
        new AgentIdentity(
            AgentIdentifier.create(
                properties.getIdentifierNamespace(),
                properties.getAgentProductId(),
                UUID.randomUUID().toString()),
            thumbprint,
            properties.getAgentProductId(),
            verified.packageName(),
            verified.securityLevel(),
            verified.verifiedBootState(),
            verified.deviceLocked(),
            clock.instant());
    identity.setDeviceBinding(deviceBinding);
    return Outcome.accepted(repository.save(identity));
  }

  /** RFC 7638 JWK 지문. 신원의 실질적 키다. */
  static String thumbprintOf(AttestationResult.Verified verified) {
    try {
      return new ECKey.Builder((ECPublicKey) verified.publicKey())
          .build()
          .computeThumbprint()
          .toString();
    } catch (Exception e) {
      throw new IllegalStateException("공개키 지문을 계산하지 못했다", e);
    }
  }
}
```

- [ ] **Step 7: `Clock` 빈 등록**

`ServerApplication.java`에 추가한다. 시각을 주입받는 구조를 서버 전체에 일관되게 둔다.

```java
  @org.springframework.context.annotation.Bean
  public java.time.Clock clock() {
    return java.time.Clock.systemUTC();
  }
```

또한 `@SpringBootApplication` 위에
`@org.springframework.boot.context.properties.EnableConfigurationProperties(dev.starryeye.agentidentity.policy.PolicyProperties.class)`
를 붙인다.

- [ ] **Step 8: 테스트 통과 확인**

```bash
cd server && ./gradlew test
```
Expected: 지금까지의 테스트 전부 통과.

- [ ] **Step 9: 커밋 & 푸시**

```bash
git add -A && git commit -m "feat(server): 신원 발급과 멱등 등록" && git push
```

---

## Task 5: 소유 증명과 자격증명

등록 PoP와 런타임 DPoP는 형태를 공유하되 **수용 조건이 분리**돼야 한다. 같은 `typ`을 쓰면
한쪽 proof를 다른 쪽에 재생할 수 있다.

**Files:**
- Create: `.../proof/ProofType.java`, `.../proof/JwsProofVerifier.java`
- Create: `.../identity/CredentialIssuer.java`
- Create: `.../api/RegistrationController.java`, `.../api/CredentialController.java`
- Test: `.../proof/JwsProofVerifierTest.java`

**Interfaces:**
- Consumes: `AgentIdentityRepository`, `PolicyProperties`, `RegistrationService`
- Produces:
  - `JwsProofVerifier.verify(String jws, ProofType type, String method, String url) -> Optional<String>` (JWK 지문)
  - `CredentialIssuer.issue(AgentIdentity) -> String` (JWT)

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package dev.starryeye.agentidentity.proof;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwsProofVerifierTest {

  private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
  private static final String URL = "https://example.test/agent/credential";

  private static ECKey key() throws Exception {
    return new ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate();
  }

  private static String proof(ECKey key, String typ, String method, String url, Instant iat)
      throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new JOSEObjectType(typ))
                .jwk(key.toPublicJWK())
                .build(),
            new JWTClaimsSet.Builder()
                .claim("htm", method)
                .claim("htu", url)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(iat))
                .build());
    jwt.sign(new ECDSASigner(key));
    return jwt.serialize();
  }

  private static JwsProofVerifier verifier() {
    return new JwsProofVerifier(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60));
  }

  @Test
  void 올바른_DPoP_proof_는_지문을_돌려준다() throws Exception {
    ECKey key = key();
    String jws = proof(key, "dpop+jwt", "POST", URL, NOW);

    assertThat(verifier().verify(jws, ProofType.DPOP, "POST", URL))
        .contains(key.computeThumbprint().toString());
  }

  @Test
  void 등록_PoP_를_DPoP_자리에_내면_거절한다() throws Exception {
    String jws = proof(key(), "agent-reg-pop+jwt", "POST", URL, NOW);

    assertThat(verifier().verify(jws, ProofType.DPOP, "POST", URL)).isEmpty();
  }

  @Test
  void DPoP_를_등록_PoP_자리에_내면_거절한다() throws Exception {
    String jws = proof(key(), "dpop+jwt", "POST", URL, NOW);

    assertThat(verifier().verify(jws, ProofType.REGISTRATION, "POST", URL)).isEmpty();
  }

  @Test
  void 같은_proof_를_두_번_쓰면_거절한다() throws Exception {
    String jws = proof(key(), "dpop+jwt", "POST", URL, NOW);
    JwsProofVerifier verifier = verifier();

    assertThat(verifier.verify(jws, ProofType.DPOP, "POST", URL)).isPresent();
    assertThat(verifier.verify(jws, ProofType.DPOP, "POST", URL)).isEmpty();
  }

  @Test
  void 시계_오차를_벗어난_proof_는_거절한다() throws Exception {
    String jws = proof(key(), "dpop+jwt", "POST", URL, NOW.minus(Duration.ofMinutes(10)));

    assertThat(verifier().verify(jws, ProofType.DPOP, "POST", URL)).isEmpty();
  }

  @Test
  void 다른_URL_로_만든_proof_는_거절한다() throws Exception {
    String jws = proof(key(), "dpop+jwt", "POST", "https://example.test/other", NOW);

    assertThat(verifier().verify(jws, ProofType.DPOP, "POST", URL)).isEmpty();
  }
}
```

- [ ] **Step 2: 실패 확인**

```bash
cd server && ./gradlew test --tests '*JwsProofVerifierTest*'
```
Expected: 컴파일 실패.

- [ ] **Step 3: ProofType 작성**

```java
package dev.starryeye.agentidentity.proof;

/**
 * proof 의 용도. **`typ` 이 다른 것이 핵심이다.**
 *
 * RFC 9449 는 DPoP proof 의 `typ` 을 `dpop+jwt` 로 강제한다. 등록 PoP 가 같은 `typ` 을 쓰면
 * 한쪽에서 얻은 proof 를 다른 쪽에 재생할 수 있다. 등록 PoP 는 신원이 없는 상태에서,
 * 런타임 DPoP 는 신원이 확정된 뒤에 오므로 서로 통과시키면 안 된다.
 */
public enum ProofType {
  REGISTRATION("agent-reg-pop+jwt"),
  DPOP("dpop+jwt");

  private final String typ;

  ProofType(String typ) { this.typ = typ; }

  public String typ() { return typ; }
}
```

- [ ] **Step 4: 검증기 구현**

```java
package dev.starryeye.agentidentity.proof;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * JWS 형태의 소유 증명을 검증한다. 등록 PoP 와 런타임 DPoP 가 **파싱·서명검증을 공유하고
 * 수용 조건은 분리**한다.
 *
 * `jti` 를 기억해 재생을 막는다. 허용 창의 두 배 동안 보관하며, 그 뒤에는 `iat` 검사가
 * 재생을 막으므로 지워도 된다.
 */
@Component
public class JwsProofVerifier {

  private final Map<String, Instant> seenJti = new ConcurrentHashMap<>();
  private final Clock clock;
  private final Duration skew;

  public JwsProofVerifier(Clock clock, Duration skew) {
    this.clock = clock;
    this.skew = skew;
  }

  public JwsProofVerifier(Clock clock) {
    this(clock, Duration.ofSeconds(60));
  }

  /** 통과하면 서명한 키의 RFC 7638 지문을 돌려준다. */
  public Optional<String> verify(String jws, ProofType expected, String method, String url) {
    try {
      SignedJWT jwt = SignedJWT.parse(jws);

      if (jwt.getHeader().getType() == null
          || !expected.typ().equals(jwt.getHeader().getType().toString())) {
        return Optional.empty();
      }

      ECKey jwk = (ECKey) jwt.getHeader().getJWK();
      if (jwk == null || !jwt.verify(new ECDSAVerifier(jwk))) {
        return Optional.empty();
      }

      var claims = jwt.getJWTClaimsSet();
      if (!method.equals(claims.getStringClaim("htm")) || !url.equals(claims.getStringClaim("htu"))) {
        return Optional.empty();
      }

      Instant issuedAt = claims.getIssueTime() == null ? null : claims.getIssueTime().toInstant();
      Instant now = clock.instant();
      if (issuedAt == null || Duration.between(issuedAt, now).abs().compareTo(skew) > 0) {
        return Optional.empty();
      }

      String jti = claims.getJWTID();
      if (jti == null) {
        return Optional.empty();
      }
      evictExpired(now);
      if (seenJti.putIfAbsent(jti, now) != null) {
        return Optional.empty();
      }

      return Optional.of(jwk.computeThumbprint().toString());
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private void evictExpired(Instant now) {
    Instant cutoff = now.minus(skew.multipliedBy(2));
    seenJti.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
  }
}
```

- [ ] **Step 5: 자격증명 발급기 작성**

```java
package dev.starryeye.agentidentity.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.starryeye.agentidentity.policy.PolicyProperties;
import java.time.Clock;
import java.util.Date;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 자격증명(JWT)을 발급한다.
 *
 * `cnf.jkt` 로 에이전트 키에 묶으므로, 토큰만 탈취해도 키 없이는 쓸 수 없다.
 * `aud` 는 리소스 서버가 하나뿐인 지금도 넣는다 — 둘 이상이 되는 순간 A용 자격증명을 B에
 * 제시하는 혼동이 생기고, 그때 모양을 바꾸면 이미 발급된 토큰과 호환이 깨진다.
 *
 * 서명 키는 기동 시 생성한다. 키 관리는 이 사이클의 범위 밖이며, 서버를 재시작하면 이전
 * 자격증명은 무효가 된다.
 */
@Component
public class CredentialIssuer {

  private final ECKey signingKey;
  private final PolicyProperties properties;
  private final Clock clock;

  public CredentialIssuer(PolicyProperties properties, Clock clock) throws Exception {
    this.signingKey = new ECKeyGenerator(Curve.P_256).keyID("server").generate();
    this.properties = properties;
    this.clock = clock;
  }

  public String issue(AgentIdentity identity) {
    try {
      var now = clock.instant();
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(signingKey.getKeyID()).build(),
              new JWTClaimsSet.Builder()
                  .issuer("https://agent-identity.local")
                  .audience("https://agent-identity.local/resource")
                  .subject(identity.getId())
                  .issueTime(Date.from(now))
                  .expirationTime(Date.from(now.plus(properties.getCredentialTtl())))
                  .claim("cnf", Map.of("jkt", identity.getJwkThumbprint()))
                  .build());
      jwt.sign(new ECDSASigner(signingKey));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException("자격증명을 발급하지 못했다", e);
    }
  }
}
```

- [ ] **Step 6: 컨트롤러 두 개 작성**

`RegistrationController.java`:

```java
package dev.starryeye.agentidentity.api;

import dev.starryeye.agentidentity.identity.CredentialIssuer;
import dev.starryeye.agentidentity.registration.ChallengeStore;
import dev.starryeye.agentidentity.registration.RegistrationService;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent/registration")
public class RegistrationController {

  public record RegistrationRequest(
      String registrationId,
      List<String> attestationChain,
      String pop,
      String deviceBinding,
      String playIntegrityToken) {}

  private final ChallengeStore challenges;
  private final RegistrationService registration;
  private final CredentialIssuer credentials;

  public RegistrationController(
      ChallengeStore challenges,
      RegistrationService registration,
      CredentialIssuer credentials) {
    this.challenges = challenges;
    this.registration = registration;
    this.credentials = credentials;
  }

  @PostMapping("/challenge")
  public Map<String, Object> challenge() {
    var issued = challenges.issue();
    return Map.of(
        "registrationId", issued.registrationId(),
        "challenge", ChallengeStore.encode(issued.value()),
        "expiresIn", 300);
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> register(@RequestBody RegistrationRequest request)
      throws Exception {
    var outcome =
        registration.register(
            request.registrationId(),
            parseChain(request.attestationChain()),
            request.deviceBinding(),
            request.playIntegrityToken());

    if (!outcome.isAccepted()) {
      return ResponseEntity.status(403).body(Map.of("reason", outcome.reason().name()));
    }
    return ResponseEntity.ok(
        Map.of(
            "agentId", outcome.identity().getId(),
            "credential", credentials.issue(outcome.identity()),
            "expiresIn", 900));
  }

  private static List<X509Certificate> parseChain(List<String> encoded) throws Exception {
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    List<X509Certificate> chain = new ArrayList<>();
    for (String der : encoded) {
      chain.add(
          (X509Certificate)
              factory.generateCertificate(
                  new ByteArrayInputStream(Base64.getDecoder().decode(der))));
    }
    return chain;
  }
}
```

`CredentialController.java`:

```java
package dev.starryeye.agentidentity.api;

import dev.starryeye.agentidentity.identity.AgentIdentity;
import dev.starryeye.agentidentity.identity.AgentIdentityRepository;
import dev.starryeye.agentidentity.identity.CredentialIssuer;
import dev.starryeye.agentidentity.policy.PolicyProperties;
import dev.starryeye.agentidentity.policy.RejectionReason;
import dev.starryeye.agentidentity.proof.JwsProofVerifier;
import dev.starryeye.agentidentity.proof.ProofType;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자격증명 갱신과 신원 확인.
 *
 * 갱신은 attestation 을 다시 하지 않는다 — 등록 때 검증했고, 그 키를 지금 쥐고 있다는 사실이
 * proof 로 증명된다. 다만 무기한은 아니다(`max-attestation-age`).
 */
@RestController
public class CredentialController {

  private final JwsProofVerifier proofs;
  private final AgentIdentityRepository repository;
  private final CredentialIssuer credentials;
  private final PolicyProperties properties;
  private final Clock clock;

  public CredentialController(
      JwsProofVerifier proofs,
      AgentIdentityRepository repository,
      CredentialIssuer credentials,
      PolicyProperties properties,
      Clock clock) {
    this.proofs = proofs;
    this.repository = repository;
    this.credentials = credentials;
    this.properties = properties;
    this.clock = clock;
  }

  @PostMapping("/agent/credential")
  public ResponseEntity<Map<String, Object>> refresh(
      @RequestHeader("DPoP") String proof, HttpServletRequest request) {

    Optional<AgentIdentity> found = authenticate(proof, "POST", request);
    if (found.isEmpty()) {
      return ResponseEntity.status(401).body(Map.of("reason", RejectionReason.DPOP_INVALID.name()));
    }
    AgentIdentity identity = found.get();

    Duration age = Duration.between(identity.getLastAttestedAt(), clock.instant());
    if (age.compareTo(properties.getMaxAttestationAge()) > 0) {
      return ResponseEntity.status(401)
          .body(Map.of("reason", RejectionReason.REATTESTATION_REQUIRED.name()));
    }

    identity.markAuthenticated(clock.instant());
    repository.save(identity);
    return ResponseEntity.ok(
        Map.of(
            "agentId", identity.getId(),
            "credential", credentials.issue(identity),
            "expiresIn", 900));
  }

  @GetMapping("/agent/whoami")
  public ResponseEntity<Map<String, Object>> whoami(
      @RequestHeader("DPoP") String proof, HttpServletRequest request) {

    return authenticate(proof, "GET", request)
        .<ResponseEntity<Map<String, Object>>>map(
            identity -> ResponseEntity.ok(Map.of("agentId", identity.getId())))
        .orElseGet(
            () ->
                ResponseEntity.status(401)
                    .body(Map.of("reason", RejectionReason.DPOP_INVALID.name())));
  }

  private Optional<AgentIdentity> authenticate(
      String proof, String method, HttpServletRequest request) {
    return proofs
        .verify(proof, ProofType.DPOP, method, request.getRequestURL().toString())
        .flatMap(repository::findByJwkThumbprint)
        .filter(identity -> "ACTIVE".equals(identity.getStatus()));
  }
}
```

- [ ] **Step 7: `JwsProofVerifier` 빈 생성자 정리**

Spring이 `Duration`을 주입하지 못하므로, 단일 인자 생성자에 `@org.springframework.beans.factory.annotation.Autowired`를 붙이거나 설정에서 빈을 만든다. `ServerApplication`에 추가한다.

```java
  @org.springframework.context.annotation.Bean
  public dev.starryeye.agentidentity.proof.JwsProofVerifier jwsProofVerifier(java.time.Clock clock) {
    return new dev.starryeye.agentidentity.proof.JwsProofVerifier(
        clock, java.time.Duration.ofSeconds(60));
  }
```

그리고 `JwsProofVerifier`의 `@Component`를 제거한다(빈이 두 번 만들어지지 않게).

- [ ] **Step 8: 전체 테스트 통과 확인**

```bash
cd server && ./gradlew test
```
Expected: 전부 통과.

- [ ] **Step 9: 커밋 & 푸시**

```bash
git add -A && git commit -m "feat(server): 소유 증명 검증과 자격증명 발급/갱신" && git push
```

---

## Task 6: 안드로이드 클라이언트

앱 최초 실행 시 **대화 없이** 등록한다. 툴은 추가하지 않는다 — 툴이 자격증명을 들고 나가는
것은 에이전트가 사용자를 대신할 때(②·③번)이지 신원을 갖는 단계가 아니다.

**Files:**
- Create: `android/app/src/main/kotlin/dev/starryeye/ondeviceagent/identity/EcdsaSignature.kt`
- Create: `.../identity/AgentKeyStore.kt`, `.../identity/JwsProofSigner.kt`
- Create: `.../identity/DeviceBindingProvider.kt`, `.../identity/IntegrityTokenProvider.kt`
- Create: `.../identity/AgentRegistrar.kt`, `.../identity/AgentIdentityState.kt`
- Modify: `.../AgentViewModel.kt`
- Modify: `android/app/src/main/AndroidManifest.xml` (평문 HTTP 허용 — 개발용)
- Test: `android/app/src/test/kotlin/dev/starryeye/ondeviceagent/identity/EcdsaSignatureTest.kt`

**Interfaces:**
- Consumes: 서버의 `/agent/registration/challenge`, `/agent/registration`
- Produces: `AgentIdentityState` — `Registering` / `Registered(agentId)` / `Failed(reason)`

- [ ] **Step 1: 실패하는 테스트 작성**

ECDSA 서명은 JCA가 DER로 내주는데 JOSE는 `R‖S` 원시 형식을 요구한다. 여기서 틀리면 서버가
서명을 거부하는데 원인을 찾기 어렵다. 순수 함수라 기기 없이 테스트된다.

```kotlin
package dev.starryeye.ondeviceagent.identity

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EcdsaSignatureTest {

  @Test
  fun `DER 서명을 JOSE 64바이트로 바꾼다`() {
    val keyPair =
      KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()
    val der =
      Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private)
        update("payload".toByteArray())
        sign()
      }

    val jose = EcdsaSignature.derToJose(der)

    assertEquals(64, jose.size)
  }

  @Test
  fun `앞자리가 0인 값도 32바이트로 왼쪽 패딩한다`() {
    // DER 은 선행 0 을 생략하므로, 그대로 이어붙이면 64바이트가 안 되거나 자리가 밀린다.
    val r = BigInteger("1")
    val s = BigInteger("2")
    val der = EcdsaSignature.joseToDerForTest(r, s)

    val jose = EcdsaSignature.derToJose(der)

    assertEquals(64, jose.size)
    assertArrayEquals(ByteArray(31) + 1, jose.copyOfRange(0, 32))
    assertArrayEquals(ByteArray(31) + 2, jose.copyOfRange(32, 64))
  }
}
```

- [ ] **Step 2: 실패 확인**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests '*EcdsaSignatureTest*'
```
Expected: 컴파일 실패.

- [ ] **Step 3: 변환 구현**

```kotlin
package dev.starryeye.ondeviceagent.identity

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * ECDSA 서명 형식 변환.
 *
 * JCA 는 DER(SEQUENCE{r,s})로 서명을 내주는데 JOSE(ES256)는 고정 길이 `R‖S` 를 요구한다.
 * DER 은 선행 0 을 생략하므로 단순히 이어붙이면 길이가 어긋난다. 여기서 틀리면 서버가 서명을
 * 거부하는데, 증상이 "서명 불일치"뿐이라 원인을 찾기 어렵다.
 */
object EcdsaSignature {

  private const val COORDINATE_BYTES = 32

  fun derToJose(der: ByteArray): ByteArray {
    var offset = 2 // SEQUENCE 태그와 길이
    if (der[1].toInt() and 0x80 != 0) offset += der[1].toInt() and 0x7f

    require(der[offset].toInt() == 0x02) { "DER 형식이 아니다" }
    val rLength = der[offset + 1].toInt()
    val r = BigInteger(der.copyOfRange(offset + 2, offset + 2 + rLength))

    val sOffset = offset + 2 + rLength
    require(der[sOffset].toInt() == 0x02) { "DER 형식이 아니다" }
    val sLength = der[sOffset + 1].toInt()
    val s = BigInteger(der.copyOfRange(sOffset + 2, sOffset + 2 + sLength))

    return toFixed(r) + toFixed(s)
  }

  private fun toFixed(value: BigInteger): ByteArray {
    val bytes = value.toByteArray()
    val trimmed = if (bytes.size > COORDINATE_BYTES) bytes.copyOfRange(bytes.size - COORDINATE_BYTES, bytes.size) else bytes
    return ByteArray(COORDINATE_BYTES - trimmed.size) + trimmed
  }

  /** 테스트가 알려진 r·s 로 DER 을 만들기 위한 도우미. */
  internal fun joseToDerForTest(r: BigInteger, s: BigInteger): ByteArray {
    fun integer(value: BigInteger): ByteArray {
      val bytes = value.toByteArray()
      return byteArrayOf(0x02, bytes.size.toByte()) + bytes
    }
    val body = integer(r) + integer(s)
    return ByteArrayOutputStream().apply {
      write(0x30); write(body.size); write(body)
    }.toByteArray()
  }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests '*EcdsaSignatureTest*'
```
Expected: 2개 통과.

- [ ] **Step 5: 키 저장소 작성**

```kotlin
package dev.starryeye.ondeviceagent.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * 에이전트의 하드웨어 키. 개인키는 어떤 경로로도 앱에 노출되지 않는다.
 *
 * StrongBox 를 먼저 시도하고 없으면 TEE 로 내려간다. **어느 쪽인지 클라이언트가 주장하지
 * 않는다** — attestation 이 하드웨어 서명으로 알려 주고, 판단은 서버가 한다.
 */
class AgentKeyStore(private val alias: String = "agent-identity-key") {

  private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

  fun hasKey(): Boolean = keyStore.containsAlias(alias)

  /** [challenge] 를 attestation challenge 로 넣어 키를 만들고 체인을 돌려준다. */
  fun createKey(challenge: ByteArray): List<X509Certificate> {
    if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    for (strongBox in listOf(true, false)) {
      try {
        generate(challenge, strongBox)
        return chain()
      } catch (e: StrongBoxUnavailableException) {
        if (!strongBox) throw e
      }
    }
    error("키를 만들지 못했다")
  }

  fun chain(): List<X509Certificate> =
    keyStore.getCertificateChain(alias).map { it as X509Certificate }

  fun publicKey(): ECPublicKey = chain().first().publicKey as ECPublicKey

  /** JOSE 형식(R‖S)으로 서명한다. */
  fun sign(payload: ByteArray): ByteArray {
    val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    val der =
      Signature.getInstance("SHA256withECDSA").run {
        initSign(entry.privateKey)
        update(payload)
        sign()
      }
    return EcdsaSignature.derToJose(der)
  }

  private fun generate(challenge: ByteArray, strongBox: Boolean) {
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
      initialize(
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
          .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
          .setDigests(KeyProperties.DIGEST_SHA256)
          .setAttestationChallenge(challenge)
          .apply { if (strongBox) setIsStrongBoxBacked(true) }
          .build()
      )
      generateKeyPair()
    }
  }
}
```

- [ ] **Step 6: proof 서명기와 이음매 둘 작성**

```kotlin
package dev.starryeye.ondeviceagent.identity

import java.math.BigInteger
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/**
 * 등록 PoP 와 런타임 DPoP 를 만든다. **`typ` 이 다르다** — 같은 형태를 쓰되 서로의 자리에서
 * 통과하면 안 되기 때문이다(서버의 ProofType 과 짝을 이룬다).
 */
class JwsProofSigner(private val keys: AgentKeyStore) {

  fun registrationPop(url: String, challenge: String): String =
    sign("agent-reg-pop+jwt", "POST", url, mapOf("challenge" to challenge))

  fun dpop(method: String, url: String): String = sign("dpop+jwt", method, url, emptyMap())

  private fun sign(
    typ: String,
    method: String,
    url: String,
    extraClaims: Map<String, String>,
  ): String {
    val key = keys.publicKey()
    val header =
      JSONObject()
        .put("alg", "ES256")
        .put("typ", typ)
        .put("jwk", publicJwk(key))
        .toString()
    val payload =
      JSONObject()
        .put("htm", method)
        .put("htu", url)
        .put("jti", UUID.randomUUID().toString())
        .put("iat", System.currentTimeMillis() / 1000)
        .apply { extraClaims.forEach { (name, value) -> put(name, value) } }
        .toString()

    val signingInput = "${encode(header.toByteArray())}.${encode(payload.toByteArray())}"
    return "$signingInput.${encode(keys.sign(signingInput.toByteArray()))}"
  }

  private fun publicJwk(key: ECPublicKey): JSONObject =
    JSONObject()
      .put("kty", "EC")
      .put("crv", "P-256")
      .put("x", encode(coordinate(key.w.affineX)))
      .put("y", encode(coordinate(key.w.affineY)))

  private fun coordinate(value: BigInteger): ByteArray {
    val bytes = value.toByteArray()
    val trimmed = if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size) else bytes
    return ByteArray(32 - trimmed.size) + trimmed
  }

  private fun encode(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
```

```kotlin
package dev.starryeye.ondeviceagent.identity

/**
 * 기기 자체를 가리키는 증거. 1st-party 경로(Device ID / Knox attestation)가 열리면 구현이
 * 들어온다. 소매 기기에서는 얻을 수 없다.
 */
fun interface DeviceBindingProvider {
  suspend fun deviceBinding(): String?
}

/** 지금 유일한 구현. */
object NoDeviceBinding : DeviceBindingProvider {
  override suspend fun deviceBinding(): String? = null
}
```

```kotlin
package dev.starryeye.ondeviceagent.identity

/** 실행 환경 무결성 증거. 신원이 아니라 정책의 보조 재료다. */
fun interface IntegrityTokenProvider {
  suspend fun integrityToken(): String?
}

/** 지금 유일한 구현. Play Integrity 연동은 이 사이클의 범위 밖이다. */
object NoIntegrityToken : IntegrityTokenProvider {
  override suspend fun integrityToken(): String? = null
}
```

- [ ] **Step 7: 등록 흐름과 상태 작성**

```kotlin
package dev.starryeye.ondeviceagent.identity

/** 에이전트 신원의 현재 상태. 화면에 시스템 줄로 표시된다. */
sealed interface AgentIdentityState {
  data object Registering : AgentIdentityState
  data class Registered(val agentId: String) : AgentIdentityState
  /** [reason] 은 서버의 사유 코드. 재시도가 무의미한 사유는 반복하지 않는다. */
  data class Failed(val reason: String) : AgentIdentityState
}
```

```kotlin
package dev.starryeye.ondeviceagent.identity

import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 등록 흐름. challenge → 키 생성 → 체인 제출 → 신원 수령.
 *
 * 사용자와 무관하게 앱 최초 실행 시 자동으로 일어난다. 대화도 모델도 관여하지 않는다.
 */
class AgentRegistrar(
  private val baseUrl: String,
  private val keys: AgentKeyStore,
  private val proofs: JwsProofSigner,
  private val deviceBinding: DeviceBindingProvider = NoDeviceBinding,
  private val integrity: IntegrityTokenProvider = NoIntegrityToken,
) {

  suspend fun register(): AgentIdentityState = withContext(Dispatchers.IO) {
    try {
      val challengeResponse = JSONObject(post("$baseUrl/agent/registration/challenge", null))
      val registrationId = challengeResponse.getString("registrationId")
      val challenge = challengeResponse.getString("challenge")

      val chain = keys.createKey(Base64.getUrlDecoder().decode(challenge))

      val body =
        JSONObject()
          .put("registrationId", registrationId)
          .put(
            "attestationChain",
            JSONArray(chain.map { Base64.getEncoder().encodeToString(it.encoded) }),
          )
          .put("pop", proofs.registrationPop("$baseUrl/agent/registration", challenge))
          .put("deviceBinding", deviceBinding.deviceBinding())
          .put("playIntegrityToken", integrity.integrityToken())

      val response = JSONObject(post("$baseUrl/agent/registration", body.toString()))
      AgentIdentityState.Registered(response.getString("agentId"))
    } catch (e: RegistrationRejected) {
      AgentIdentityState.Failed(e.reason)
    } catch (e: Exception) {
      AgentIdentityState.Failed(e.message ?: e::class.simpleName ?: "unknown")
    }
  }

  private class RegistrationRejected(val reason: String) : Exception(reason)

  private fun post(url: String, body: String?): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "POST"
      connection.connectTimeout = 10_000
      connection.readTimeout = 20_000
      if (body != null) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
      }
      if (connection.responseCode !in 200..299) {
        val error = connection.errorStream?.bufferedReader()?.readText().orEmpty()
        val reason =
          runCatching { JSONObject(error).getString("reason") }.getOrDefault("HTTP ${connection.responseCode}")
        throw RegistrationRejected(reason)
      }
      return connection.inputStream.bufferedReader().readText()
    } finally {
      connection.disconnect()
    }
  }
}
```

- [ ] **Step 7.5: 자격증명을 실제로 쓰는 경로 추가**

등록만으로는 스펙 완료 기준 3(`whoami` 가 DPoP 검증을 통과)과 5(attestation 없이 갱신)를
확인할 수 없다. 등록 직후 두 호출을 한 번씩 한다.

`AgentRegistrar` 에 추가한다.

```kotlin
  /** 발급받은 자격증명이 실제로 통하는지 확인한다. 완료 기준 3. */
  suspend fun whoami(): String = withContext(Dispatchers.IO) {
    val url = "$baseUrl/agent/whoami"
    JSONObject(send("GET", url, null, proofs.dpop("GET", url))).getString("agentId")
  }

  /** attestation 없이 자격증명을 새로 받는다. 완료 기준 5. */
  suspend fun refreshCredential(): String = withContext(Dispatchers.IO) {
    val url = "$baseUrl/agent/credential"
    JSONObject(send("POST", url, "", proofs.dpop("POST", url))).getString("credential")
  }
```

그리고 `post` 를 메서드·헤더를 받는 `send` 로 일반화한다.

```kotlin
  private fun send(method: String, url: String, body: String?, dpop: String? = null): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = method
      connection.connectTimeout = 10_000
      connection.readTimeout = 20_000
      dpop?.let { connection.setRequestProperty("DPoP", it) }
      if (body != null) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
      }
      if (connection.responseCode !in 200..299) {
        val error = connection.errorStream?.bufferedReader()?.readText().orEmpty()
        val reason =
          runCatching { JSONObject(error).getString("reason") }
            .getOrDefault("HTTP ${connection.responseCode}")
        throw RegistrationRejected(reason)
      }
      return connection.inputStream.bufferedReader().readText()
    } finally {
      connection.disconnect()
    }
  }
```

기존 `post(url, body)` 호출부는 `send("POST", url, body)` 로 바꾼다.

**DPoP 가 거절되면 `htu` 불일치를 먼저 의심한다.** 클라이언트는 `http://127.0.0.1:8080/...` 로
서명하는데 서버는 `HttpServletRequest.getRequestURL()` 로 비교한다. Host 헤더가 그대로면
일치하지만, 프록시나 포트 포워딩이 끼면 어긋난다.

- [ ] **Step 8: ViewModel 배선**

`AgentViewModel.kt`의 `init` 블록에서 모델 로드와 **나란히** 등록을 시작한다. 등록 실패가
채팅을 막지 않는다 — 이번 사이클에는 자격증명을 쓰는 툴이 없기 때문이다.

```kotlin
  // init 블록 안, 기존 모델 확보 코루틴과 별개로 시작한다.
  viewModelScope.launch {
    val registrar =
      AgentRegistrar(
        baseUrl = "http://127.0.0.1:8080",
        keys = AgentKeyStore(),
        proofs = JwsProofSigner(AgentKeyStore()),
      )
    when (val state = registrar.register()) {
      is AgentIdentityState.Registered -> {
        addSystem("에이전트 신원: ${state.agentId}")
        // 발급만으로는 자격증명이 통하는지 모른다. 한 번씩 실제로 써 본다.
        runCatching { registrar.whoami() }
          .onSuccess { addSystem("서버가 확인한 신원: $it") }
          .onFailure { addSystem("신원 확인 실패: ${it.message}") }
        runCatching { registrar.refreshCredential() }
          .onSuccess { addSystem("자격증명 갱신 성공 (attestation 없이)") }
          .onFailure { addSystem("자격증명 갱신 실패: ${it.message}") }
      }
      is AgentIdentityState.Failed -> addSystem("신원 등록 실패: ${state.reason}")
      AgentIdentityState.Registering -> Unit
    }
  }
```

`127.0.0.1:8080`은 `adb reverse tcp:8080 tcp:8080`으로 맥의 서버에 닿는다.

- [ ] **Step 9: 매니페스트에 평문 HTTP 허용**

개발용이다. `application` 요소에 추가한다.

```xml
        android:usesCleartextTraffic="true"
```

- [ ] **Step 10: 빌드와 기존 테스트 확인**

```bash
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, 기존 9개 + 신규 2개 = 11개 통과.

- [ ] **Step 11: 커밋 & 푸시**

```bash
git add -A && git commit -m "feat(android): 에이전트 신원 등록 클라이언트" && git push
```

---

## Task 7: 실기 검증과 정책 실험

**Files:**
- Modify: `android/README.md`, `server/README.md`, 루트 `README.md`

**Interfaces:**
- Consumes: Task 1~6 전부
- Produces: 검증된 시스템과 재현 절차

- [ ] **Step 1: 서버 기동과 포트 연결**

```bash
cd server && ./gradlew bootRun &
adb reverse tcp:8080 tcp:8080
```

- [ ] **Step 2: 앱 설치와 첫 실행 — 완료 기준 2**

```bash
cd android && ./gradlew :app:installDebug
adb shell am start -n dev.starryeye.ondeviceagent/.MainActivity
```

30초쯤 뒤 `adb exec-out screencap -p > /tmp/identity-1.png` 로 화면을 찍어 **읽는다.**
Expected: `에이전트 신원: urn:samsung:agent:galaxy-personal-agent:...` 시스템 줄이 보인다.

보이지 않으면 `adb logcat`과 서버 로그를 함께 확인하고, 사유 코드를 그대로 보고한다.

- [ ] **Step 2.5: 자격증명이 실제로 통하는지 — 완료 기준 3·5**

Step 2 의 같은 화면에서 이어지는 두 줄을 확인한다.

Expected:
- `서버가 확인한 신원: urn:samsung:agent:...` — Step 2 의 agentId 와 **같아야** 한다
- `자격증명 갱신 성공 (attestation 없이)`

`신원 확인 실패: DPOP_INVALID` 가 나오면 `htu` 불일치를 먼저 의심한다(Task 6 Step 7.5 참고).

- [ ] **Step 3: 신원 유지 확인 — 완료 기준 4**

앱을 강제 종료하고 다시 켠다.

```bash
adb shell am force-stop dev.starryeye.ondeviceagent
adb shell am start -n dev.starryeye.ondeviceagent/.MainActivity
```

Expected: **같은 agentId**가 표시된다. 다르면 멱등성이 깨진 것이다.

- [ ] **Step 4: StrongBox 정책 실험 — 완료 기준 6**

`server/src/main/resources/application.yml`에 정책을 넣고 서버를 재시작한다.

```yaml
agent-registration:
  require-security-level: STRONGBOX
  allowed-packages: [dev.starryeye.ondeviceagent]
```

앱 데이터를 지우고(키를 새로 만들게) 다시 실행한다.

```bash
adb shell pm clear dev.starryeye.ondeviceagent
adb shell am start -n dev.starryeye.ondeviceagent/.MainActivity
```

Expected: `신원 등록 실패: POLICY_SECURITY_LEVEL`. A36은 TEE 전용이므로 거절되는 것이 정상이다.

**주의:** `pm clear`는 앱의 외부 저장소도 지운다. 모델 파일이 사라지므로 이후
`adb push ~/.litertlm-models/gemma-4-E2B-it.litertlm /sdcard/Android/data/dev.starryeye.ondeviceagent/files/`
로 되돌린다.

- [ ] **Step 5: 기기 증명 정책 실험 — 완료 기준 7**

```yaml
agent-registration:
  require-security-level: TRUSTED_ENVIRONMENT
  require-device-binding: true
```

Expected: `신원 등록 실패: POLICY_DEVICE_BINDING`. 소매 기기에서는 기기 증명을 얻을 수 없다.

- [ ] **Step 6: 정책을 되돌리고 통과 확인**

```yaml
agent-registration:
  require-security-level: TRUSTED_ENVIRONMENT
  require-device-binding: false
```

Expected: 다시 등록에 성공한다.

- [ ] **Step 7: 관찰 결과를 문서에 기록**

`server/README.md`에 "관찰된 결과" 절을 만들어 Step 2~6에서 **실제로 본 것**을 적는다 —
발급된 agentId 형식, 재시작 후 유지 여부, 두 정책에서의 사유 코드. 관찰하지 않은 것은 적지
않는다.

- [ ] **Step 8: 루트 README 갱신**

`server/`를 "예정"에서 "구현됨"으로 바꾸고, 이번 사이클이 3단계 중 ①이며 ②(사용자 위임)와
③(행동별 인가)이 남았음을 적는다.

- [ ] **Step 9: 최종 검증**

```bash
cd server && ./gradlew build
cd ../android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```
Expected: 양쪽 모두 `BUILD SUCCESSFUL`.

- [ ] **Step 10: 커밋 & 푸시**

```bash
git add -A && git commit -m "docs: 신원 등록 실기 검증 결과" && git push
```

- [ ] **Step 11: 결과 보고**

스펙 §11의 완료 기준 12개 각각에 대해 통과/실패/미도달을 **관찰한 출력과 함께** 보고한다.
자동 테스트로 덮은 것(8~12번 일부)과 실기로 확인한 것을 구분한다. 통과하지 못한 항목은
숨기지 않는다.
