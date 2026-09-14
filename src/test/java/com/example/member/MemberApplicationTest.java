package com.example.member;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MemberApplicationTest {

	@Test
	@DisplayName("애플리케이션 컨텍스트가 예외 없이 로딩된다")
	void 컨텍스트가_로딩된다() {
	}

}
