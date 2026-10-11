package com.example.board.user;

/** 로그인 수단 (decision-4). 새 수단은 여기에 값 하나를 더하고 마이그레이션의 CHECK 제약도 같이 고친다. */
public enum AuthProvider {
	LOCAL, GOOGLE, GITHUB, NAVER, KAKAO, COGNITO, PHONE
}
