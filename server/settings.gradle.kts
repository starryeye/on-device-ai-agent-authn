plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0" }

rootProject.name = "agent-identity-server"

// 공식 Key Attestation 검증기. Maven Central 에 없고 group 좌표도 없어서
// 명시적 치환으로 붙인다. 서브모듈은 수정하지 않는다.
includeBuild("third_party/keyattestation") {
  dependencySubstitution {
    substitute(module("com.android.keyattestation:keyattestation")).using(project(":"))
  }
}
