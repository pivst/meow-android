package io.github.madeye.meow.ui.screens.proxies

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.analytics.Analytics
import io.github.madeye.meow.api.GroupDelayTester
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.repo.ProfileRepository
import io.github.madeye.meow.ui.screens.home.RouteChanges
import io.github.madeye.meow.vpn.VpnStateRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

@Immutable
data class ProxyNodeUi(
    val name: String,
    val type: String,
    val delay: NodeDelay,
    val selected: Boolean,
)

@Immutable
data class ProxyGroupUi(
    val name: String,
    val type: String,
    val now: String,
    val nodes: List<ProxyNodeUi>,
    /** The group's own health-check URL, which its latency test probes with. */
    val testUrl: String? = null,
    /** Share of members tested while a latency test runs; `null` otherwise. */
    val testProgress: Float? = null,
)

@Immutable
data class ProxyGroupsUiState(
    val state: BaseService.State = BaseService.State.Idle,
    val hasProfile: Boolean = false,
    val groups: List<ProxyGroupUi> = emptyList(),
    val expandedGroup: String? = null,
    val waitingForProfile: Boolean = false,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val selectionFailed: Boolean = false,
    val pendingGroups: Set<String> = emptySet(),
)

/**
 * The Proxy Groups tab: the running engine's groups, node picks and latency
 * tests. Groups only exist while the engine is up, so everything here is read
 * from `/proxies` rather than from the profile.
 */
