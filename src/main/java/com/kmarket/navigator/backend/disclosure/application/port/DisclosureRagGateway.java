package com.kmarket.navigator.backend.disclosure.application.port;

import com.kmarket.navigator.backend.disclosure.domain.DisclosureAnswer;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureQuestion;

public interface DisclosureRagGateway {

	DisclosureAnswer ask(String receiptNumber, DisclosureQuestion question);
}
