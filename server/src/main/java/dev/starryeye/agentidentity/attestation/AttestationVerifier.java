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
import java.security.InvalidAlgorithmParameterException;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger log = LoggerFactory.getLogger(AttestationVerifier.class);

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
          // anchorsOrThrow/revokedSerialsOrThrow 를 넘긴다 — 원본 조회기가 던지는 예외를
          // InfrastructureFailure 로 표시해 두면, verify() 의 catch 블록이 "우리 인프라가
          // 죽었다"와 "체인/설정이 잘못됐다"를 구분할 수 있다.
          local =
              new Verifier(
                  this::anchorsOrThrow, this::revokedSerialsOrThrow, clock, new ConstraintConfig());
          verifier = local;
        }
      }
    }
    return local;
  }

  private Set<TrustAnchor> anchorsOrThrow() {
    try {
      return anchors.anchors();
    } catch (Exception e) {
      throw new InfrastructureFailure(e);
    }
  }

  private Set<String> revokedSerialsOrThrow() {
    try {
      return revocation.revokedSerials();
    } catch (Exception e) {
      throw new InfrastructureFailure(e);
    }
  }

  /**
   * 신뢰 앵커/폐기 목록 조회기가 던진 예외임을 표시하는 마커. {@code trustAnchorsSource()} 와
   * {@code revokedSerialsSource()} 는 {@link Verifier} 가 검증마다(그리고 최초 생성 시) 직접
   * 호출하므로, 이 예외로 감싸 두지 않으면 체인 자체의 문제(경로 검증 실패 등)와 구분할 방법이
   * 없다.
   */
  private static final class InfrastructureFailure extends RuntimeException {
    InfrastructureFailure(Throwable cause) {
      super(cause);
    }
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
      // 이 try 는 Verifier 생성(지연 생성 시)과 라이브러리의 verify() 호출만 감싼다. 이
      // 범위 안에서 예외가 나오는 경우는 세 가지뿐이다: (1) 신뢰 앵커/폐기 목록 조회기가
      // 던진 것(anchorsOrThrow/revokedSerialsOrThrow 가 InfrastructureFailure 로 표시),
      // (2) 신뢰 앵커 집합이 비었거나(PKIXParameters) 소프트웨어 루트가 앵커로 들어온
      // 설정 오류, (3) 그 외 — 체인 자체가 이상해서 나는 것으로 본다. 이 아래
      // KeyDescription.parseFrom 재파싱은 **의도적으로 이 catch 밖에 둔다** — 여기서 나는
      // 예외는 이미 검증을 통과한 체인을 우리가 다시 읽다가 나는 것이므로, 진짜 버그라면
      // 거절로 위장하지 말고 그대로 튀어야 한다.
      result =
          verifier()
              .verify(chain, challenge -> Futures.immediateFuture(challenge.equals(expected)));
    } catch (Exception e) {
      if (e instanceof InfrastructureFailure infra) {
        // 구글 루트/폐기 목록 조회 실패. 공격이 아니라 우리 쪽(또는 구글 쪽) 장애다 —
        // 반드시 시끄럽게 알려야 한다. fail-closed 는 맞는 동작이지만, 조용히 거절만
        // 하면 이게 공격 시도인지 장애인지 운영자가 구분할 수 없다.
        log.error("attestation 신뢰 앵커/폐기 목록 조회 실패 — 등록을 거절한다", infra.getCause());
        return new AttestationResult.Rejected("infrastructure failure: " + infra.getCause());
      }
      if (e instanceof InvalidAlgorithmParameterException || e instanceof IllegalArgumentException) {
        // 신뢰 앵커가 비었거나(PKIXParameters), 소프트웨어 루트가 앵커로 들어왔다
        // (Verifier 의 init 블록). 둘 다 배포 설정 문제이지 공격이 아니다.
        log.error("attestation 검증기 설정 오류 — 등록을 거절한다", e);
        return new AttestationResult.Rejected("configuration error: " + e);
      }
      // 그 외는 체인 자체가 이상해서 나는 것으로 본다. 공격/오작동 기기가 매일 만들어낼
      // 수 있는 잡음이므로 ERROR 가 아니라 DEBUG 로 남긴다.
      log.debug("attestation chain verification threw", e);
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
    if (application.getPackages().size() > 1) {
      // 공유 UID 앱은 패키지 여러 개를 하나의 attestationApplicationId 에 묶어 넣을 수
      // 있다. 하나만 골라서 넘기면, 정책이 그 이름 하나로 exact-match 할 때 실제로는
      // 같이 설치된 다른 패키지의 키로도 통과시켜 버리는 셈이 된다 — 조용히 고르지 않고
      // 명시적으로 거절한다.
      return new AttestationResult.Rejected("ambiguous attestationApplicationId");
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
