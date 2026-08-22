# Nows Remapper

Shared local Minecraft JAR remapping library for Nows installer and Gradle tooling.

`NowsRemapper` reads Mojang's official client ProGuard mappings and remaps an
official Minecraft client JAR on the user's machine when a target Minecraft
version still ships obfuscated runtime names.

It does not publish, host or embed Minecraft client JARs. Remapped Minecraft
JARs are local install or local development outputs only.

The artifact is published as:

```text
space.nows.mcnows:nows-remapper:<nows-version>
```

Nows source is licensed under Apache License 2.0. Minecraft client artifacts and
official mappings remain owned by Mojang/Microsoft and are resolved from the
user's local installation or Mojang's official services.
