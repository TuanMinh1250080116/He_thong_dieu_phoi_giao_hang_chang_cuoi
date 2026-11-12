package vn.edu.finalism.logistics.modules.identityaccess.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import vn.edu.finalism.logistics.modules.identityaccess.application.TokenSettings;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.AccessTokenIssuer;

@Component
class JwtAccessTokenIssuer implements AccessTokenIssuer {

	static final String ISSUER = "logistics";

	private final JwtEncoder encoder;
	private final TokenSettings settings;

	JwtAccessTokenIssuer(JwtEncoder encoder, TokenSettings settings) {
		this.encoder = encoder;
		this.settings = settings;
	}

	@Override
	public AccessToken issue(UUID userId, Set<String> roles, Instant now) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(ISSUER)
				.subject(userId.toString())
				.issuedAt(now)
				.expiresAt(now.plus(settings.accessTtl()))
				.id(UUID.randomUUID().toString())
				.claim("roles", List.copyOf(roles))
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new AccessToken(value, settings.accessTtl().toSeconds());
	}
}
