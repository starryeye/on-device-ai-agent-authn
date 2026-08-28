package dev.starryeye.agentidentity.attestation;

import com.android.keyattestation.verifier.AttestationApplicationId;
import com.android.keyattestation.verifier.ConstraintConfig;
import com.android.keyattestation.verifier.ExtensionParsingException;
import com.android.keyattestation.verifier.InstantSource;
import com.android.keyattestation.verifier.KeyDescription;
import com.android.keyattestation.verifier.VerificationResult;
import com.android.keyattestation.verifier.Verifier;
import com.google.common.util.concurrent.Futures;
import com.google.protobuf.ByteString;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 구글 공식 검증기를 우리 도메인 타입 뒤로 감싼다.
 *
 * 직접 파싱하지 않는 이유는 체인 검증·루트 집합·RKP 유효기간·폐기 목록·확장 파싱이 모두
 * 놓치기 쉬운 영역이고, 우리가 새로 짜서 더 잘할 이유가 없기 때문이다.
 *
 * 이 클래스는 HTTP 도 JPA 도 모른다. 그래야 실기기 체인을 픽스처로 반복 검증할 수 있다.
 *
 * <p>{@link Verifier} 는 첫 {@link #verify} 호출 시점에 지연 생성한다. {@code Verifier} 의
 * {@code init} 블록은 {@code allowSoftwareRoot=false}(기본값)일 때 즉시
 * {@code trustAnchorsSource()} 를 호출해 순회하는데, 생성자에서 미리 만들어 버리면 스프링이
 * 기동 시점에 빈을 생성하면서 네트워크를 타게 되고, 오프라인에서 도는 나중 단계의
 * {@code @SpringBootTest} 가 깨진다.
 */
public class AttestationVerifier {

  private final TrustAnchorSource anchors;
  private final RevocationSource revocation;
  private final InstantSource clock;

  private volatile Verifier verifier;

  public AttestationVerifier(
      TrustAnchorSource anchors, RevocationSource revocation, InstantSource clock) {
    this.anchors = anchors;
    this.revocation = revocation;
    this.clock = clock;
  }

  private Verifier verifier() {
    Verifier local = verifier;
    if (local == null) {
      synchronized (this) {
        local = verifier;
        if (local == null) {
          local =
              new Verifier(anchors::anchors, revocation::revokedSerials, clock, new ConstraintConfig());
          verifier = local;
        }
      }
    }
    return local;
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
    VerificationResult result;
    try {
      result =
          verifier()
              .verify(chain, challenge -> Futures.immediateFuture(challenge.equals(expected)));
    } catch (Exception e) {
      // 신뢰 앵커가 비었거나(PKIXParameters), 소프트웨어 루트가 앵커로 들어왔거나
      // (Verifier 의 init 블록) 하면 라이브러리가 VerificationResult 가 아니라 예외를 던진다.
      // (코틀린은 checked exception 을 선언하지 않으므로 javac 는 특정 하위 타입을 잡는 걸
      // 허용하지 않는다 — 그래서 Exception 을 넓게 잡는다.) 검증 실패는 모두 거절로 접는다 —
      // 열어두면 검증 우회가 된다.
      return new AttestationResult.Rejected("verification threw: " + e);
    }

    if (!(result instanceof VerificationResult.Success success)) {
      return new AttestationResult.Rejected(result.getClass().getSimpleName());
    }

    // 앱 신원은 Success 에 담겨 오지 않는다. leaf 를 직접 파싱해 읽는다.
    // 이 값이 softwareEnforced 에 있다는 사실이 타입으로 드러난다.
    KeyDescription description;
    try {
      description = KeyDescription.parseFrom(chain.get(0));
    } catch (ExtensionParsingException e) {
      return new AttestationResult.Rejected("failed to parse key description: " + e.getMessage());
    }
    if (description == null) {
      return new AttestationResult.Rejected("no key description extension");
    }

    AttestationApplicationId application =
        description.getSoftwareEnforced().getAttestationApplicationId();
    if (application == null || application.getPackages().isEmpty()) {
      return new AttestationResult.Rejected("no attestationApplicationId");
    }

    String packageName = application.getPackages().iterator().next().getName();
    List<String> signingDigests =
        application.getSignatures().stream()
            .map(signature -> HexFormat.of().formatHex(signature.toByteArray()))
            .collect(Collectors.toList());

    return new AttestationResult.Verified(
        success.getPublicKey(),
        success.getChallenge().toByteArray(),
        success.getSecurityLevel().toString(),
        success.getVerifiedBootState().toString(),
        success.getDeviceLocked(),
        packageName,
        signingDigests);
  }
}
