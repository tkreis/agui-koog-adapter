package com.agui.community.koog

/** Server-Sent Events framing expected by `@ag-ui/client`: one `data:` line per event, LF only. */
public object SseEncoder {
    public const val CONTENT_TYPE: String = "text/event-stream"

    public fun encode(event: AgUiEvent): String =
        "data: " + AgUiJson.encodeToString(AgUiEvent.serializer(), event) + "\n\n"
}
