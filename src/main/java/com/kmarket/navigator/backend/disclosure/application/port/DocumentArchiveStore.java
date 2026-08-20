package com.kmarket.navigator.backend.disclosure.application.port;

import java.util.List;

public interface DocumentArchiveStore {

	List<StoredDocumentArchive> store(DocumentJob job, OpenDartDocumentFetch fetch);
}
