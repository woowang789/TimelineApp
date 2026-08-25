package com.timeline.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청.
 *
 * <p>가입({@link SignupRequest})과 달리 길이·형식 제약을 걸지 않는다. 여기서 400을 내면
 * "이 형식은 애초에 가입할 수 없는 형식"이라는 정보를 흘리게 되고, 그건 username 열거의 실마리가 된다.
 * 값이 있는지만 보고 나머지는 전부 401로 수렴시킨다.
 */
public record LoginRequest(

		@NotBlank(message = "username은 필수입니다.")
		String username,

		@NotBlank(message = "password는 필수입니다.")
		String password) {
}
