package com.seaturtle.office.content;

/** 세계관 문서 하나(베이스 섹션 또는 상세 항목). text는 AI에게 보낼 본문이다. */
public record WorldDoc(String id, String title, String text) {
}
