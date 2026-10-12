Server Performance Bar for Minecraft Java 26.3 (Fabric) - SOURCE CODE ONLY

Commands for every player (no OP needed):
/tps - toggle your own bossbar
/tps on - show your own bossbar
/tps off - hide your own bossbar

Preferences persist in config/server-performance-bar.properties, keyed by player UUID.
By default the bossbar is visible. Other players are not affected.
Displays TPS, MSPT and Java heap RAM usage, with TPS-dependent bar color.

Build: install compatible Java and Gradle; run gradle build. The compiled jar (if build succeeds) is in build/libs.
NOTE: Source has NOT been compiled or tested against Minecraft 26.3. Check dependency versions and mappings before building.
