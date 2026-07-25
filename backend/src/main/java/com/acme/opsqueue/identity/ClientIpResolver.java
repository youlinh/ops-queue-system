package com.acme.opsqueue.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class ClientIpResolver {
    private static final Pattern IP_LITERAL =
            Pattern.compile("(?:[0-9]{1,3}(?:\\.[0-9]{1,3}){3}|[0-9a-fA-F:]+)");

    private final List<Cidr> trustedProxies;

    public ClientIpResolver(@Value("${TRUSTED_PROXY_CIDRS:}") String trustedProxyCidrs) {
        trustedProxies = Arrays.stream(trustedProxyCidrs.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Cidr::parse)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = canonicalLiteral(request.getRemoteAddr());
        if (peer == null || trustedProxies.stream().noneMatch(cidr -> cidr.contains(peer))) {
            return peer == null ? request.getRemoteAddr() : peer;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.contains(",")) {
            return peer;
        }
        String client = canonicalLiteral(forwarded.trim());
        return client == null ? peer : client;
    }

    private static String canonicalLiteral(String value) {
        if (value == null || !IP_LITERAL.matcher(value).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private record Cidr(byte[] network, int prefixLength) {
        static Cidr parse(String value) {
            String[] parts = value.split("/", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + value);
            }
            String address = canonicalLiteral(parts[0]);
            if (address == null) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + value);
            }
            try {
                byte[] bytes = InetAddress.getByName(address).getAddress();
                int prefix = Integer.parseInt(parts[1]);
                if (prefix < 0 || prefix > bytes.length * 8) {
                    throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + value);
                }
                return new Cidr(bytes, prefix);
            } catch (UnknownHostException | NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "Invalid trusted proxy CIDR: " + value, exception);
            }
        }

        boolean contains(String address) {
            try {
                byte[] candidate = InetAddress.getByName(address).getAddress();
                if (candidate.length != network.length) {
                    return false;
                }
                int fullBytes = prefixLength / 8;
                int remainingBits = prefixLength % 8;
                for (int index = 0; index < fullBytes; index++) {
                    if (candidate[index] != network[index]) {
                        return false;
                    }
                }
                if (remainingBits == 0) {
                    return true;
                }
                int mask = 0xff << (8 - remainingBits);
                return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
            } catch (UnknownHostException exception) {
                return false;
            }
        }
    }
}
