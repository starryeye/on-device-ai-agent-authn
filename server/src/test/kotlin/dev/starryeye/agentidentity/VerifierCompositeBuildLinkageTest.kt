package dev.starryeye.agentidentity

import com.android.keyattestation.verifier.ConstraintConfig
import com.android.keyattestation.verifier.InstantSource
import com.android.keyattestation.verifier.Verifier
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 공식 검증 라이브러리가 컴포지트 빌드로 실제로 붙는지 증명한다.
 *
 * 이 라이브러리는 Maven Central 에 없고 group 좌표도 없어서 settings.gradle.kts 의 명시적
 * 치환에 의존한다. 그 배선이 깨지면 여기서 컴파일이 실패한다.
 *
 * 예전 이름(`VerifierLinkageTest`)과 주석은 "코틀린을 자바에서 쓰는 상호운용"을 증명한다고
 * 적혀 있었지만, 이 테스트 자체가 코틀린으로 짜여 있어 애초에 Java 상호운용을 검증한 적이
 * 없었다 — 실제 검증기는 지금 `AttestationVerifierTest` 등 다른 테스트들이 실제 체인으로
 * 종단 간 구동하고 있으므로, 이 파일이 증명하는 것은 딱 하나, 컴포지트 빌드 배선이 여전히
 * 붙어 있다는 것뿐이다. 그 사실에 맞춰 이름과 주석을 다시 붙였다.
 */
class VerifierCompositeBuildLinkageTest {

  @Test
  fun `컴포지트_빌드로_포함된_공식_검증기를_생성할_수_있다`() {
    val clock = InstantSource { Instant.parse("2026-08-28T00:00:00Z") }

    val verifier =
        Verifier(
            { emptySet() },
            { emptySet() },
            clock,
            ConstraintConfig.testDefault())

    assertThat(verifier).isNotNull()
  }
}
