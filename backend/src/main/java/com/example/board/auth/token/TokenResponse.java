package com.example.board.auth.token;

/**
 * JWT 로그인·refresh 응답. {@code expiresIn} 은 access 토큰 수명(초).
 * refresh 토큰은 JWT 가 아닌 불투명 랜덤 값이고 서버에는 해시만 있다.
 */
public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {

	public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn) {
		return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn);
	}

}
