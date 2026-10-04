package com.nemoclaw.chat

import androidx.navigation.NavHostController

/**
 * Route tipizzate per tab: una route per ogni [Tab], derivata dall'enum
 * esistente (`Tab.name` come route). Nessuna hard-coded string altrove.
 */
internal val Tab.navRoute: String get() = name

internal fun tabForNavRoute(route: String?): Tab =
    runCatching { Tab.valueOf(route ?: Tab.Chat.name) }.getOrDefault(Tab.Chat)

/**
 * Navigazione tra tab con back stack reale di sistema:
 * - push (nessun popUpTo) così il back hardware torna al tab precedente poi esce,
 *   parità con la vecchia `tabHistory.takeLast(10)` (senza limite artificiale);
 * - `launchSingleTop` evita duplicati quando si riseleziona il tab corrente;
 * - `restoreState = true` + SavedStateHandle del back stack preservano lo stato
 *   per-tab (scroll, saveable, ViewModel retained come ChatViewModel/pendingBot).
 * Lo stato `saveState` è fornito dalle back stack entry del NavHost.
 */
internal fun NavHostController.navigateToTab(tab: Tab) {
    val current = currentDestination?.route
    if (current == tab.navRoute) return
    // Riusa l'istanza esistente invece di duplicarla: niente schermate
    // vuote/flash e niente stack infiniti. Il back continua a camminare
    // sulla cronologia restante e poi esce.
    if (!popBackStack(tab.navRoute, inclusive = false)) {
        navigate(tab.navRoute) {
            launchSingleTop = true
            restoreState = true
        }
    }
}

/** Helper testabile: calcola se una navigazione sarebbe no-op (stesso tab). */
internal fun isSameTabNavigation(currentRoute: String?, tab: Tab): Boolean =
    (currentRoute ?: Tab.Chat.name) == tab.navRoute

/** Start destination unica: Chat, come prima del refactor. */
internal val tabNavStartDestination: String get() = Tab.Chat.name
