# miniz (vendored)

Upstream: https://github.com/richgel999/miniz
Vendored files: `miniz.c`, `miniz.h`, `miniz_common.h`, `miniz_tdef.{c,h}`,
`miniz_tinfl.{c,h}`, `miniz_zip.{c,h}`, plus a hand-written
`miniz_export.h` (MirageDroid links miniz statically and needs no export
visibility attributes).

License: MIT, see `LICENSE` (Copyright 2010-2014 Rich Geldreich and
Tenacious Software LLC; 2013-2014 RAD Game Tools and Valve Software).

Used by `core/devicelib/src/devlib_ota.c` to read official OTA `.zip`
packages (stored and deflate entries).
