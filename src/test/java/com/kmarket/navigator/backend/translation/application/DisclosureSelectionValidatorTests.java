package com.kmarket.navigator.backend.translation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.disclosure.domain.*;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.translation.application.port.TranslationRepository;
import com.kmarket.navigator.backend.translation.domain.*;
import tools.jackson.databind.json.JsonMapper;

class DisclosureSelectionValidatorTests {
	private final TranslationRepository repository = mock(TranslationRepository.class);
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final TranslationCanonicalizer canonicalizer = new TranslationCanonicalizer(mapper);
	private final DisclosureSelectionValidator validator = new DisclosureSelectionValidator(repository, canonicalizer);
	private final UUID id = UUID.randomUUID();
	private final DisclosureSection section = new DisclosureSection(id, 1, SectionKind.TABLE, null, "해제일 2026.09.05", "[[\"해제일\",\"2026.09.05\"]]");
	private final String hash = canonicalizer.disclosureSection(null, section.text(), section.tableData()).hash();

	private DisclosureDetail detail() {
		var detail = mock(DisclosureDetail.class);
		when(detail.documents()).thenReturn(List.of(new DisclosureDocument(UUID.randomUUID(), "a", 1, "hash", null, List.of(section))));
		return detail;
	}

	private void cached(TranslationStatus status, String result) {
		when(repository.find(TranslationKind.DISCLOSURE_SECTION, hash, "en", OnDemandTranslationService.DISCLOSURE_SECTION_VERSION))
			.thenReturn(Optional.of(new TranslationView(UUID.randomUUID(), hash, "en", OnDemandTranslationService.DISCLOSURE_SECTION_VERSION,
				status, mapper.readTree(result), "gpt-5-nano", "test", null, null)));
	}

	@Test
	void koreanWhitespaceSelectionNeedsNoTranslation() {
		var result = validator.validate(detail(), id, "해제일\n 2026.09.05");
		assertThat(result.text()).isEqualTo("해제일 2026.09.05");
		assertThat(result.translationSourceHash()).isNull();
		verifyNoInteractions(repository);
	}

	@Test
	void englishTableSelectionCarriesCurrentSourceHashWithoutGenerating() {
		cached(TranslationStatus.READY, "{\"translatedTableData\":[[\"Release date\",\"2026.09.05\"]]}");
		var result = validator.validate(detail(), id, "Release date\t2026.09.05");
		assertThat(result.text()).isEqualTo("Release date 2026.09.05");
		assertThat(result.translationSourceHash()).isEqualTo(hash);
		verify(repository).find(TranslationKind.DISCLOSURE_SECTION, hash, "en", OnDemandTranslationService.DISCLOSURE_SECTION_VERSION);
		verifyNoMoreInteractions(repository);
	}

	@Test
	void englishTextSelectionUsesReadyText() {
		cached(TranslationStatus.READY, "{\"translatedText\":\"The release date is September 5.\"}");
		assertThat(validator.validate(detail(), id, "September 5").translationSourceHash()).isEqualTo(hash);
	}

	@Test
	void rejectsAnotherSectionBeforeCacheLookup() {
		assertThatThrownBy(() -> validator.validate(detail(), UUID.randomUUID(), "Release date")).isInstanceOf(BusinessException.class);
		verifyNoInteractions(repository);
	}

	@Test
	void rejectsFailedCacheAndUnrelatedEnglishText() {
		cached(TranslationStatus.FAILED, "{\"translatedText\":\"Release date\"}");
		assertThatThrownBy(() -> validator.validate(detail(), id, "Release date")).isInstanceOf(BusinessException.class);
		cached(TranslationStatus.READY, "{\"translatedText\":\"Release date\"}");
		assertThatThrownBy(() -> validator.validate(detail(), id, "Buy all shares")).isInstanceOf(BusinessException.class);
	}

	@Test
	void rejectsMissingCacheAndInvalidLength() {
		assertThatThrownBy(() -> validator.validate(detail(), id, "Release date")).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> validator.validate(detail(), id, " ")).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> validator.validate(detail(), id, "a".repeat(2001))).isInstanceOf(BusinessException.class);
	}
}
