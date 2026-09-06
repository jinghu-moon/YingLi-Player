package seeyuer.yingli.player.domain.navigation

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RootDestination {
    HOME,
    LIBRARY,
    ORGANIZE,
    PROCESSING,
}

enum class GlobalAppAction {
    OPEN_PROCESSING,
    OPEN_SETTINGS,
    OPEN_HOME_STATS,
}

sealed interface AppRoute {
    data class Root(val destination: RootDestination) : AppRoute

    data object Processing : AppRoute

    data object Settings : AppRoute

    data object HomeStats : AppRoute

    data object Vault : AppRoute

    data class Detail(
        val mediaId: String,
        val source: RootDestination,
    ) : AppRoute

    data class Player(
        val mediaId: String,
        val source: RootDestination,
    ) : AppRoute

    data class VaultPlayer(val itemId: String) : AppRoute

    data object AppLock : AppRoute
}

data class NavigationState(
    val currentRoot: RootDestination = RootDestination.HOME,
    val lastStandardRoot: RootDestination = RootDestination.HOME,
    val stacks: Map<RootDestination, List<AppRoute>> = initialStacks(),
    val overlay: AppRoute? = null,
) {
    val currentRoute: AppRoute
        get() = overlay ?: stacks[currentRoot].orEmpty().lastOrNull() ?: AppRoute.Root(currentRoot)

    val primaryDestinations: List<RootDestination>
        get() = listOf(RootDestination.HOME, RootDestination.LIBRARY, RootDestination.ORGANIZE)

    val showPrimaryNavigation: Boolean
        get() = currentRoute !is AppRoute.Player && currentRoute !is AppRoute.VaultPlayer &&
            currentRoute !is AppRoute.AppLock

    val canNavigateBack: Boolean
        get() = overlay != null || stacks[currentRoot].orEmpty().size > 1

    companion object {
        fun initialStacks(): Map<RootDestination, List<AppRoute>> =
            RootDestination.entries.associateWith { destination -> listOf(AppRoute.Root(destination)) }
    }
}

interface NavigationStateStore {
    val state: StateFlow<NavigationState>

    fun selectRoot(destination: RootDestination)

    fun openGlobalAction(action: GlobalAppAction)

    fun openDetail(mediaId: String)

    fun openPlayer(mediaId: String)

    fun openAppLock()

    fun openVault()

    fun openVaultPlayer(itemId: String)

    fun navigateDeepLink(route: String)

    fun navigateBack(): Boolean

}

