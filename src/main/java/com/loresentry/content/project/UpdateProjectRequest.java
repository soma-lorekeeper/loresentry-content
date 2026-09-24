package com.loresentry.content.project;

/**
 * 부분 갱신이다. null은 "보내지 않았다"는 뜻이고 값을 지우는 뜻이 아니다.
 * 설명을 비우려면 빈 문자열을 보낸다.
 */
public record UpdateProjectRequest(String name, String description) {
}
