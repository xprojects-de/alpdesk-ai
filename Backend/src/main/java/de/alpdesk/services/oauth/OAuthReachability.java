package de.alpdesk.services.oauth;

import de.alpdesk.services.common.InternalAddresses;
import jakarta.inject.Singleton;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Whether an authorization server can fetch our client id metadata document (docs/architecture/mcp.md "Our client
 * identity"): decided from the host the admin reaches the installation at right now - public when every address it
 * resolves to is public. An installation inside a company network (a private address, split DNS) is not.
 */
@Singleton
public class OAuthReachability {

    public boolean isPublic(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (InternalAddresses.isInternal(address)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
