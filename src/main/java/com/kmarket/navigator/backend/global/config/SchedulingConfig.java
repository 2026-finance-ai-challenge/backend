package com.kmarket.navigator.backend.global.config;

import java.time.Clock;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Profile("!test")
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT15M")
@Configuration(proxyBeanMethods = false)
class SchedulingConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	LockProvider lockProvider(DataSource dataSource) {
		return new JdbcTemplateLockProvider(
			JdbcTemplateLockProvider.Configuration.builder()
				.withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
				.usingDbTime()
				.build()
		);
	}

	@Bean
	TaskScheduler taskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(2);
		scheduler.setThreadNamePrefix("disclosure-scheduler-");
		scheduler.setWaitForTasksToCompleteOnShutdown(true);
		scheduler.setAwaitTerminationSeconds(20);
		return scheduler;
	}
}
