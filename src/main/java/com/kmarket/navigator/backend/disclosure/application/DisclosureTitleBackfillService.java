package com.kmarket.navigator.backend.disclosure.application;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureTitleTranslationCatalog;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureTitleTranslationRepository;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureTitleBackfillPreview;

@Service
public class DisclosureTitleBackfillService {

	private final DisclosureTitleTranslationRepository repository;
	private final DisclosureTitleTranslationCatalog catalog;
	private final DisclosureTitleTranslationWorker worker;

	public DisclosureTitleBackfillService(
		DisclosureTitleTranslationRepository repository,
		DisclosureTitleTranslationCatalog catalog,
		DisclosureTitleTranslationWorker worker
	) {
		this.repository = repository;
		this.catalog = catalog;
		this.worker = worker;
	}

	public DisclosureTitleBackfillPreview preview() {
		var outstanding = repository.findOutstandingSources();
		var missing = outstanding.stream()
			.filter(source -> catalog.translate(source.normalizedTitle()).isEmpty())
			.map(source -> source.normalizedTitle())
			.toList();
		long ready = repository.countReadyTitles();
		return new DisclosureTitleBackfillPreview(
			repository.countSupportedDisclosures(),
			repository.countUniqueTitles(),
			ready,
			ready + outstanding.size() - missing.size(),
			missing
		);
	}

	public List<String> missingCatalogEntries() {
		return repository.findOutstandingSources().stream()
			.filter(source -> catalog.translate(source.normalizedTitle()).isEmpty())
			.map(source -> catalog.reviewKey(source.normalizedTitle()))
			.distinct()
			.sorted()
			.toList();
	}

	public DisclosureTitleBackfillPreview apply(int batchSize) {
		DisclosureTitleBackfillPreview before = preview();
		if (!before.completeCatalog()) {
			throw new IllegalStateException(
				"Disclosure title catalog is incomplete: " + before.missingTitles().size()
			);
		}
		repository.requeueFailed(Instant.now());
		while (worker.processBatch(batchSize) > 0) {
			// 각 배치가 커밋되므로 중단 후 같은 명령으로 재개할 수 있다.
		}
		DisclosureTitleBackfillPreview after = preview();
		if (after.readyTitleCount() != after.uniqueTitleCount()) {
			throw new IllegalStateException("Disclosure title backfill did not complete");
		}
		return after;
	}
}
