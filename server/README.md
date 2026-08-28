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
