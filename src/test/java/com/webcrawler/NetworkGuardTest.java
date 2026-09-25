package com.webcrawler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class NetworkGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "169.254.169.254", // cloud metadata service
            "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255",
            "::1", "::", "fc00::1", "fd12:3456::1", "fe80::1",
            "::ffff:10.0.0.1",   // IPv4-mapped
            "64:ff9b::a00:1",    // NAT64 form of 10.0.0.1
            "2002:a00:1::1",     // 6to4 form of 10.0.0.1
    })
    void internalAddressesAreBlocked(String literal) throws Exception {
        assertTrue(NetworkGuard.isInternal(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "2606:4700:4700::1111"})
    void publicAddressesAreAllowed(String literal) throws Exception {
        assertFalse(NetworkGuard.isInternal(InetAddress.getByName(literal)), literal);
    }

    @Test
    void verifyRejectsHostnamesThatResolveInternally() {
        NetworkGuard guard = new NetworkGuard(false);
        assertThrows(NetworkGuard.UnsafeAddressException.class, () -> guard.verify(URI.create("http://localhost/")));
        assertThrows(NetworkGuard.UnsafeAddressException.class, () -> guard.verify(URI.create("http://[::1]:8080/")));
    }

    @Test
    void allowPrivateNetworkTurnsTheCheckOff() {
        NetworkGuard guard = new NetworkGuard(true);
        assertDoesNotThrow(() -> guard.verify(URI.create("http://127.0.0.1/")));
    }
}
