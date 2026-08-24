package com.kmarket.navigator.backend.disclosure.infrastructure.backfill;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.disclosure.application.DisclosureTitleBackfillService;

@Component
@Profile("title-backfill")
class DisclosureTitleBackfillRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DisclosureTitleBackfillRunner.class);
	private static final int DEFAULT_BATCH_SIZE = 100;
	private final DisclosureTitleBackfillService service;
	private final ConfigurableApplicationContext applicationContext;

	DisclosureTitleBackfillRunner(
		DisclosureTitleBackfillService service,
		ConfigurableApplicationContext applicationContext
	) {
		this.service = service;
		this.applicationContext = applicationContext;
	}

	@Override
	public void run(ApplicationArguments arguments) {
		try {
			var preview = service.preview();
			log.info(
				"공시 제목 백필 점검: disclosures={}, uniqueTitles={}, readyTitles={}, "
					+ "catalogMatches={}, missingTitles={}, providerCalls=0, estimatedCostUsd=0",
				preview.disclosureCount(),
				preview.uniqueTitleCount(),
				preview.readyTitleCount(),
				preview.catalogMatchCount(),
				preview.missingTitles().size()
			);
			if (!preview.missingTitles().isEmpty()) {
				var missingEntries = service.missingCatalogEntries();
				int missingOffset = optionalNonNegativeInteger(arguments, "missing-offset", 0);
				int missingLimit = optionalPositiveInteger(arguments, "missing-limit", 20);
				log.info("공시 제목 카탈로그 검수 단위: missingEntries={}", missingEntries.size());
				missingEntries.stream().skip(missingOffset).limit(missingLimit)
					.forEach(title -> log.warn("공시 제목 번역 누락: catalogEntry={}", title));
			}
			if (!arguments.containsOption("apply")) {
				log.info("dry-run 완료: 실제 적용은 --apply 옵션이 필요합니다");
				return;
			}
			int batchSize = optionalPositiveInteger(arguments, "batch-size", DEFAULT_BATCH_SIZE);
			var result = service.apply(batchSize);
			log.info(
				"공시 제목 백필 완료: disclosures={}, uniqueTitles={}, readyTitles={}, "
					+ "providerCalls=0, actualCostUsd=0",
				result.disclosureCount(),
				result.uniqueTitleCount(),
				result.readyTitleCount()
			);
		}
		finally {
			applicationContext.close();
		}
	}

	private static int optionalPositiveInteger(
		ApplicationArguments arguments,
		String option,
		int defaultValue
	) {
		int value = optionalNonNegativeInteger(arguments, option, defaultValue);
		if (value == 0) {
			throw new IllegalArgumentException("--" + option + " must be positive");
		}
		return value;
	}

	private static int optionalNonNegativeInteger(
		ApplicationArguments arguments,
		String option,
		int defaultValue
	) {
		List<String> values = arguments.getOptionValues(option);
		if (values == null) {
			return defaultValue;
		}
		if (values.size() != 1) {
			throw new IllegalArgumentException("--" + option + " requires one integer");
		}
		try {
			int value = Integer.parseInt(values.getFirst());
			if (value < 0) {
				throw new IllegalArgumentException("--" + option + " must not be negative");
			}
			return value;
		}
		catch (NumberFormatException exception) {
			throw new IllegalArgumentException("--" + option + " requires one integer", exception);
		}
	}
}
