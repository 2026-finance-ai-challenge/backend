package com.kmarket.navigator.backend.tax.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TaxDocumentComparison(
	String verificationStatus,
	List<TaxDocumentIssue> findings,
	Map<String, Object> crossCheck,
	List<TaxDocumentVerification> documents,
	String modelId
) {

	public TaxDocumentComparison {
		findings = List.copyOf(findings);
		// reason=null은 정상적인 일치 결과이므로 null 값을 보존하면서 외부 변경만 차단한다.
		crossCheck = Collections.unmodifiableMap(new LinkedHashMap<>(crossCheck));
		documents = List.copyOf(documents);
	}
}
