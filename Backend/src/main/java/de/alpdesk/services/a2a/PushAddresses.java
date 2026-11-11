package de.alpdesk.services.a2a;

import de.alpdesk.services.common.InternalAddresses;
import jakarta.inject.Singleton;
import org.a2aproject.sdk.server.tasks.PushNotificationUrlValidator;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Where a push notification may go (docs/architecture/a2a.md "Push notifications"): an address a key's client names -
 * HTTPS only, to a public host. No exemption for the hosts the installation lists for model servers or connected
 * software: a key's client never makes us call into our own network. Checked when the webhook is registered and again
 * before every delivery, where the name is resolved anew.
 */
@Singleton
public class PushAddresses implements PushNotificationUrlValidator {

    @Override
    public void validate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The webhook is no valid address");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("A webhook must be an https address of a public host");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (InternalAddresses.isInternal(address)) {
                    throw new IllegalArgumentException("A webhook must not lead into an internal network");
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("The webhook's host is not known");
        }
    }
}
