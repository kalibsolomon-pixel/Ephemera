package com.ephemera.core.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastIpAddressTest {
	@Test
	void recognizesValidIpv4Literals() {
		assertTrue(FastIpAddress.isNumericIp("127.0.0.1"));
		assertTrue(FastIpAddress.isNumericIp("0.0.0.0"));
		assertTrue(FastIpAddress.isNumericIp("192.168.1.254"));
		assertTrue(FastIpAddress.isNumericIp("255.255.255.255"));
	}

	@Test
	void recognizesValidIpv6Literals() {
		assertTrue(FastIpAddress.isNumericIp("::1"));
		assertTrue(FastIpAddress.isNumericIp("[::1]"));
		assertTrue(FastIpAddress.isNumericIp("2001:db8::1"));
		assertTrue(FastIpAddress.isNumericIp("[2001:db8:85a3::8a2e:370:7334]"));
		assertTrue(FastIpAddress.isNumericIp("fe80::1%eth0"));
		assertTrue(FastIpAddress.isNumericIp("::ffff:192.0.2.128"));
		assertTrue(FastIpAddress.isNumericIp("2001:0db8:0000:0000:0000:ff00:0042:8329"));
	}

	@Test
	void rejectsHostnamesAndMalformedIps() {
		assertFalse(FastIpAddress.isNumericIp(null));
		assertFalse(FastIpAddress.isNumericIp(""));
		assertFalse(FastIpAddress.isNumericIp("localhost"));
		assertFalse(FastIpAddress.isNumericIp("play.hypixel.net"));
		assertFalse(FastIpAddress.isNumericIp("127.0.0.1.example.com"));
		assertFalse(FastIpAddress.isNumericIp("256.0.0.1"));
		assertFalse(FastIpAddress.isNumericIp("1.2.3"));
		assertFalse(FastIpAddress.isNumericIp("1.2.3.4.5"));
		assertFalse(FastIpAddress.isNumericIp("127.0.0.01"));
		assertFalse(FastIpAddress.isNumericIp("[127.0.0.1]"));
		assertFalse(FastIpAddress.isNumericIp("2001:::1"));
		assertFalse(FastIpAddress.isNumericIp("2001::1::2"));
		assertFalse(FastIpAddress.isNumericIp("fe80::1%"));
	}
}
