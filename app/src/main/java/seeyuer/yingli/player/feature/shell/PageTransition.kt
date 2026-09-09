package seeyuer.yingli.player.feature.shell

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import seeyuer.yingli.player.domain.navigation.AppRoute
import seeyuer.yingli.player.domain.navigation.RootDestination

internal enum class PageTransitionDirection {
    FORWARD,
    BACKWARD,
}

internal fun pageTransitionDirection(initial: AppRoute, target: AppRoute): PageTransitionDirection {
    val initialRoot = initial.rootDestinationOrNull()
    val targetRoot = target.rootDestinationOrNull()
    if (initialRoot != null && targetRoot != null && initialRoot != targetRoot) {
        return if (targetRoot.primaryIndex() > initialRoot.primaryIndex()) {
            PageTransitionDirection.FORWARD
        } else {
            PageTransitionDirection.BACKWARD
        }
    }
    return when {
        initial is AppRoute.Root && target !is AppRoute.Root -> PageTransitionDirection.FORWARD
        initial !is AppRoute.Root && target is AppRoute.Root -> PageTransitionDirection.BACKWARD
        else -> PageTransitionDirection.FORWARD
    }
}

internal fun pageContentTransform(initial: AppRoute, target: AppRoute): ContentTransform {
    val forward = pageTransitionDirection(initial, target) == PageTransitionDirection.FORWARD
    val enter: EnterTransition = slideInHorizontally { width -> pageEnterOffset(width, forward) } + fadeIn()
    val exit: ExitTransition = slideOutHorizontally { width -> pageExitOffset(width, forward) } + fadeOut()
    return enter.togetherWith(exit)
}

internal fun pageEnterOffset(width: Int, forward: Boolean): Int = if (forward) width else -width

internal fun pageExitOffset(width: Int, forward: Boolean): Int = if (forward) -width else width

private fun AppRoute.rootDestinationOrNull(): RootDestination? = when (this) {
    is AppRoute.Root -> destination
    is AppRoute.Detail -> source
    is AppRoute.Player -> source
    AppRoute.Processing,
    AppRoute.Settings,
    AppRoute.HomeStats,
    AppRoute.Vault,
    is AppRoute.VaultPlayer,
    AppRoute.AppLock,
    -> null
}

private fun RootDestination.primaryIndex(): Int = when (this) {
    RootDestination.HOME -> 0
    RootDestination.LIBRARY -> 1
    RootDestination.ORGANIZE -> 2
    RootDestination.PROCESSING -> 3
}
