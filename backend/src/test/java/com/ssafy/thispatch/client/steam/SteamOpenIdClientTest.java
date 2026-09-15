package com.ssafy.thispatch.client.steam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.math.BigInteger;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.ssafy.thispatch.global.config.AppProperties;

class SteamOpenIdClientTest {

	private MockRestServiceServer server;
	private SteamOpenIdClient client;

	@BeforeEach
	void setUp() {
		var builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new SteamOpenIdClient(builder.build(), new AppProperties(
			URI.create("https://frontend.example/app/"), URI.create("https://backend.example/api/"),
			new AppProperties.Cors(List.of(URI.create("https://frontend.example")))));
	}

	@Test
	void verifiesWithSteamAndPreservesSignedFieldsAndExternalPath() {
		var parameters = assertion();
		parameters.add("loginCode", "untrusted");
		parameters.add("redirect", "https://attacker.example");
		var expected = assertion();
		expected.set("openid.mode", "check_authentication");
		server.expect(requestTo(SteamOpenIdClient.ENDPOINT)).andExpect(method(HttpMethod.POST))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
			.andExpect(content().formData(expected))
			.andRespond(withSuccess("ns:" + SteamOpenIdClient.NAMESPACE + "\nis_valid:true\n", MediaType.TEXT_PLAIN));

		assertThat(client.verify(parameters)).contains(new BigInteger("76561198000000001"));
		server.verify();
	}

	@Test
	void acceptsSteamHttpIdentifierDocumentedByProvider() {
		var parameters = assertion();
		parameters.set("openid.claimed_id", "http://steamcommunity.com/openid/id/76561198000000001");
		parameters.set("openid.identity", parameters.getFirst("openid.claimed_id"));
		server.expect(requestTo(SteamOpenIdClient.ENDPOINT))
			.andRespond(withSuccess("ns:" + SteamOpenIdClient.NAMESPACE + "\r\nis_valid:true\r\n",
				MediaType.TEXT_PLAIN));
		assertThat(client.verify(parameters)).isPresent();
		server.verify();
	}

	@ParameterizedTest
	@MethodSource("invalidFields")
	void rejectsInvalidAssertionsBeforeNetworkRequest(String field, String value) {
		var parameters = assertion();
		parameters.set("openid." + field, value);
		assertThat(client.verify(parameters)).isEmpty();
		server.verify();
	}

	static Stream<Arguments> invalidFields() {
		return Stream.of(
			Arguments.of("ns", "http://attacker.example"),
			Arguments.of("mode", "cancel"),
			Arguments.of("mode", "check_authentication"),
			Arguments.of("op_endpoint", "https://attacker.example"),
			Arguments.of("return_to", "https://backend.example/auth/steam/callback"),
			Arguments.of("return_to", "https://attacker.example"),
			Arguments.of("identity", "https://steamcommunity.com/openid/id/76561198000000002"),
			Arguments.of("signed", "claimed_id,identity"),
			Arguments.of("response_nonce", "2026-99-15T01:00:00Znonce"),
			Arguments.of("response_nonce", "invalid"),
			Arguments.of("sig", "signature\nforged"),
			Arguments.of("assoc_handle", ""));
	}

	@Test
	void rejectsEveryMissingRequiredFieldAndDuplicateParameters() {
		for (String key : assertion().keySet()) {
			var missing = assertion();
			missing.remove(key);
			assertThat(client.verify(missing)).as("missing %s", key).isEmpty();
			var duplicate = assertion();
			duplicate.add(key, duplicate.getFirst(key));
			assertThat(client.verify(duplicate)).as("duplicate %s", key).isEmpty();
		}
		server.verify();
	}

	@Test
	void rejectsForeignMalformedAndOutOfRangeIdentifiers() {
		for (String identifier : List.of(
			"https://steamcommunity.com.attacker.example/openid/id/76561198000000001",
			"https://steamcommunity.com/openid/id/76561198000000001?x=1",
			"https://steamcommunity.com/openid/id/0",
			"https://steamcommunity.com/openid/id/18446744073709551616",
			"https://steamcommunity.com/openid/id/not-a-number")) {
			var parameters = assertion();
			parameters.set("openid.claimed_id", identifier);
			parameters.set("openid.identity", identifier);
			assertThat(client.verify(parameters)).isEmpty();
		}
		server.verify();
	}

	@ParameterizedTest
	@MethodSource("invalidResponses")
	void requiresExactPositiveVerificationResponse(String response) {
		server.expect(requestTo(SteamOpenIdClient.ENDPOINT))
			.andRespond(withSuccess(response, MediaType.TEXT_PLAIN));
		assertThat(client.verify(assertion())).isEmpty();
		server.verify();
	}

	static Stream<String> invalidResponses() {
		return Stream.of("", "is_valid:true\n", "ns:" + SteamOpenIdClient.NAMESPACE + "\nis_valid:false\n",
			"ns:" + SteamOpenIdClient.NAMESPACE + "\nis_valid:true\nis_valid:false\n",
			"ns:" + SteamOpenIdClient.NAMESPACE + "\nis_valid:true-forged\n");
	}

	@Test
	void failsClosedForHttpErrorTimeoutAndRedirect() {
		server.expect(requestTo(SteamOpenIdClient.ENDPOINT)).andRespond(withServerError());
		server.expect(requestTo(SteamOpenIdClient.ENDPOINT))
			.andRespond(withException(new SocketTimeoutException("private upstream detail")));
		server.expect(requestTo(SteamOpenIdClient.ENDPOINT))
			.andRespond(withStatus(org.springframework.http.HttpStatus.FOUND)
				.location(URI.create("https://attacker.example")));
		assertThat(client.verify(assertion())).isEmpty();
		assertThat(client.verify(assertion())).isEmpty();
		assertThat(client.verify(assertion())).isEmpty();
		server.verify();
	}

	private MultiValueMap<String, String> assertion() {
		var values = new LinkedMultiValueMap<String, String>();
		values.set("openid.ns", SteamOpenIdClient.NAMESPACE);
		values.set("openid.mode", "id_res");
		values.set("openid.op_endpoint", SteamOpenIdClient.ENDPOINT);
		values.set("openid.claimed_id", "https://steamcommunity.com/openid/id/76561198000000001");
		values.set("openid.identity", values.getFirst("openid.claimed_id"));
		values.set("openid.return_to", "https://backend.example/api/auth/steam/callback");
		values.set("openid.response_nonce", "2026-09-15T01:00:00Znonce");
		values.set("openid.assoc_handle", "association");
		values.set("openid.signed", "op_endpoint,claimed_id,identity,return_to,response_nonce,assoc_handle");
		values.set("openid.sig", "signature+/=");
		return values;
	}
}
