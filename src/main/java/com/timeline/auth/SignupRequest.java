package com.timeline.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청.
 *
 * <p>길이 상한은 스키마와 맞춘다 — username·nickname은 {@code VARCHAR(50)}이다.
 * password 상한 64자는 컬럼(255)과 무관하다. 저장되는 것은 항상 60자짜리 BCrypt 해시이고,
 * BCrypt는 72바이트를 넘는 입력을 잘라내므로 그보다 긴 비밀번호를 받아 봐야 뒤쪽은 검증되지 않는다.
 */
public record SignupRequest(

		@NotBlank(message = "username은 필수입니다.")
		@Size(max = 50, message = "username은 50자 이하여야 합니다.")
		String username,

		@NotBlank(message = "password는 필수입니다.")
		@Size(min = 8, max = 64, message = "password는 8자 이상 64자 이하여야 합니다.")
		String password,

		@NotBlank(message = "nickname은 필수입니다.")
		@Size(max = 50, message = "nickname은 50자 이하여야 합니다.")
		String nickname) {
}