class SavedStateNavigationStateStore(
    private val savedStateHandle: SavedStateHandle,
) : NavigationStateStore {
    private val mutableState = MutableStateFlow(restore())
    override val state: StateFlow<NavigationState> = mutableState.asStateFlow()

    override fun selectRoot(destination: RootDestination) {
        val current = mutableState.value
        if (destination == RootDestination.PROCESSING) return
        if (destination == current.currentRoot && current.overlay == null) return
        val lastStandard = if (destination == RootDestination.PROCESSING) {
            current.lastStandardRoot
        } else {
            destination
        }
        update(current.copy(currentRoot = destination, lastStandardRoot = lastStandard, overlay = null))
    }

    override fun openGlobalAction(action: GlobalAppAction) {
        val route = when (action) {
            GlobalAppAction.OPEN_PROCESSING -> AppRoute.Processing
            GlobalAppAction.OPEN_SETTINGS -> AppRoute.Settings
            GlobalAppAction.OPEN_HOME_STATS -> AppRoute.HomeStats
        }
        if (mutableState.value.overlay == route) return
        update(mutableState.value.copy(overlay = route))
    }

    override fun openDetail(mediaId: String) {
        if (!mediaId.isStableRouteId()) return
        push(AppRoute.Detail(mediaId = mediaId, source = mutableState.value.currentRoot))
    }

    override fun openPlayer(mediaId: String) {
        if (!mediaId.isStableRouteId()) return
        push(AppRoute.Player(mediaId = mediaId, source = mutableState.value.currentRoot))
    }

    override fun openAppLock() {
        push(AppRoute.AppLock)
    }

    override fun openVault() {
        if (mutableState.value.overlay == AppRoute.Vault) return
        update(mutableState.value.copy(overlay = AppRoute.Vault))
    }

    override fun openVaultPlayer(itemId: String) {
        if (!itemId.isStableRouteId() || mutableState.value.overlay != AppRoute.Vault) return
        val current = mutableState.value
        val stack = current.stacks[current.currentRoot].orEmpty()
        val withVault = if (stack.lastOrNull() == AppRoute.Vault) stack else stack + AppRoute.Vault
        update(
            current.copy(
                overlay = null,
                stacks = current.stacks + (current.currentRoot to (withVault + AppRoute.VaultPlayer(itemId))),
            ),
        )
    }

    override fun navigateDeepLink(route: String) {
        when (route.trim('/')) {
            "home" -> selectRoot(RootDestination.HOME)
            "library" -> selectRoot(RootDestination.LIBRARY)
            "organize" -> selectRoot(RootDestination.ORGANIZE)
            "processing" -> openGlobalAction(GlobalAppAction.OPEN_PROCESSING)
            "settings" -> openGlobalAction(GlobalAppAction.OPEN_SETTINGS)
            "home-stats" -> openGlobalAction(GlobalAppAction.OPEN_HOME_STATS)
            "lock" -> openAppLock()
            "vault" -> openVault()
            else -> {
                val segments = route.trim('/').split('/')
                when {
                    segments.size == 2 && segments[0] == "detail" -> openDetail(segments[1])
                    segments.size == 2 && segments[0] == "player" -> openPlayer(segments[1])
                    else -> update(NavigationState())
                }
            }
        }
    }

    override fun navigateBack(): Boolean {
        val current = mutableState.value
        if (current.overlay != null) {
            update(current.copy(overlay = null))
            return true
        }
        val stack = current.stacks[current.currentRoot].orEmpty()
        if (stack.size <= 1) return false
        update(
            current.copy(
                stacks = current.stacks + (current.currentRoot to stack.dropLast(1)),
            ),
        )
        return true
    }

    private fun push(route: AppRoute) {
        val current = mutableState.value
        val stack = current.stacks[current.currentRoot].orEmpty()
        if (stack.lastOrNull() == route) return
        update(
            current.copy(
                overlay = null,
                stacks = current.stacks + (current.currentRoot to (stack + route)),
            ),
        )
    }

    private fun update(state: NavigationState) {
        mutableState.value = state
        savedStateHandle[CURRENT_ROOT_KEY] = state.currentRoot.name
        savedStateHandle[LAST_STANDARD_ROOT_KEY] = state.lastStandardRoot.name
        savedStateHandle[OVERLAY_KEY] = state.overlay?.encode()
        state.stacks.forEach { (root, routes) ->
            savedStateHandle[stackKey(root)] = ArrayList(routes.map { route -> route.encode() })
        }
    }

    private fun restore(): NavigationState {
        val currentRoot = savedStateHandle.get<String>(CURRENT_ROOT_KEY).toRootOrNull() ?: RootDestination.HOME
        val lastStandard = savedStateHandle.get<String>(LAST_STANDARD_ROOT_KEY).toRootOrNull()
            ?.takeUnless { it == RootDestination.PROCESSING }
            ?: RootDestination.HOME
        val safeCurrentRoot = if (currentRoot == RootDestination.PROCESSING) lastStandard else currentRoot
        val stacks = RootDestination.entries.associateWith { root ->
            savedStateHandle.get<ArrayList<String>>(stackKey(root))
                ?.mapNotNull { route -> route.decodeRoute() }
                ?.takeIf { routes -> routes.firstOrNull() == AppRoute.Root(root) }
                ?: listOf(AppRoute.Root(root))
        }
        return NavigationState(
            currentRoot = safeCurrentRoot,
            lastStandardRoot = lastStandard,
            stacks = stacks,
            overlay = savedStateHandle.get<String>(OVERLAY_KEY)?.decodeRoute(),
        )
    }

    private fun AppRoute.encode(): String = when (this) {
        is AppRoute.Root -> "root:${destination.name}"
        AppRoute.Processing -> "processing"
        AppRoute.Settings -> "settings"
        AppRoute.HomeStats -> "home-stats"
        AppRoute.Vault -> "vault"
        is AppRoute.Detail -> "detail:${source.name}:$mediaId"
        is AppRoute.Player -> "player:${source.name}:$mediaId"
        is AppRoute.VaultPlayer -> "vault-player:$itemId"
        AppRoute.AppLock -> "lock"
    }

    private fun String.decodeRoute(): AppRoute? {
        val segments = split(':')
        return when {
            this == "processing" -> AppRoute.Processing
            this == "settings" -> AppRoute.Settings
            this == "home-stats" -> AppRoute.HomeStats
            this == "vault" -> AppRoute.Vault
            this == "lock" -> AppRoute.AppLock
            segments.size == 2 && segments[0] == "root" ->
                segments[1].toRootOrNull()?.let(AppRoute::Root)
            segments.size == 3 && segments[0] == "detail" && segments[2].isStableRouteId() ->
                segments[1].toRootOrNull()?.let { source -> AppRoute.Detail(segments[2], source) }
            segments.size == 3 && segments[0] == "player" && segments[2].isStableRouteId() ->
                segments[1].toRootOrNull()?.let { source -> AppRoute.Player(segments[2], source) }
            segments.size == 2 && segments[0] == "vault-player" && segments[1].isStableRouteId() ->
                AppRoute.VaultPlayer(segments[1])
            else -> null
        }
    }

    private fun String?.toRootOrNull(): RootDestination? =
        RootDestination.entries.firstOrNull { it.name == this }

    private fun String.isStableRouteId(): Boolean = matches(STABLE_ID_PATTERN)

    private companion object {
        const val CURRENT_ROOT_KEY = "navigation.current_root"
        const val LAST_STANDARD_ROOT_KEY = "navigation.last_standard_root"
        const val OVERLAY_KEY = "navigation.overlay"
        val STABLE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")

        fun stackKey(root: RootDestination): String = "navigation.stack.${root.name.lowercase()}"
    }
}
