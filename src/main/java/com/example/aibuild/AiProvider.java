package com.example.aibuild;

import java.io.IOException;
import java.util.function.Consumer;

/** One AI backend (Claude, Gemini, ...). Blocking: call it off the main thread. */
interface AiProvider {

    /** Short name for logs and chat messages, e.g. the model id. */
    String label();

    /** Sends one system + user prompt and returns the model's text reply. */
    default String complete(String system, String user) throws IOException, InterruptedException {
        return complete(system, user, status -> {});
    }

    /** Sends one system + user prompt with real-time status notifications for debug/failover tracking. */
    String complete(String system, String user, Consumer<String> statusListener) throws IOException, InterruptedException;
}
