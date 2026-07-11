package com.example.website.util;

import com.example.website.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtUtil {

    private final AppProperties props;
    private final SecretKey signingKey;

    public JwtUtil(AppProperties props) {
        this.props = props;
        byte[] keyBytes = Decoders.BASE64.decode(props.getJwt().getSecret());
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(Long userId, String username) {
        return generateToken(userId, username, null);
    }

    public String generateToken(Long userId, String username, String role) {
        return generateToken(userId, username, role, 0);
    }

    public String generateToken(Long userId, String username, String role, int authVersion) {
        long now = System.currentTimeMillis();
        long exp = now + props.getJwt().getExpireMinutes() * 60_000L;
        io.jsonwebtoken.JwtBuilder builder = Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .claim("authVersion", authVersion)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(exp))
                .signWith(signingKey, SignatureAlgorithm.HS256);
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.compact();
    }

    public Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public Long getUserId(String token) {
        return Long.valueOf(parse(token).getSubject());
    }

    public String getUsername(String token) {
        Object v = parse(token).get("username");
        return v == null ? null : v.toString();
    }

    public String getRole(String token) {
        Object v = parse(token).get("role");
        return v == null ? null : v.toString();
    }

    public int getAuthVersion(Claims claims) {
        Object value = claims.get("authVersion");
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }
}
