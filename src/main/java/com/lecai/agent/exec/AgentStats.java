package com.lecai.agent.exec;

public class AgentStats {

    private int processed = 0;
    private int safe = 0;
    private int unsafe = 0;

    public synchronized void record(boolean flaggedUnsafe) {
        processed++;
        if (flaggedUnsafe) {
            unsafe++;
        } else {
            safe++;
        }
    }

    public synchronized int processed() {
        return processed;
    }

    public synchronized int safe() {
        return safe;
    }

    public synchronized int unsafe() {
        return unsafe;
    }
}
