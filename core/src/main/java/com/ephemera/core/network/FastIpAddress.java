package com.ephemera.core.network;

/**
 * Allocation-free literal IPv4 / IPv6 address detector used by Ephemera's Fast IP Ping path to
 * skip blocking reverse-DNS (PTR) and DNS SRV ({@code _minecraft._tcp}) queries on numeric server
 * addresses.
 */
public final class FastIpAddress {
	private FastIpAddress() {}

	/**
	 * Returns {@code true} if {@code host} is a syntactically valid numeric IPv4 dotted-quad or
	 * IPv6 literal (optionally enclosed in brackets and/or suffixed with a {@code %zone} scope id).
	 * Performs zero heap allocations.
	 */
	public static boolean isNumericIp(String host) {
		if (host == null) {
			return false;
		}
		int len = host.length();
		if (len < 2) {
			return false;
		}
		int start = 0;
		int end = len;
		if (host.charAt(0) == '[') {
			if (host.charAt(len - 1) != ']') {
				return false;
			}
			start = 1;
			end = len - 1;
			if (end - start < 2) {
				return false;
			}
			return isIpv6Literal(host, start, end);
		}
		// IPv6 literals always contain ':'; IPv4 literals contain '.' and only digits/dots.
		if (host.indexOf(':') >= 0) {
			return isIpv6Literal(host, start, end);
		}
		return isIpv4Literal(host, start, end);
	}

	private static boolean isIpv4Literal(String s, int start, int end) {
		int parts = 0;
		int val = 0;
		int digits = 0;
		for (int i = start; i < end; i++) {
			char c = s.charAt(i);
			if (c == '.') {
				if (digits == 0 || val > 255) {
					return false;
				}
				parts++;
				if (parts > 3) {
					return false;
				}
				val = 0;
				digits = 0;
			} else if (c >= '0' && c <= '9') {
				if (digits > 0 && val == 0) {
					// Leading zero in octet (e.g. 01) — defer to system resolver.
					return false;
				}
				val = val * 10 + (c - '0');
				digits++;
				if (digits > 3 || val > 255) {
					return false;
				}
			} else {
				return false;
			}
		}
		return parts == 3 && digits > 0 && val <= 255;
	}

	private static boolean isIpv6Literal(String s, int start, int end) {
		// Strip optional %zoneId (RFC 4007)
		for (int i = start; i < end; i++) {
			if (s.charAt(i) == '%') {
				if (i + 1 >= end) {
					return false;
				}
				end = i;
				break;
			}
		}
		int colons = 0;
		boolean sawDoubleColon = false;
		int hexDigits = 0;
		int groups = 0;
		for (int i = start; i < end; i++) {
			char c = s.charAt(i);
			if (c == ':') {
				colons++;
				if (i + 1 < end && s.charAt(i + 1) == ':') {
					if (sawDoubleColon) {
						return false;
					}
					sawDoubleColon = true;
					if (hexDigits > 0) {
						groups++;
						hexDigits = 0;
					}
					i++;
					if (i + 1 < end && s.charAt(i + 1) == ':') {
						return false; // triple colon
					}
				} else {
					if (i == start || i + 1 == end || hexDigits == 0) {
						return false;
					}
					groups++;
					hexDigits = 0;
				}
			} else if (c == '.') {
				// IPv4-mapped IPv6 tail (e.g. ::ffff:192.0.2.128)
				int v4Start = i - hexDigits;
				if (colons < 2 || hexDigits == 0 || !isIpv4Literal(s, v4Start, end)) {
					return false;
				}
				groups += 2;
				hexDigits = 0;
				break;
			} else if (isHex(c)) {
				hexDigits++;
				if (hexDigits > 4) {
					return false;
				}
			} else {
				return false;
			}
		}
		if (hexDigits > 0) {
			groups++;
		}
		if (colons == 0) {
			return false;
		}
		return sawDoubleColon ? groups < 8 : groups == 8;
	}

	private static boolean isHex(char c) {
		return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	}
}
