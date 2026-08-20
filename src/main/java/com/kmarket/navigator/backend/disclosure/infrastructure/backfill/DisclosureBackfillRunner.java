package com.kmarket.navigator.backend.disclosure.infrastructure.backfill;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.disclosure.application.DisclosureBackfillHandler;

@Component
@Profile("backfill")
class DisclosureBackfillRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DisclosureBackfillRunner.class);

	private final DisclosureBackfillHandler backfillHandler;

	DisclosureBackfillRunner(DisclosureBackfillHandler backfillHandler) {
		this.backfillHandler = backfillHandler;
	}

	@Override
	public void run(ApplicationArguments arguments) {
		LocalDate from = requiredDate(arguments, "from");
		LocalDate to = requiredDate(arguments, "to");
		var result = backfillHandler.run(from, to);
		log.info(
			"과거 공시 적재 완료: from={}, to={}, collectedCount={}, alreadyCompleted={}",
			result.from(),
			result.to(),
			result.collectedCount(),
			result.alreadyCompleted()
		);
	}

	private static LocalDate requiredDate(ApplicationArguments arguments, String name) {
		List<String> values = arguments.getOptionValues(name);
		if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
			throw new IllegalArgumentException("--" + name + " requires one ISO-8601 date");
		}
		try {
			return LocalDate.parse(values.getFirst());
		}
		catch (DateTimeParseException exception) {
			throw new IllegalArgumentException(
				"--" + name + " requires one ISO-8601 date",
				exception
			);
		}
	}
}
