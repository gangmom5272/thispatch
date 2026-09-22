package com.ssafy.thispatch.domain.patch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.patch-search")
public record PatchSearchProperties(@DefaultValue("100") int efSearch) {

	public PatchSearchProperties {
		if (efSearch < 1 || efSearch > 1000) {
			throw new IllegalArgumentException("app.patch-search.ef-search must be an integer between 1 and 1000");
		}
	}
}
