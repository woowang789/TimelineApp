package com.timeline.auth;

/**
 * 재발급 응답 — Access Token만 준다.
 *
 * <p>Refresh Token은 갱신하지 않는다(rotation 없음). 14일이 지나면 다시 로그인한다.
 * rotation은 탈취 대응 설계인데, 이 프로젝트가 증명하려는 것은 타임라인의 성능이지 인증의 견고함이 아니다.
 */
public record ReissueResponse(String accessToken) {
}
