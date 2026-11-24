package com.airachabanda.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.airachabanda.app.AIRachabandaApplication
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.core.model.ResearchMode
import kotlinx.coroutines.launch

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination("home", "Home", Icons.Outlined.Home),
    Destination("council", "AI Council", Icons.Outlined.Forum),
    Destination("activity", "Activity", Icons.Outlined.Timeline),
    Destination("history", "History", Icons.Outlined.History),
    Destination("settings", "Settings", Icons.Outlined.Settings),
)

@Composable
fun AIRachabandaApp(
    application: AIRachabandaApplication,
    expanded: Boolean,
    useRail: Boolean,
    sharedText: String?,
) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val factory = remember { appViewModelFactory(application.container) }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val topLevelRoute = destinations.firstOrNull { destination ->
        currentRoute == destination.route || currentRoute?.startsWith("${destination.route}/") == true
    }?.route
    val showPrimaryNav = topLevelRoute != null || currentRoute == null
    LaunchedEffect(sharedText) {
        if (sharedText.isNullOrBlank()) return@LaunchedEffect
        val route = navController.currentDestination?.route
        if (route?.startsWith("import") == true) return@LaunchedEffect
        navController.navigate("import?provider=${ProviderId.CLAUDE.name}&task=&conversation=") {
            launchSingleTop = true
        }
    }

    fun notice(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showPrimaryNav && !useRail) {
                NavigationBar {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = topLevelRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Row {
            if (showPrimaryNav && useRail) {
                NavigationRail {
                    destinations.forEach { destination ->
                        NavigationRailItem(
                            selected = topLevelRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
            NavHost(
                navController = navController,
                startDestination = if (sharedText != null) {
                    "import?provider=${ProviderId.CLAUDE.name}&task=&conversation="
                } else {
                    "home"
                },
                modifier = Modifier.weight(1f),
            ) {
                composable("home") {
                    val model: HomeViewModel = viewModel(factory = factory)
                    val providers by model.providers.collectAsState()
                    HomeScreen(
                        providers = providers,
                        onOpenUrl = { url -> notice(ExternalLinks.openHttps(application, url)) },
                        onOpenCouncil = { navController.navigate("council") },
                        onOpenAccount = { id -> navController.navigate("account/${id.name}") },
                        contentPadding = padding,
                    )
                }
                composable("council") { CouncilRoute(application, factory, expanded, padding, ::notice, navController) }
                composable(
                    "council/{conversationId}",
                    arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
                ) { entry ->
                    CouncilRoute(
                        application,
                        factory,
                        expanded,
                        padding,
                        ::notice,
                        navController,
                        entry.arguments?.getString("conversationId"),
                    )
                }
                composable("activity") {
                    val model: ActivityViewModel = viewModel(factory = factory)
                    val events by model.events.collectAsState()
                    ActivityScreen(events, padding)
                }
                composable("history") {
                    val model: HistoryViewModel = viewModel(factory = factory)
                    val conversations by model.conversations.collectAsState()
                    HistoryScreen(
                        conversations = conversations,
                        onCreate = { title ->
                            model.create(title, ResearchMode.STANDARD) { id ->
                                navController.navigate("council/$id")
                            }
                        },
                        onOpen = { id -> navController.navigate("council/$id") },
                        onRename = model::rename,
                        onDelete = model::delete,
                        contentPadding = padding,
                    )
                }
                composable("settings") {
                    SettingsRoot(onOpen = { navController.navigate("settings/$it") }, contentPadding = padding)
                }
                composable("settings/{section}") { entry ->
                    val model: SettingsViewModel = viewModel(factory = factory)
                    val accounts: AccountViewModel = viewModel(factory = factory)
                    val providers by accounts.providers.collectAsState()
                    val appearance by model.appearance.collectAsState()
                    val mode by model.researchMode.collectAsState()
                    val rounds by model.maxRounds.collectAsState()
                    val openRouterStatus by model.openRouterStatus.collectAsState()
                    val directCredentials by model.directCredentials.collectAsState()
                    val enabled by model.enabledParticipants.collectAsState()
                    val geminiVerification by model.geminiVerification.collectAsState()
                    SettingsDetail(
                        section = entry.arguments?.getString("section").orEmpty(),
                        providers = providers,
                        appearance = appearance,
                        researchMode = mode,
                        maxRounds = rounds,
                        onAppearance = model::setAppearance,
                        onResearchMode = model::setResearchMode,
                        onMaxRounds = model::setMaxRounds,
                        onOpenAccount = { id -> navController.navigate("account/${id.name}") },
                        onDelete = { model.deleteLocalData { notice("Local data deleted.") } },
                        openRouterStatus = openRouterStatus,
                        onSaveOpenRouter = { secret -> model.saveOpenRouterKey(secret) { message -> notice(message) } },
                        onRemoveOpenRouter = { model.removeOpenRouterKey { message -> notice(message) } },
                        onConfirmOpenRouterMetadata = { model.confirmOpenRouterMetadata { message -> notice(message) } },
                        cloudflareReady = directCredentials.first,
                        geminiReady = directCredentials.second,
                        onSaveCloudflare = { account, secret -> model.saveCloudflare(account, secret) { message -> notice(message) } },
                        onRemoveCloudflare = { model.removeCloudflare { message -> notice(message) } },
                        onSaveGemini = { secret -> model.saveGemini(secret) { message -> notice(message) } },
                        onRemoveGemini = { model.removeGemini { message -> notice(message) } },
                        geminiVerification = geminiVerification,
                        onVerifyGemini = { token, done -> model.verifyGemini(token, done) },
                        onInferGemini = { token, done -> model.inferGemini(token, done) },
                        enabledProviders = enabled,
                        onToggleParticipant = model::setParticipantEnabled,
                        contentPadding = padding,
                    )
                }
                composable(
                    "account/{providerId}",
                    arguments = listOf(navArgument("providerId") { type = NavType.StringType }),
                ) { entry ->
                    val model: AccountViewModel = viewModel(factory = factory)
                    val providers by model.providers.collectAsState()
                    val id = entry.arguments?.getString("providerId")?.let { runCatching { ProviderId.valueOf(it) }.getOrNull() }
                    val config = providers.firstOrNull { it.providerId == id }
                    if (config == null) {
                        EmptyPanel("Account details are still loading.", Modifier.padding(padding))
                    } else {
                        AccountEditor(config, onSave = { choice, plan ->
                            model.save(config.providerId, choice, plan)
                            notice("Declaration saved. No capability was granted.")
                        }, padding)
                    }
                }
                composable(
                    "import?provider={provider}&task={task}&conversation={conversation}",
                    arguments = listOf(
                        navArgument("provider") { type = NavType.StringType; defaultValue = ProviderId.CLAUDE.name },
                        navArgument("task") { type = NavType.StringType; defaultValue = "" },
                        navArgument("conversation") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { entry ->
                    ImportRoute(
                        application,
                        factory,
                        sharedText,
                        entry.arguments?.getString("provider"),
                        entry.arguments?.getString("task"),
                        entry.arguments?.getString("conversation"),
                        padding,
                        ::notice,
                    )
                }
            }
        }
    }
}

@Composable
private fun CouncilRoute(
    application: AIRachabandaApplication,
    factory: ViewModelProviderFactory,
    expanded: Boolean,
    padding: androidx.compose.foundation.layout.PaddingValues,
    notice: (String) -> Unit,
    navController: androidx.navigation.NavHostController,
    conversationId: String? = null,
) {
    val model: CouncilViewModel = viewModel(factory = factory)
    LaunchedEffect(conversationId) { model.select(conversationId) }
    val conversations by model.conversations.collectAsState()
    val conversation = conversations.firstOrNull { it.id == conversationId }
    val messages by model.messages.collectAsState()
    val tasks by model.tasks.collectAsState()
    val stage by model.stage.collectAsState()
    val enabled by model.enabledParticipants.collectAsState()
    CouncilScreen(
        conversation = conversation,
        messages = messages,
        tasks = tasks,
        stage = stage,
        expanded = expanded,
        onNewChat = { mode -> model.create(mode) { id -> navController.navigate("council/$id") } },
        onModeChange = { mode -> conversationId?.let { model.setMode(it, mode) } },
        onAddMessage = { body -> conversationId?.let { model.addMessage(it, body) } },
        onStart = { question, selected ->
            val id = conversationId ?: return@CouncilScreen
            model.start(id, question, conversation?.researchMode ?: ResearchMode.STANDARD, selected, notice)
        },
        onAdvance = { conversationId?.let { model.advance(it, notice) } },
        onPrepare = { provider, question, mode, selected ->
            val id = conversationId ?: return@CouncilScreen
            model.prepareTask(id, provider, question, mode, selected) {
                notice("Prompt prepared. ${provider.name} is not researching inside this app. $it")
            }
        },
        onPrepareCritique = {
            val id = conversationId ?: return@CouncilScreen
            model.prepareCritique(id, notice)
        },
        onRecordObjection = { text -> conversationId?.let { model.recordObjection(it, text, notice) } },
        onRequestRebuttal = { conversationId?.let { model.requestRebuttal(it, notice) } },
        onRequestVerification = { conversationId?.let { model.requestVerification(it, notice) } },
        onPrepareSynthesis = { conversationId?.let { model.prepareSynthesis(it, notice) } },
        onPause = { conversationId?.let { model.pause(it, notice) } },
        onResume = { conversationId?.let { model.resume(it, notice) } },
        onStop = { conversationId?.let { model.stop(it, notice) } },
        onAddContext = { text, invalidate -> conversationId?.let { model.addContext(it, text, invalidate, notice) } },
        onFollowUp = { text -> conversationId?.let { model.requestFollowUp(it, text, notice) } },
        onAnotherReview = { conversationId?.let { model.requestAnotherReview(it, notice) } },
        onCopy = { text -> notice(ExternalLinks.copy(application, "AI Rachabanda prompt", text)) },
        onCopyAndOpen = { task ->
            val conversation = conversationId ?: return@CouncilScreen
            model.rememberAssisted(conversation, task.id, task.providerId)
            notice(ExternalLinks.copy(application, "AI Rachabanda prompt", task.prompt))
            val chat = com.airachabanda.app.provider.ProviderAccess.profile(task.providerId).chatUrl
            if (chat != null) notice(ExternalLinks.openHttps(application, chat))
        },
        onOpenUrl = { url -> notice(ExternalLinks.openHttps(application, url)) },
        onSkip = { taskId -> conversationId?.let { model.skip(it, taskId) } },
        onDefer = model::defer,
        onImport = { provider, taskId ->
            val task = taskId.orEmpty()
            val conversation = conversationId.orEmpty()
            navController.navigate("import?provider=${provider.name}&task=$task&conversation=$conversation")
        },
        enabledProviders = enabled,
        contentPadding = padding,
    )
}

@Composable
private fun ImportRoute(
    application: AIRachabandaApplication,
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    sharedText: String?,
    providerName: String?,
    taskId: String?,
    conversationId: String?,
    padding: androidx.compose.foundation.layout.PaddingValues,
    notice: (String) -> Unit,
) {
    val model: ImportViewModel = viewModel(factory = factory)
    val conversations by model.conversations.collectAsState()
    val ready by model.ready.collectAsState()
    val recovered by model.recovered.collectAsState()
    val provider = providerName?.let { runCatching { ProviderId.valueOf(it) }.getOrNull() } ?: ProviderId.CLAUDE
    if (conversationId.isNullOrBlank() && !ready) {
        EmptyPanel("Returning to the saved conversation.", Modifier.padding(padding))
        return
    }
    val saved = if (conversationId.isNullOrBlank()) recovered else null
    ImportScreen(
        conversations = conversations,
        initialBody = sharedText.orEmpty(),
        initialProvider = saved?.providerId ?: provider,
        initialConversationId = conversationId?.takeIf { it.isNotBlank() } ?: saved?.conversationId,
        taskId = taskId?.takeIf { it.isNotBlank() } ?: saved?.taskId?.takeIf { it.isNotBlank() },
        recovered = saved,
        onImport = { draft, callback ->
            model.import(draft) { ok, message ->
                notice(message)
                callback(ok, message)
            }
        },
        contentPadding = padding,
    )
}

private typealias ViewModelProviderFactory = androidx.lifecycle.ViewModelProvider.Factory
