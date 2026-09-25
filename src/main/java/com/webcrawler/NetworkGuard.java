package com.webcrawler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;

/**
 * Refuses to contact addresses inside private networks: loopback, LAN ranges, link-local
 * (including the 169.254.169.254 cloud metadata service) and similar. Without this, any page
 * could point the crawler at your router, services on your machine, or cloud credentials.
 */
final class NetworkGuard {

    static final class UnsafeAddressException extends IOException {
        UnsafeAddressException(String message) {
            super(message);
        }
    }

    private static final byte[] NAT64_PREFIX = {0, 0x64, (byte) 0xff, (byte) 0x9b, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final byte[] IPV4_COMPATIBLE_PREFIX = new byte[12];

    private final boolean allowPrivateNetwork;

    NetworkGuard(boolean allowPrivateNetwork) {
        this.allowPrivateNetwork = allowPrivateNetwork;
    }

    /**
     * Throws if the host resolves to any internal address. The JVM caches successful DNS lookups
     * (30 seconds by default), so a request made right after this check connects to the addresses
     * it approved, even if the DNS record is switched to an internal one in between.
     *
     * @throws UnsafeAddressException if the host is internal
     * @throws java.net.UnknownHostException if the host doesn't resolve
     */
    void verify(URI url) throws IOException {
        if (allowPrivateNetwork) {
            return;
        }
        for (InetAddress address : InetAddress.getAllByName(url.getHost())) {
            if (isInternal(address)) {
                throw new UnsafeAddressException("blocked: " + url.getHost()
                        + " is a private network address (" + address.getHostAddress() + ")");
            }
        }
    }

    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isInternalIpv4(bytes);
        }
        if ((bytes[0] & 0xfe) == 0xfc) {
            return true; // fc00::/7 unique local
        }
        // IPv6 forms that carry an IPv4 address inside them: check that IPv4 address.
        if (Arrays.equals(bytes, 0, 12, NAT64_PREFIX, 0, 12)
                || Arrays.equals(bytes, 0, 12, IPV4_COMPATIBLE_PREFIX, 0, 12)) {
            return isInternalIpv4(Arrays.copyOfRange(bytes, 12, 16));
        }
        if ((bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x02) {
            return isInternalIpv4(Arrays.copyOfRange(bytes, 2, 6)); // 2002::/16 6to4
        }
        return false;
    }

    private static boolean isInternalIpv4(byte[] b) {
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        return first == 0                                               // 0.0.0.0/8 "this network"
                || first == 10                                          // 10.0.0.0/8
                || first == 127                                         // loopback
                || (first == 100 && second >= 64 && second <= 127)     // 100.64.0.0/10 carrier-grade NAT
                || (first == 169 && second == 254)                     // link-local, cloud metadata
                || (first == 172 && second >= 16 && second <= 31)      // 172.16.0.0/12
                || (first == 192 && second == 168)                     // 192.168.0.0/16
                || (first == 192 && second == 0 && (b[2] & 0xff) == 0) // 192.0.0.0/24 protocol assignments
                || (first == 198 && (second == 18 || second == 19))    // 198.18.0.0/15 benchmarking
                || first >= 224;                                        // multicast, reserved, broadcast
    }
}
