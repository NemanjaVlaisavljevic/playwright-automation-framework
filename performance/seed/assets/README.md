# Seed fixture assets

`fixture.png` - a real, valid 1x1 transparent PNG (68 bytes), decoded from the well-known minimal
base64-encoded PNG constant used across the web (`iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAA
C0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=`). Verified as a genuine PNG (magic bytes
`89 50 4E 47 0D 0A 1A 0A`, a valid `IHDR` chunk, confirmed via `file`) - not a zero-byte placeholder
claiming to be one, which would break a real image viewer/thumbnail even though a raw byte
download would still "work."

Copied by `../seed.sh` onto the mounted artifacts volume for five seeded runs, with its own real,
measured byte size (never a hardcoded constant) passed into `../seed.sql` as the matching
`artifacts.size_bytes` value.
