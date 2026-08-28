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
            ConstraintConfig.Companion.testDefault());

    assertThat(verifier).isNotNull();
  }
}
