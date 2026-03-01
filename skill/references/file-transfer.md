# File Transfer Guide

## Chunked Download (phone → server)

For files larger than 2MB, read in chunks:

```
1. file.info {path: "/sdcard/video.mp4"}
   → {size: 50000000}

2. file.read {path: "/sdcard/video.mp4", offset: 0, size: 2097152}
   → {base64: "...", offset: 0, size: 2097152, total: 50000000, done: false}

3. file.read {path: "...", offset: 2097152, size: 2097152}
   → {base64: "...", done: false}

   ... repeat, incrementing offset by size each time

4. When done: true, all chunks received. Concatenate base64-decoded bytes.
```

Default chunk size: 2MB (2097152 bytes). Max per Gateway message: ~18MB raw (25MB base64).

## Chunked Upload (server → phone)

Write in chunks using append mode:

```
1. file.write {path: "/sdcard/Download/file.zip", base64: "<chunk1>"}
   → First chunk creates the file

2. file.write {path: "/sdcard/Download/file.zip", base64: "<chunk2>", append: true}
   → Subsequent chunks append

3. file.info {path: "/sdcard/Download/file.zip"}
   → Verify final size
```

## Screen Recording Download

Large recordings (>10MB) return a `recordingId` instead of inline base64:

```
1. screen.record {durationMs: 30000}
   → {recordingId: "/data/.../recording_xxx.mp4", sizeBytes: 15000000}

2. file.read {path: "<recordingId>", offset: 0, size: 2097152}
   ... chunked download as above

3. file.delete {path: "<recordingId>"}
   → Cleanup
```

## Accessible Paths

| Path | Content |
|------|---------|
| `/sdcard/` | Root of external storage |
| `/sdcard/DCIM/` | Camera photos/videos |
| `/sdcard/Download/` | Downloads |
| `/sdcard/Pictures/` | Screenshots, saved images |
| `/sdcard/Documents/` | Documents |
| `/sdcard/Music/` | Audio files |
| App cache dir | Recordings, temp files |
