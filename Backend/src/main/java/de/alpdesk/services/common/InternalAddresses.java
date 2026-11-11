package de.alpdesk.services.common;

import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Addresses of our own network - loopback, private, link-local (e.g. the cloud's metadata service) and the like. An
 * address a tenant admin enters (connected software, a file store) must not lead there unless the installation names
 * its host (services.mcp.McpAddresses, services.filestore.FileStoreAddresses).
 */
public final class InternalAddresses {

    public static boolean isInternal(InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress() || isUniqueLocal(address)
                || isSharedAddressSpace(address);
    }

    /** IPv6 fc00::/7 - Java counts only the old fec0::/10 as site-local. */
    private static boolean isUniqueLocal(InetAddress address) {
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
    }

    /** 100.64.0.0/10, the carriers' and many clouds' internal range. */
    private static boolean isSharedAddressSpace(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
    }

    private InternalAddresses() {
    }
}
