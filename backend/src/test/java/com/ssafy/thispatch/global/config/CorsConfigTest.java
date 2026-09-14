package com.ssafy.thispatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	classes = CorsConfigTest.TestApplication.class,
	properties = {
		"app.frontend-base-url=http://localhost:5173",
		"app.backend-public-url=http://localhost:8080",
		"app.cors.allowed-origins=http://localhost:5173,https://thispatch.example"
	})
class CorsConfigTest {

	@Autowired
	private TestRestTemplate rest;

	@Test
	void preflightWithBearerHeaderPassesBeforeSecurityWithoutCredentials() {
		var response = rest.exchange("/auth/steam/signup", HttpMethod.OPTIONS,
			new HttpEntity<>(preflightHeaders("http://localhost:5173", "POST", "authorization,content-type")), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:5173");
		assertThat(response.getHeaders().getAccessControlAllowHeaders()).contains("authorization", "content-type");
		assertThat(response.getHeaders().containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isFalse();
	}

	@Test
	void rejectsUnlistedOrigin() {
		var response = rest.exchange("/auth/steam/signup", HttpMethod.OPTIONS,
			new HttpEntity<>(preflightHeaders("https://untrusted.example", "POST", "authorization")), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
	}

	@Test
	void rejectsUnlistedMethodAndHeader() {
		for (var headers : new HttpHeaders[] {
			preflightHeaders("http://localhost:5173", "TRACE", "authorization"),
			preflightHeaders("http://localhost:5173", "POST", "x-untrusted-header")
		}) {
			var response = rest.exchange("/auth/steam/signup", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		}
	}

	@Test
	void actualRequestStillRequiresAuthenticationAndIncludesCorsHeader() {
		var headers = new HttpHeaders();
		headers.setOrigin("https://thispatch.example");
		headers.set(HttpHeaders.ACCEPT, "application/json");
		var response = rest.exchange("/games", HttpMethod.GET, new HttpEntity<>(headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("https://thispatch.example");
	}

	private HttpHeaders preflightHeaders(String origin, String method, String requestedHeaders) {
		var headers = new HttpHeaders();
		headers.setOrigin(origin);
		headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
		headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, requestedHeaders);
		return headers;
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
		FlywayAutoConfiguration.class})
	@Import({AppConfig.class, CorsConfig.class})
	static class TestApplication {
	}
}
