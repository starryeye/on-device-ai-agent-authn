plugins {
  java
  kotlin("jvm") version "2.2.0"
  kotlin("plugin.spring") version "2.2.0"
  id("org.springframework.boot") version "4.1.1"
  id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.starryeye"
version = "0.1.0"

// Java 21: 공식 검증 라이브러리가 21 툴체인을 요구한다.
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
kotlin { jvmToolchain(21) }

// google() 는 서브모듈(keyattestation)의 전이 의존성(androidx.annotation 등) 해석에 필요하다.
// Gradle 컴포지트 빌드에서는 의존성을 해석하는 프로젝트(여기서는 루트)의 저장소만 쓰이고,
// 포함된 빌드가 자체적으로 선언한 저장소는 쓰이지 않기 때문이다.
repositories {
  mavenCentral()
  google()
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("com.nimbusds:nimbus-jose-jwt:10.9.1")
  implementation("com.android.keyattestation:keyattestation")
  // keyattestation 이 kotlin-stdlib 를 implementation 으로만 선언해 컴파일 클래스패스로
  // 전이되지 않는다. Verifier 생성자가 실제 코틀린 함수 타입(Function0)을 받으므로
  // Java 에서 람다로 넘기려면 이 타입이 컴파일 시점에 보여야 한다. 서브모듈 버전(2.2.0)에 맞춘다.
  implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
  // 같은 이유로: keyattestation 의 공개 API(VerificationResult.Success, ChallengeChecker)가
  // guava 의 ListenableFuture 와 protobuf 의 ByteString 을 직접 노출하는데, 서브모듈이 이
  // 둘도 implementation 으로만 선언해 전이되지 않는다. 서브모듈 버전에 맞춘다.
  implementation("com.google.guava:guava:33.5.0-jre")
  implementation("com.google.protobuf:protobuf-javalite:4.28.3")
  // AttestationConfiguration 이 구글 루트 목록 응답(JSON)을 파싱하는 데 쓴다. 손으로 짠
  // 문자열 스캔 대신 정식 JSON 파서를 쓰기 위함이다. 서브모듈도 이미 같은 버전을 쓰지만
  // implementation 으로만 선언해 전이되지 않으므로 직접 추가한다.
  implementation("com.google.code.gson:gson:2.11.0")
  runtimeOnly("com.h2database:h2")
  // Spring Data JPA 가 코틀린 엔티티(AgentIdentity)의 영속 생성자를 찾을 때 코틀린 리플렉션으로
  // 넘어간다(PreferredConstructorDiscoverer). 이 라이브러리가 없으면 컨텍스트 기동이
  // NoClassDefFoundError(kotlin/reflect/full/KClasses)로 죽는다. 서브모듈이 이미 고정한
  // 코틀린 버전(2.2.0)에 맞춘다.
  runtimeOnly("org.jetbrains.kotlin:kotlin-reflect:2.2.0")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  // AttestationVerifierTest 가 "신뢰 앵커와 무관한 자기서명 인증서"를 직접 만들어 실제
  // 경로 검증 실패(PathValidationFailure)를 재현하는 데 쓴다. JDK 는 인증서 생성 공개
  // API 가 없어서 필요하다. 서브모듈이 이미 쓰는 버전(1.78.1)에 맞춘다.
  testImplementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}

tasks.withType<Test> { useJUnitPlatform() }
