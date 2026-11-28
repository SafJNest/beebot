# R2 object layout

The `beebot` R2 bucket stores public audio objects and image objects. The
application uses `soundPrefix` for sound keys and the configured CDN base URL
to build public links. Keys stored in MariaDB are relative object keys.

## Sounds

For sound ID `42` with audio extension `mp3`, the audio key is:

```text
sounds/42/42.mp3
```

A sound thumbnail, when present, uses the same ID directory and a generated
UUID filename:

```text
sounds/42/<uuid>.jpg
```

The `sound` row stores the audio and image object keys. Sounds without a
thumbnail have no image object.

## Soundboard thumbnails

Soundboard covers use their own prefix and UUID filenames:

```text
soundboard/thumbnail/<soundboardId>/<uuid>.jpg
```

The soundboard row stores the thumbnail object key. Replacing a cover creates
a new key. Existing objects are retained.

## Configuration and ownership

The bucket name and audio prefix come from `services.r2.soundBucket` and
`services.r2.soundPrefix` in the active YAML configuration. The thumbnail
prefix is currently fixed by `SoundboardThumbnails`. Both media types use the
configured CDN base URL and the same bucket. SQL controls catalog visibility.
The CDN link itself can be opened by anyone who has it.

The layouts correspond to `SoundFiles` and `SoundboardThumbnails`. When either
implementation changes its key format, update this file in the same change.
