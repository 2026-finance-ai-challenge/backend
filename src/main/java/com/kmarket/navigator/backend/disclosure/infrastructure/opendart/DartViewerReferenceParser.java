package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

final class DartViewerReferenceParser {
	private static final Pattern INITIAL = Pattern.compile(
		"viewDoc\\(\\\"([0-9]{14})\\\",\\s*\\\"([0-9]{1,20})\\\",\\s*"
			+ "\\\"([0-9]{1,12})\\\",\\s*\\\"([0-9]{1,12})\\\",\\s*"
			+ "\\\"([0-9]{1,12})\\\",\\s*\\\"([A-Za-z0-9._-]{1,50})\\\","
	);
	private static final Pattern NODE = Pattern.compile(
		"(node[0-9]+)\\['rcpNo'\\]\\s*=\\s*\"([0-9]{14})\";\\s*"
			+ "\\1\\['dcmNo'\\]\\s*=\\s*\"([0-9]{1,20})\";\\s*"
			+ "\\1\\['eleId'\\]\\s*=\\s*\"([0-9]{1,12})\";\\s*"
			+ "\\1\\['offset'\\]\\s*=\\s*\"([0-9]{1,12})\";\\s*"
			+ "\\1\\['length'\\]\\s*=\\s*\"([0-9]{1,12})\";\\s*"
			+ "\\1\\['dtd'\\]\\s*=\\s*\"([A-Za-z0-9._-]{1,50})\";"
	);

	private DartViewerReferenceParser() { }

	static List<Reference> parse(String receipt, String index) {
		var initial = INITIAL.matcher(index);
		Reference main = null;
		while (initial.find()) {
			if (receipt.equals(initial.group(1))) {
				main = new Reference(initial.group(2), initial.group(3), initial.group(4), initial.group(5), initial.group(6));
				break;
			}
		}
		if (main == null) throw new OpenDartException("DART_VIEWER_REFERENCE_NOT_FOUND");
		if (main.elementId().equals("0") && main.offset().equals("0") && main.length().equals("0")) return List.of(main);
		var refs = new ArrayList<Reference>();
		int rootLevel = Integer.MAX_VALUE;
		int matchedNodes = 0;
		var matcher = NODE.matcher(index);
		while (matcher.find()) {
			if (receipt.equals(matcher.group(2)) && main.documentNumber().equals(matcher.group(3))) {
				matchedNodes++;
				int level = Integer.parseInt(matcher.group(1).substring(4));
				// DART 목차의 상위 항목은 하위 항목 전체를 포함한다. 구형 DTD의 닫는 태그 바이트 오차로 중복 수집하지 않는다.
				if (level < rootLevel) { rootLevel = level; refs.clear(); }
				if (level == rootLevel) refs.add(new Reference(matcher.group(3), matcher.group(4), matcher.group(5), matcher.group(6), matcher.group(7)));
			}
		}
		long expected = Pattern.compile("node[0-9]+\\['dcmNo'\\]\\s*=\\s*\"" + Pattern.quote(main.documentNumber()) + "\";")
			.matcher(index).results().count();
		if (expected != matchedNodes) throw new OpenDartException("DART_VIEWER_TOC_UNRECOGNIZED");
		if (refs.isEmpty()) {
			if (index.contains("['rcpNo']")) throw new OpenDartException("DART_VIEWER_TOC_UNRECOGNIZED");
			return List.of(main);
		}
		refs.add(main);
		refs.sort(Comparator.comparingLong(Reference::start).thenComparing(Comparator.comparingLong(Reference::end).reversed()));
		var result = new ArrayList<Reference>();
		// 상위 목차에 포함된 하위 범위는 다시 수집하지 않는다. 부분 겹침은 누락·중복 위험이 있어 거부한다.
		for (var ref : refs) {
			if (!ref.dtd().equals(main.dtd()) || ref.end() <= ref.start()) throw new OpenDartException("DART_VIEWER_TOC_INVALID");
			if (!result.isEmpty()) {
				var previous = result.getLast();
				if (ref.start() >= previous.start() && ref.end() <= previous.end()) continue;
				if (ref.start() < previous.end()) throw new OpenDartException("DART_VIEWER_TOC_OVERLAP");
			}
			result.add(ref);
		}
		if (result.size() > 256) throw new OpenDartException("DART_VIEWER_TOC_LIMIT");
		return List.copyOf(result);
	}

	record Reference(String documentNumber, String elementId, String offset, String length, String dtd) {
		long start() { return Long.parseLong(offset); }
		long end() { return start() + Long.parseLong(length); }
	}
}
