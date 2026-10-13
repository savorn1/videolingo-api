package com.example.videolingo.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// "Make again": a video made from audio is rendered once more with the settings of its last render
// (the job's parameters), as long as the uploads it was made from are still kept.
public final class RemakeRules {

    /** How long an upload made for an edit or a video from audio is kept (then deleted with the stale uploads). */
    public static final int UPLOADS_KEPT_HOURS = PipelineSteps.CLIP_HOURS;

    /** Not redone: the video already has its transcript (and the file subtitles were burned into is kept as a version). */
    static final Set<String> NOT_REDONE = Set.of("transcribe", "burnSubtitles");

    private RemakeRules() {}

    /** Whether a job's parameters are those of a render from audio. */
    public static boolean isFromAudio(JsonNode params) {
        return params != null
                && "AUDIO_TO_VIDEO".equals(params.path("operation").asText(null));
    }

    /** The parameters for making the video again: the last render's, without what isn't redone, marked as a remake. */
    public static ObjectNode remakeParams(JsonNode original) {
        if (!isFromAudio(original) || !original.isObject()) {
            throw new IllegalArgumentException("Not a render from audio");
        }
        ObjectNode copy = ((ObjectNode) original).deepCopy();
        NOT_REDONE.forEach(copy::remove);
        copy.put("remake", true);
        return copy;
    }

    /** The uploads a render reads: the sound, the cover, each slideshow picture and the logo. */
    public static List<String> uploadKeys(JsonNode params) {
        List<String> keys = new ArrayList<>();
        add(keys, params.path("audioKey"));
        add(keys, params.path("coverKey"));
        for (JsonNode slide : params.path("slides")) {
            add(keys, slide.path("key"));
        }
        add(keys, params.path("logoKey"));
        return keys.stream().distinct().toList();
    }

    private static void add(List<String> keys, JsonNode node) {
        if (node.isTextual() && !node.asText().isBlank()) {
            keys.add(node.asText());
        }
    }
}
