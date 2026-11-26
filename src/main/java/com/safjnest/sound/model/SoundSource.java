package com.safjnest.sound.model;

public record SoundSource(Type type, String id) {
    public enum Type {
        SOUND,
        SOUNDBOARD,
        COMMENT
    }
}
