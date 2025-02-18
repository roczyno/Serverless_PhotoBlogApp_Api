package com.roczyno.aws.photoblogapp.util;

import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class TokenUtils {
	private static final String COGNITO_POOL_ID = System.getenv("PB_COGNITO_USER_POOL_ID");
	private static final String AWS_REGION = System.getenv("AWS_REGION");
	private static final String JWK_URL_FORMAT = "https://cognito-idp.%s.amazonaws.com/%s/.well-known/jwks.json";

	private static final JWKSet jwkSet;

	static {
		if (COGNITO_POOL_ID == null || AWS_REGION == null) {
			throw new IllegalStateException("COGNITO_POOL_ID and AWS_REGION environment variables must be set");
		}

		try {
			String jwkUrl = String.format(JWK_URL_FORMAT, AWS_REGION, COGNITO_POOL_ID);
			jwkSet = JWKSet.load(new URL(jwkUrl));
		} catch (Exception e) {
			throw new IllegalStateException("Failed to load JWK Set", e);
		}
	}

	public static Map<String, String> extractUserDetails(String token) {
		if (token == null || token.trim().isEmpty()) {
			throw new IllegalArgumentException("Token cannot be null or empty");
		}

		try {
			SignedJWT signedJWT = SignedJWT.parse(token);
			JWSHeader header = signedJWT.getHeader();
			JWTClaimsSet claimsSet = signedJWT.getJWTClaimsSet();

			// Verify token hasn't expired
			if (claimsSet.getExpirationTime().getTime() < System.currentTimeMillis()) {
				throw new SecurityException("Token has expired", null);
			}

			// Verify issuer
			String issuer = String.format("https://cognito-idp.%s.amazonaws.com/%s", AWS_REGION, COGNITO_POOL_ID);
			if (!issuer.equals(claimsSet.getIssuer())) {
				throw new SecurityException("Invalid token issuer", null);
			}

			// Verify signature
			JWK jwk = jwkSet.getKeyByKeyId(header.getKeyID());
			if (jwk == null) {
				throw new SecurityException("Unable to find JWK", null);
			}

			// Extract user details from claims
			Map<String, String> userDetails = new HashMap<>();

			// Get custom userId
			String userId = claimsSet.getStringClaim("custom:userId");
			if (userId == null || userId.trim().isEmpty()) {
				throw new SecurityException("Token missing required custom:userId claim", null);
			}
			userDetails.put("userId", userId);

			// Get email
			String email = claimsSet.getStringClaim("email");
			if (email == null || email.trim().isEmpty()) {
				throw new SecurityException("Token missing required email claim", null);
			}
			userDetails.put("email", email);

			// Get full name
			String fullName = claimsSet.getStringClaim("name");
			if (fullName != null && !fullName.trim().isEmpty()) {
				String[] nameParts = fullName.split(" ", 2);
				userDetails.put("firstName", nameParts[0]);
				userDetails.put("lastName", nameParts.length > 1 ? nameParts[1] : "");
			} else {
				userDetails.put("firstName", "");
				userDetails.put("lastName", "");
			}

			return userDetails;

		} catch (Exception e) {
			throw new SecurityException("Failed to verify token", e);
		}
	}

	public static class SecurityException extends RuntimeException {
		public SecurityException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
