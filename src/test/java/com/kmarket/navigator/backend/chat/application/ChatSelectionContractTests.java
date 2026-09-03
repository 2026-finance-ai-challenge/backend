package com.kmarket.navigator.backend.chat.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.kmarket.navigator.backend.chat.domain.ChatContextType;
import com.kmarket.navigator.backend.global.error.BusinessException;

class ChatSelectionContractTests {
	@Test
	void newsAcceptsArticleSelectionButNotFilingSectionIds() {
		assertThatCode(() -> ChatMessageService.validateSelection(ChatContextType.NEWS, null, "기사 원문")).doesNotThrowAnyException();
		assertThatThrownBy(() -> ChatMessageService.validateSelection(ChatContextType.NEWS, UUID.randomUUID(), "기사 원문"))
			.isInstanceOf(BusinessException.class);
	}
	@Test
	void filingStillRequiresBothSelectionFields() {
		assertThatThrownBy(() -> ChatMessageService.validateSelection(ChatContextType.FILING, null, "공시 원문"))
			.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> ChatMessageService.validateSelection(ChatContextType.FILING, UUID.randomUUID(), null))
			.isInstanceOf(BusinessException.class);
	}
	@Test
	void otherContextsCannotSmuggleSelections() {
		for (var type : new ChatContextType[]{ChatContextType.GENERAL, ChatContextType.STOCK, ChatContextType.TAX_GUIDE}) {
			assertThatThrownBy(() -> ChatMessageService.validateSelection(type, null, "기사 원문")).isInstanceOf(BusinessException.class);
		}
	}
	@Test
	void boundsSelectionBeforeCreatingAJob() {
		assertThatThrownBy(() -> ChatMessageService.validateSelection(ChatContextType.NEWS, null, "x".repeat(2001)))
			.isInstanceOf(BusinessException.class);
	}
}
