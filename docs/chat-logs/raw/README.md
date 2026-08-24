# Raw recovered chat export archive

Original upload: `ChatGPT_2026-08-21-15-19-15.txt`

Original SHA-256:

`feb564db2486c2d98a2f6c6b990b21ae2be86622e891dba73f0b3b63e961496b`

The original export is stored byte-for-byte as four ordered text parts because the GitHub connector write path is bounded per request. Reconstruct it by concatenating, in order:

1. `ChatGPT_2026-08-21-15-19-15.part01.txt`
2. `ChatGPT_2026-08-21-15-19-15.part02.txt`
3. `ChatGPT_2026-08-21-15-19-15.part03.txt`
4. `ChatGPT_2026-08-21-15-19-15.part04.txt`

Example:

```sh
cat ChatGPT_2026-08-21-15-19-15.part0{1,2,3,4}.txt > ChatGPT_2026-08-21-15-19-15.txt
sha256sum ChatGPT_2026-08-21-15-19-15.txt
```

Expected reconstructed SHA-256:

`feb564db2486c2d98a2f6c6b990b21ae2be86622e891dba73f0b3b63e961496b`

The smaller companion export is stored directly as `ChatGPT_2026-08-21-15-17-35.txt`; its SHA-256 is:

`569043834af190da9633759bd7b945d91c9950ffdea76db3b40aa6b424aa3719`
