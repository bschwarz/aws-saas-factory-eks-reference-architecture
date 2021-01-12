/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.amazonaws.saas.eks.auth;

import static com.nimbusds.jose.JWSAlgorithm.RS256;
import static java.util.List.of;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

@Component
public class TokenProcessor {
	private static final Logger logger = LogManager.getLogger(TokenProcessor.class);

	private static final String SAAS_PROVIDER_METADATA = "SAAS_PROVIDER_METADATA";

	@Autowired
	private JwtConfig jwtConfiguration;

	public Authentication authenticate(HttpServletRequest request) throws Exception {
		String idToken = request.getHeader(this.jwtConfiguration.getHttpHeader());
		String origin = request.getHeader("origin");
		logger.info("Origin name => " + origin);

		if (origin == null || origin.equals("http://localhost:4200")) {
			// TODO this is test code and should be deleted unless we create a test tenant
			// with every install
			origin = "http://a5co.aws-dev-shop.com";
		}

		try {
			logger.info("Host name => " + origin);
			URI uri = new URI(origin);
			String domain = uri.toString();
			String[] parts = domain.split("\\.");
			origin = parts[1] + "." + parts[2];
			logger.info("Origin for lookup => " + origin);
		} catch (URISyntaxException ex) {
			logger.error(ex.toString());
		}

		if (idToken != null) {
			String table_name = SAAS_PROVIDER_METADATA;
			String userPoolId = "";
			String region = "";

			AmazonDynamoDB client = AmazonDynamoDBClientBuilder.standard().build();
			DynamoDB dynamoDB = new DynamoDB(client);
			Table table = dynamoDB.getTable(table_name);

			try {
				Item item = table.getItem("DOMAIN_NAME", origin);
				userPoolId = (String) item.get("PROVIDER_USER_POOL_ID");
				region = (String) item.get("REGION");

				logger.info(item.toJSONPretty());
			} catch (Exception e) {
				logger.error("GetItem failed.");
				logger.error(e.getMessage());
			}
			logger.info("userPoolId= " + userPoolId);
			logger.info("region= " + region);

			jwtConfiguration.setUserPoolId(userPoolId);
			jwtConfiguration.setRegion(region);

			String jwkUrl = "https://cognito-idp." + region + ".amazonaws.com/" + userPoolId + "/.well-known/jwks.json";
			jwtConfiguration.setJwkUrl(jwkUrl);
			ResourceRetriever resourceRetriever = new DefaultResourceRetriever(jwtConfiguration.getConnectionTimeout(),
					jwtConfiguration.getReadTimeout());
			URL jwkSetURL = new URL(jwtConfiguration.getJwkUrl());

			JWKSource keySource = new RemoteJWKSet(jwkSetURL, resourceRetriever);
			ConfigurableJWTProcessor jwtProcessor = new DefaultJWTProcessor();
			JWSKeySelector keySelector = new JWSVerificationKeySelector(RS256, keySource);
			jwtProcessor.setJWSKeySelector(keySelector);

			JWTClaimsSet claims = jwtProcessor.process(this.getBearerToken(idToken), null);
			validateIssuer(claims);
			verifyIfIdToken(claims);
			String username = getUserNameFrom(claims);

			if (username != null) {
				List<GrantedAuthority> grantedAuthorities = of(new SimpleGrantedAuthority("ROLE_ADMIN"));
				User user = new User(username, "", of());
				return new JwtAuth(user, claims, grantedAuthorities);
			}
		}
		return null;
	}

	private String getUserNameFrom(JWTClaimsSet claims) {
		return claims.getClaims().get(this.jwtConfiguration.getUserNameField()).toString();
	}

	private void verifyIfIdToken(JWTClaimsSet claims) throws Exception {
		if (!claims.getIssuer().equals(this.jwtConfiguration.getCognitoIdentityPoolUrl())) {
			throw new Exception("JWT Token is not an ID Token");
		}
	}

	private void validateIssuer(JWTClaimsSet claims) throws Exception {
		if (!claims.getIssuer().equals(this.jwtConfiguration.getCognitoIdentityPoolUrl())) {
			throw new Exception(String.format("Issuer %s does not match cognito idp %s", claims.getIssuer(),
					this.jwtConfiguration.getCognitoIdentityPoolUrl()));
		}
	}

	private String getBearerToken(String token) {
		return token.startsWith("Bearer ") ? token.substring("Bearer ".length()) : token;
	}
}