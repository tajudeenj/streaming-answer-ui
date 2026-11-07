package io.github.tajudeenj.streaming;

/** A policy passage selected to ground an answer, with the relevance score that admitted it. */
public record Source(PolicyChunk chunk, double relevance) {}
