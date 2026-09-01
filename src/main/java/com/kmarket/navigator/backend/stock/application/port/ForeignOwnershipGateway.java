package com.kmarket.navigator.backend.stock.application.port;

import java.time.LocalDate;
import java.util.List;

import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipCollectionTarget;
import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipSnapshot;

public interface ForeignOwnershipGateway {

	boolean configured();

	List<ForeignOwnershipSnapshot> fetchHistory(
		ForeignOwnershipCollectionTarget target,
		LocalDate from,
		LocalDate to
	);
}
