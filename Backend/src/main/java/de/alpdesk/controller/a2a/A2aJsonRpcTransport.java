package de.alpdesk.controller.a2a;

import org.a2aproject.sdk.server.TransportMetadata;
import org.a2aproject.sdk.spec.TransportProtocol;

/**
 * Tells the A2A SDK that this server serves the JSON-RPC binding ({@link A2aController}) - the SDK checks every agent
 * card against the transports found through the service loader, as the SDK's own reference servers declare theirs
 * (META-INF/services/org.a2aproject.sdk.server.TransportMetadata).
 */
public class A2aJsonRpcTransport implements TransportMetadata {

    @Override
    public String getTransportProtocol() {
        return TransportProtocol.JSONRPC.asString();
    }
}
