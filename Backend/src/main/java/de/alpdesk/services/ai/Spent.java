package de.alpdesk.services.ai;

import java.math.BigDecimal;

/**
 * What the model calls of one answer, or of one step of a task, used and cost - summed call by call as the usage
 * records them, so a step that fails halfway still knows what it spent. The agent loop counts from the provider
 * client's threads, one round after the other.
 */
public final class Spent {

    private long input;
    private long output;
    private long cacheRead;
    private long cacheWrite;
    private BigDecimal amount = BigDecimal.ZERO;

    public synchronized void add(CallTokens tokens, BigDecimal cost) {
        input += tokens.input() == null ? 0 : tokens.input();
        output += tokens.output();
        cacheRead += tokens.cacheRead();
        cacheWrite += tokens.cacheWrite();
        amount = amount.add(cost);
    }

    public synchronized long input() {
        return input;
    }

    public synchronized long output() {
        return output;
    }

    public synchronized long cacheRead() {
        return cacheRead;
    }

    public synchronized long cacheWrite() {
        return cacheWrite;
    }

    public synchronized BigDecimal amount() {
        return amount;
    }
}
