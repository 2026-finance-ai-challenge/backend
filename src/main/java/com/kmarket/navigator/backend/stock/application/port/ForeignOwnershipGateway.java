package com.kmarket.navigator.backend.stock.application.port;

import java.time.LocalDate;
import java.util.List;

import com.kmarket.navigator.backend.stock.domain.ForeignLimitCollectionTarget;
import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipSnapshot;

public interface ForeignOwnershipGateway {

	boolean configured();

	List<ForeignOwnershipSnapshot> fetchHistory(
		ForeignLimitCollectionTarget target,
		LocalDate from,
		LocalDate to
	);
}
