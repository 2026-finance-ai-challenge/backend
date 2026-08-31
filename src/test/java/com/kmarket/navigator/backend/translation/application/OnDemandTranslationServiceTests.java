package com.kmarket.navigator.backend.translation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.kmarket.navigator.backend.disclosure.application.DisclosureQueryHandler;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDetail;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDocument;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSection;
import com.kmarket.navigator.backend.disclosure.domain.SectionKind;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.news.application.port.NewsRepository;
import com.kmarket.navigator.backend.translation.application.port.TranslationRepository;
import com.kmarket.navigator.backend.translation.domain.TranslationKind;
import com.kmarket.navigator.backend.translation.domain.TranslationStatus;
import com.kmarket.navigator.backend.translation.domain.TranslationView;

import tools.jackson.databind.json.JsonMapper;

class OnDemandTranslationServiceTests {

	private static final String RECEIPT_NUMBER = "20260823000001";
	private final NewsRepository newsRepository = Mockito.mock(NewsRepository.class);
	private final DisclosureQueryHandler disclosureQueryHandler = Mockito.mock(
		DisclosureQueryHandler.class
	);
	private final TranslationRepository translationRepository = Mockito.mock(
		TranslationRepository.class
	);
	private final TranslationRequestRateLimiter rateLimiter = Mockito.mock(
		TranslationRequestRateLimiter.class
	);
	private final JsonMapper objectMapper = JsonMapper.builder().build();
	private final OnDemandTranslationService service = new OnDemandTranslationService(
		newsRepository,
		disclosureQueryHandler,
		translationRepository,
		new TranslationCanonicalizer(objectMapper),
		rateLimiter,
		objectMapper,
		Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC)
	);

	@Test
	void bindsDisclosureTranslationToCurrentSectionSourceHash() {
		UUID sectionId = UUID.randomUUID();
		when(disclosureQueryHandler.findOne(RECEIPT_NUMBER)).thenReturn(detail(sectionId));
		when(translationRepository.find(
			TranslationKind.DISCLOSURE_SECTION,
			"7eeec9dfa5bbc38484ca8f4512729da678f7ff96b8f5d7859de0b537bf33974e",
			OnDemandTranslationService.DISCLOSURE_SECTION_VERSION
		)).thenReturn(Optional.empty());

		var result = service.findDisclosureSection(RECEIPT_NUMBER, sectionId);

		assertThat(result.status()).isEqualTo(TranslationStatus.NOT_REQUESTED);
		assertThat(result.sourceHash()).isEqualTo(
			"7eeec9dfa5bbc38484ca8f4512729da678f7ff96b8f5d7859de0b537bf33974e"
		);
	}

	@Test
	void rejectsSectionOutsideCurrentDisclosure() {
		when(disclosureQueryHandler.findOne(RECEIPT_NUMBER)).thenReturn(detail(UUID.randomUUID()));

		assertThatThrownBy(() -> service.findDisclosureSection(RECEIPT_NUMBER, UUID.randomUUID()))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.DISCLOSURE_SECTION_NOT_FOUND)
			);
	}

	@Test
	void prioritizesAFirstViewDisclosureTranslation() {
		UUID sectionId = UUID.randomUUID();
		UUID translationId = UUID.randomUUID();
		when(disclosureQueryHandler.findOne(RECEIPT_NUMBER)).thenReturn(detail(sectionId));
		when(translationRepository.request(
			Mockito.eq(TranslationKind.DISCLOSURE_SECTION),
			Mockito.anyString(), Mockito.anyString(), Mockito.any(),
			Mockito.eq(OnDemandTranslationService.DISCLOSURE_SECTION_VERSION),
			Mockito.any()
		)).thenReturn(new TranslationView(
			translationId, "source-hash", "en",
			OnDemandTranslationService.DISCLOSURE_SECTION_VERSION,
			TranslationStatus.PENDING, null, null, null, null, null
		));

		service.requestDisclosureSection(RECEIPT_NUMBER, sectionId, "client-hash");

		verify(translationRepository).prioritize(
			Mockito.eq(translationId), Mockito.eq(Instant.parse("2026-08-25T00:00:00Z"))
		);
	}

	private static DisclosureDetail detail(UUID sectionId) {
		DisclosureSection section = new DisclosureSection(
			sectionId, 1, SectionKind.TEXT, "제목", "본문", "{\"항목\":\"값\"}"
		);
		DisclosureDocument document = new DisclosureDocument(
			UUID.randomUUID(), "report.xml", 7, "content-hash", "<p>본문</p>", List.of(section)
		);
		return new DisclosureDetail(
			RECEIPT_NUMBER,
			null, null, null, null, null, null, null, null,
			null, null, null, null,
			null, null, null, null, false, null, null, null,
			List.of(document), List.of()
		);
	}
}
