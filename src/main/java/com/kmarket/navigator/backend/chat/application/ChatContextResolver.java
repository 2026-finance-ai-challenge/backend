package com.kmarket.navigator.backend.chat.application;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.chat.domain.ChatContext;
import com.kmarket.navigator.backend.chat.domain.ChatContextType;
import com.kmarket.navigator.backend.disclosure.application.DisclosureContentVersion;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.news.application.NewsService;
import com.kmarket.navigator.backend.stock.application.MarketService;

@Component
public class ChatContextResolver {

	private final MarketService marketService;
	private final NewsService newsService;
	private final DisclosureRepository disclosureRepository;

	public ChatContextResolver(
		MarketService marketService,
		NewsService newsService,
		DisclosureRepository disclosureRepository
	) {
		this.marketService = marketService;
		this.newsService = newsService;
		this.disclosureRepository = disclosureRepository;
	}

	public ChatContext resolve(ChatContextType type, String rawReferenceId) {
		return switch (type) {
			case GENERAL -> resolveGeneral(rawReferenceId);
			case STOCK -> resolveStock(rawReferenceId);
			case NEWS -> resolveNews(rawReferenceId);
			case FILING -> resolveFiling(rawReferenceId);
			case TAX_GUIDE -> resolveTaxGuide(rawReferenceId);
		};
	}

	private ChatContext resolveGeneral(String referenceId) {
		requireAbsent(referenceId);
		return new ChatContext(ChatContextType.GENERAL, null, null, "Korean Market");
	}

	private ChatContext resolveStock(String referenceId) {
		String stockCode = requireReference(referenceId).toUpperCase(Locale.ROOT);
		if (!stockCode.matches("^[0-9A-Z]{6}$")) {
			throw new BusinessException(ErrorCode.INVALID_CHAT_CONTEXT);
		}
		var detail = marketService.stockDetail(stockCode, null);
		String name = detail.view().stock().nameEn().isBlank()
			? detail.view().stock().nameKo()
			: detail.view().stock().nameEn();
		return new ChatContext(ChatContextType.STOCK, stockCode, null, title(name));
	}

	private ChatContext resolveNews(String referenceId) {
		try {
			var article = newsService.findOne(UUID.fromString(requireReference(referenceId)));
			String title = article.englishTitle() == null || article.englishTitle().isBlank()
				? article.originalTitle()
				: article.englishTitle();
			return new ChatContext(ChatContextType.NEWS, article.id().toString(), null, title(title));
		}
		catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.INVALID_CHAT_CONTEXT);
		}
	}

	private ChatContext resolveFiling(String referenceId) {
		String receiptNumber = requireReference(referenceId);
		if (!receiptNumber.matches("^[0-9]{14}$")) {
			throw new BusinessException(ErrorCode.INVALID_CHAT_CONTEXT);
		}
		var detail = disclosureRepository.findByReceiptNumber(receiptNumber)
			.orElseThrow(() -> new BusinessException(ErrorCode.DISCLOSURE_NOT_FOUND));
		if (detail.documents().isEmpty()) {
			disclosureRepository.requestIndexing(receiptNumber);
			throw new BusinessException(ErrorCode.DISCLOSURE_DOCUMENT_NOT_READY);
		}
		return new ChatContext(
			ChatContextType.FILING,
			receiptNumber,
			DisclosureContentVersion.calculate(detail),
			title(detail.issuerNameKo() + " · " + detail.titleKo())
		);
	}

	private ChatContext resolveTaxGuide(String referenceId) {
		if (referenceId == null || referenceId.isBlank()) {
			return new ChatContext(ChatContextType.TAX_GUIDE, null, null, "Tax Guide");
		}
		String normalized = referenceId.trim().toUpperCase(Locale.ROOT);
		if (!normalized.matches("^[A-Z]{2}:(INDIVIDUAL|CORPORATE)$")) {
			throw new BusinessException(ErrorCode.INVALID_CHAT_CONTEXT);
		}
		return new ChatContext(ChatContextType.TAX_GUIDE, normalized, null, "Tax Guide · " + normalized);
	}

	private String requireReference(String referenceId) {
		if (referenceId == null || referenceId.isBlank()) {
			throw new BusinessException(ErrorCode.INVALID_CHAT_CONTEXT);
		}
		return referenceId.trim();
	}

	private void requireAbsent(String referenceId) {
		if (referenceId != null && !referenceId.isBlank()) {
			throw new BusinessException(ErrorCode.INVALID_CHAT_CONTEXT);
		}
	}

	private String title(String value) {
		return value.length() <= 500 ? value : value.substring(0, 500);
	}
}
