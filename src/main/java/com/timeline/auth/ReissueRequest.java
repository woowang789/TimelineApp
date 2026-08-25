package com.timeline.auth;

import jakarta.validation.constraints.NotBlank;

/** 재발급 요청. */
public record ReissueRequest(

		@NotBlank(message = "refreshToken은 필수입니다.")
		String refreshToken) {
}
