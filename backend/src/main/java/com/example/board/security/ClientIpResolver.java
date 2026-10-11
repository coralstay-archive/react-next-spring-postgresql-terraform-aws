package com.example.board.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

/** 요청한 클라이언트의 IP (레이트리밋·기록용). */
@Component
public class ClientIpResolver {

	public String resolve(HttpServletRequest request) {
		return request.getRemoteAddr();
	}

}
