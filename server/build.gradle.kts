plugins {
  java
  id("org.springframework.boot") version "4.1.1"
  id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.starryeye"
version = "0.1.0"

// Java 21: 공식 검증 라이브러리가 21 툴체인을 요구한다.
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

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
  runtimeOnly("com.h2database:h2")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> { useJUnitPlatform() }