class ProxyGroupsViewModel internal constructor(
    private val vpnState: StateFlow<BaseService.State>,
    private val selectedProfile: Flow<Long?>,
    private val runningProfile: StateFlow<Long?>,
    private val refreshVpn: () -> Unit,
    private val saveSelection: suspend (Long, String) -> Unit,
    private val selectionConfirmed: (String) -> Unit,
    private val api: MeowApi,
) : ViewModel() {
    constructor(vpn: VpnStateRepository, profiles: ProfileRepository, api: MeowApi,
                analytics: Analytics, routeChanges: RouteChanges) : this(
        vpn.state, profiles.observeSelected().map { it?.id }.distinctUntilChanged(), vpn.runningProfileId, vpn::refresh,
        { id, node -> profiles.saveSelectedProxy(id, node) },
        { node -> routeChanges.notifyChanged(); analytics.proxyNodeSelect(node) }, api,
    )

    private data class Feedback(val waitingForProfile: Boolean = false, val loading: Boolean = false, val loadFailed: Boolean = false,
                                val selectionFailed: Boolean = false, val pending: Set<String> = emptySet())
    private val feedback = MutableStateFlow(Feedback())
    private var generation = 0L
    private var profileId: Long? = null
    private var loadJob: Job? = null
    private val selectionJobs = HashMap<String, Job>()
    private val groups = MutableStateFlow<List<ProxyGroupUi>>(emptyList())
    private val expanded = MutableStateFlow<String?>(null)

    /** Running latency tests by group, laid over [groups] until each ends. */
    private val groupTests = MutableStateFlow<Map<String, GroupTestProgress>>(emptyMap())

    /** One job per group under test. Only touched on the main thread. */
    private val testJobs = HashMap<String, Job>()
    private val delayTester = GroupDelayTester(api)

    val uiState: StateFlow<ProxyGroupsUiState> = combine(
        vpnState,
        selectedProfile.map { it != null },
        combine(groups, groupTests) { list, tests -> list.withTests(tests) },
        combine(expanded, feedback) { group, status -> group to status },
    ) { state, hasProfile, groupList, display ->
        val (expandedGroup, status) = display
        ProxyGroupsUiState(
            state = state,
            hasProfile = hasProfile,
            groups = groupList,
            expandedGroup = expandedGroup,
            waitingForProfile = status.waitingForProfile,
            loading = status.loading, loadFailed = status.loadFailed,
            selectionFailed = status.selectionFailed, pendingGroups = status.pending,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProxyGroupsUiState())

    init {
        viewModelScope.launch {
            combine(vpnState, selectedProfile, runningProfile) { state, id, owner -> Triple(state, id, owner) }
                .distinctUntilChanged().collect { (state, id, owner) ->
                    generation++
                    profileId = id
                    loadJob?.cancel()
                    selectionJobs.values.toList().forEach { it.cancel() }
                    selectionJobs.clear()
                    testJobs.values.toList().forEach { it.cancel() }
                    testJobs.clear()
                    groupTests.value = emptyMap()
                    groups.value = emptyList()
                    val waiting = state == BaseService.State.Connected && id != owner
                    feedback.value = Feedback(waitingForProfile = waiting)
                    if (state == BaseService.State.Connected && id != null && !waiting) loadGroups()
                }
        }
    }

    /**
     * Every time the tab shows, not only on app resume: a route-mode switch on
     * Home decides whether GLOBAL is listed, and the `:vpn` process may have
     * been killed while backgrounded.
     */
    fun onResume() {
        refreshVpn()
        if (vpnState.value == BaseService.State.Connected && !feedback.value.waitingForProfile) loadGroups()
    }

    fun onToggleExpanded(group: String) {
        expanded.value = if (expanded.value == group) null else group
    }

    fun onSelectNode(group: String, node: String) {
        val owner = profileId ?: return
        if (vpnState.value != BaseService.State.Connected || feedback.value.waitingForProfile || group in feedback.value.pending) return
        val epoch = generation
        // Do not queue another write: cancelling HTTP cannot undo an accepted engine write.
        // Only this group is disabled until its write and authoritative readback finish.
        loadJob?.cancel()
        feedback.update { it.copy(loading = false, selectionFailed = false, pending = it.pending + group) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                api.selectProxy(group, node)
                if (epoch != generation) return@launch
                saveSelection(owner, node)
                if (epoch != generation) return@launch
                selectionConfirmed(node)
                loadGroups().join()
            } catch (e: IOException) {
                if (epoch == generation) {
                    feedback.update { it.copy(selectionFailed = true) }
                    loadGroups().join()
                }
            } finally {
                if (epoch == generation) {
                    selectionJobs.remove(group)
                    feedback.update { it.copy(pending = it.pending - group) }
                }
            }
        }
        selectionJobs[group] = job
        job.start()
    }

    /**
     * Latency-tests every member of [group], filling each row in as its probe
     * lands (see [GroupDelayTester]). A test already running for the group is
     * restarted rather than joined; other groups' tests carry on.
     */
    fun onTestGroup(group: String) {
        val target = groups.value.firstOrNull { it.name == group } ?: return
        testJobs.remove(group)?.cancel()
        val members = target.nodes.map { it.name }
        groupTests.update { it + (group to GroupTestProgress.start(members)) }
        // Lazy so the job is registered before its body can reach `finally`.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                delayTester.test(group, target.type, members, target.testUrl).collect { result ->
                    groupTests.update { tests ->
                        val progress = tests[group] ?: return@update tests
                        tests + (group to progress.withResult(result))
                    }
                }
                // The engine kept every result as history (and an url-test
                // group its new pick); reload before dropping the overlay so
                // rows don't flash their pre-test values in between.
                loadGroups().join()
            } finally {
                // A superseded run must leave its successor's state alone.
                if (testJobs[group] === self) {
                    testJobs.remove(group)
                    groupTests.update { it - group }
                }
            }
        }
        testJobs[group] = job
        job.start()
    }

    private fun loadGroups(): Job {
        loadJob?.cancel()
        val epoch = generation
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            feedback.update { it.copy(loading = true) }
            // Read first: the mode decides whether GLOBAL is listed.
            try {
                val mode = api.configs().routeMode
                val result = api.proxies()
                val loaded = result.visibleGroups(mode).map { group ->
                    ProxyGroupUi(
                        name = group.name,
                        type = group.type,
                        now = group.now,
                        testUrl = group.testUrl,
                        nodes = group.all.map { nodeName ->
                            val node = result.proxies[nodeName]
                            // Members can be groups themselves (every one of
                            // GLOBAL's is); those live in the other map.
                            val member = result.groups[nodeName]
                            ProxyNodeUi(
                                name = nodeName,
                                type = node?.type ?: member?.type.orEmpty(),
                                delay = NodeDelay.fromHistory(node?.history ?: member?.history.orEmpty()),
                                selected = nodeName == group.now,
                            )
                        },
                    )
                }
                if (epoch == generation) {
                    groups.value = loaded
                    feedback.update { it.copy(loading = false, loadFailed = false) }
                }
            } catch (e: IOException) {
                // No raw exception/URL reaches the UI or logs; the last good list stays visible.
                if (epoch == generation) feedback.update { it.copy(loading = false, loadFailed = true) }
            }
        }
        loadJob = job
        job.start()
        return job
    }

}
