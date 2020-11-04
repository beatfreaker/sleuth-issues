package com.example.demo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import brave.Span;
import brave.Tracer;
import brave.sampler.Sampler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.BDDAssertions.then;

@SpringBootTest(classes = IssueIT.Config.class)
@ActiveProfiles("test")
public class IssueIT {

	private static final Logger log = LoggerFactory.getLogger(IssueIT.class);

	@Value("${test.server.port:23456}")
	int port;
	@Autowired
	Tracer tracer;
	@Autowired
	WebClient.Builder webClientBuilder;

	WebClient webclient1;
	WebClient webclient2;
	WebClient webclient3;

	@BeforeEach
	public void setup() {
		this.webclient1 = this.webClientBuilder.baseUrl("http://localhost:" + this.port + "/foo").build();
		this.webclient2 = this.webClientBuilder.baseUrl("http://localhost:" + this.port + "/bar").build();
		this.webclient3 = this.webClientBuilder.baseUrl("http://localhost:" + this.port + "/baz").build();
	}

	@Test
	public void should_propagate_trace_id() throws IOException {
		//given
		Span span = this.tracer.nextSpan().name("foo").start();
		try (Tracer.SpanInScope ws = this.tracer.withSpanInScope(span)) {
			log.info("[TEST_ME] Starting");
			// when
			Object res = this.webclient1.get().retrieve()
					.bodyToMono(String.class).flatMap(foo -> {
						log.info("[TEST_ME] received [" + foo + "]");
						return this.webclient2.get().retrieve().bodyToMono(String.class).map(bar -> {
							log.info("[TEST_ME] concatenating [" + foo + " " + bar);
							return foo + " " + bar;
						});
					}).flatMap(fooBar -> {
						log.info("[TEST_ME] received [" + fooBar + "]");
						return this.webclient3.get().retrieve().bodyToMono(String.class).map(baz -> {
							log.info("[TEST_ME] concatenating [" + fooBar + "] [" + baz + "]");
							return fooBar + " " + baz;
						});
					}).block();
			log.info("[TEST_ME] Finished");

			then(res).isEqualTo("foo bar baz");
		}
		finally {
			span.finish();
		}

		//then
		String prodLog = fileAsString("target/log/spring.log");
		String testLog = fileAsString("target/test_log/spring.log");
		List<String> prodTraceIds = traceIdFromString(prodLog);
		List<String> testTraceIds = traceIdFromString(testLog);
		List<String> combinedTraceIds = new ArrayList<>(prodTraceIds);
		combinedTraceIds.addAll(testTraceIds);

		// they should have the same value
		then(combinedTraceIds.stream().distinct().collect(Collectors.toList()))
				.hasSize(1)
				.containsExactly(span.context().traceIdString());
	}

	private List<String> traceIdFromString(String prodLog) {
		return Arrays.stream(prodLog.split("\n"))
				.filter(s -> s.contains("[TEST_ME]")).map(s -> s.split(",")[1].trim())
				.collect(Collectors.toList());
	}

	private String fileAsString(String path) throws IOException {
		return new String(Files.readAllBytes(new File(path).toPath()));
	}

	@Configuration
	@EnableAutoConfiguration
	static class Config {
		@Bean
		Sampler sampler() {
			return Sampler.ALWAYS_SAMPLE;
		}
	}

}
