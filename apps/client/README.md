# Snipnet client

Kotlin Multiplatform project (desktop first).

- `shared/` – platform-independent code: domain models mirroring `docs/api.md` (`app.snipnet.shared.model`, with the
  `SnipnetJson` configuration), later the API client and editing core.
- `desktopApp/` – Compose Desktop UI (Material 3, dark editor theme).

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew :desktopApp:run     # start the app
./gradlew check               # ktlint + all tests
```

## Structure of `desktopApp`

| Package | Purpose |
|---|---|
| `theme` | `SnipnetTheme` (Material 3 dark scheme) plus `EditorColors` tokens for the timeline (`SnipnetTheme.editor`). Never hardcode colors in features. |
| `nav` | `Screen` (sealed destinations) and `Navigator` (back stack as `StateFlow`). `App` renders the top of the stack. |
| `di` | `AppContainer`, the manual dependency container. |
| `state` | `StateHolder`, the ViewModel-like base class. |
| `window` | Window geometry persistence (`window.json` in the data directory). |
| `ui` | Composables. Screens are placeholders until their features land. |

The data directory is `~/.snipnet`, or `$SNIPNET_DATA_DIR` when set.

## Dependency injection

There is no DI framework. `AppContainer` is created once in `main` and holds long-lived services as `val`s (or
`by lazy`). A feature adds its services there and exposes a factory for its state holder, for example:

```kotlin
class AppContainer(...) {
    val api by lazy { SnipnetApi(baseUrl, tokenStore) }
    fun projectsStateHolder() = ProjectsStateHolder(api)
}
```

Constructors take their dependencies as parameters (interfaces where a fake is useful), so tests build objects by
hand without the container.

## State holders

Every screen with logic gets a `StateHolder<State>`:

- `State` is one immutable data class describing everything the screen shows.
- The holder exposes `state: StateFlow<State>`, changes it only through `update { ... }` and runs work in its
  `scope` (a `SupervisorJob` on the Swing dispatcher).
- The composable creates the holder with `remember`, collects `state` with `collectAsState()`, and calls
  `close()` in a `DisposableEffect` so coroutines stop when the screen leaves the composition.
- Tests pass a `StandardTestDispatcher` as the second constructor argument and drive it with `runTest`.

```kotlin
class ProjectsStateHolder(private val api: SnipnetApi, dispatcher: CoroutineDispatcher = Dispatchers.Main) :
    StateHolder<ProjectsState>(ProjectsState(), dispatcher) {
    fun refresh() {
        scope.launch {
            update { it.copy(loading = true) }
            val items = api.listVideos()
            update { it.copy(loading = false, videos = items) }
        }
    }
}
```

## Navigation

`Navigator.push`, `back` and `resetTo` change the stack; `resetTo` is used for login and logout so back cannot
return to a screen of the previous session. Add a destination by adding a `Screen` subtype and a branch in `App`.
