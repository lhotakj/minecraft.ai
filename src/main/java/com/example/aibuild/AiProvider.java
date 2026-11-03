package com.example.aibuild;

import java.io.IOException;

/** One AI backend (Claude, Gemini, ...). Blocking: call it off the main thread. */
interface AiProvider {

    /** Short name for logs and chat messages, e.g. the model id. */
    String label();

    /** Sends one system + user prompt and returns the model's text reply. */
    String complete(String system, String user) throws IOException, InterruptedException;
}
