package com.example.board.auth.phone;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 6자리 인증번호 생성(SecureRandom)과 저장용 해시.
 *
 * <p>해시 = HMAC-SHA256(서버 키, {@code 챌린지 id | 번호 | 코드}) hex. 6자리 코드는 경우의 수가 10^6 이라 그냥 SHA-256 이면 DB 가
 * 새면 바로 역산된다. 서버 키(DB 밖, env)가 있어야 계산할 수 있게 하고, 챌린지 id 를 넣어 같은 코드라도 챌린지마다 해시가 다르다.
 */
public final class OtpCodes {

	public static final int LENGTH = 6;

	private static final int BOUND = 1_000_000;

	private static final String ALGORITHM = "HmacSHA256";

	private final SecureRandom random = new SecureRandom();

	private final SecretKeySpec key;

	public OtpCodes(String hashKey) {
		this.key = new SecretKeySpec(hashKey.getBytes(StandardCharsets.UTF_8), ALGORITHM);
	}

	public String generate() {
		return String.format("%06d", random.nextInt(BOUND));
	}

	public String hash(UUID challengeId, String phone, String code) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(key);
			byte[] digest = mac.doFinal((challengeId + "|" + phone + "|" + code).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** 상수 시간 비교. */
	public boolean matches(String storedHash, UUID challengeId, String phone, String code) {
		if (code == null) {
			return false;
		}
		return MessageDigest.isEqual(storedHash.getBytes(StandardCharsets.US_ASCII),
				hash(challengeId, phone, code.strip()).getBytes(StandardCharsets.US_ASCII));
	}

}
