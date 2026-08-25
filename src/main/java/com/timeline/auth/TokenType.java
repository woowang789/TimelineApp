package com.timeline.auth;

/**
 * 토큰 종류. 같은 키로 서명되므로 <strong>이 구분이 없으면 Refresh Token으로 API를 호출할 수 있다</strong>
 * — 서명도 만료도 통과하기 때문이다. 그래서 발급 시 {@code type} 클레임에 넣고,
 * 검증 시 기대하는 종류와 일치하는지까지 본다.
 */
public enum TokenType {

	ACCESS,
	REFRESH
}
