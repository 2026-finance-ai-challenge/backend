package com.kmarket.navigator.backend.chat.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kmarket.navigator.backend.chat.application.port.ChatRoomRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Service
public class ChatRetentionWorker {

	private final ChatRoomRepository repository;
	private final Clock clock;

	public ChatRetentionWorker(ChatRoomRepository repository) {
		this.repository = repository;
		this.clock = Clock.systemUTC();
	}

	@Scheduled(cron = "${kmarket.chat.purge-cron:0 20 4 * * *}")
	@SchedulerLock(name = "chat-retention-purge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1S")
	@Transactional
	public void purge() {
		while (repository.purgeExpired(Instant.now(clock), 500) == 500) {
			// 삭제 범위를 제한해 장시간 잠금을 피한다.
		}
	}
}
