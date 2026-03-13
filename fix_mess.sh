# Oh wait, there are duplicated lines. Let me just use vim or sed directly
cat src/main/kotlin/Context/ContextBank.kt | grep -n "suspend fun emplaceWithMutex"
