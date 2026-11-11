package io.github.madeye.meow.ui.screens.proxies

import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.bg.BaseService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyGroupsViewModelTest {
    private val server = MockWebServer()
    private val vpn = MutableStateFlow(BaseService.State.Connected)
    private val profile = MutableStateFlow<Long?>(1L)
    private val runningProfile = MutableStateFlow<Long?>(1L)
    private val writes = CopyOnWriteArrayList<String>()
    private val saves = CopyOnWriteArrayList<Pair<Long, String>>()
    @Volatile private var selected = "A"
    @Volatile private var failed = false
    @Volatile private var holdWrite: CountDownLatch? = null
    @Volatile private var writeArrived = CountDownLatch(1)
    @Volatile private var holdRead: CountDownLatch? = null
    @Volatile private var readArrived = CountDownLatch(1)
    private lateinit var vm: ProxyGroupsViewModel
    private lateinit var collector: Job
    private val mainDispatcher = newSingleThreadContext("groups-main")

    @Before fun setup() {
        Dispatchers.setMain(mainDispatcher)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (request.method == "PUT") {
                    val node = if (request.body.readUtf8().contains("B")) "B" else "A"
                    writes += "$path:$node"
                    writeArrived.countDown()
                    holdWrite?.await(4, TimeUnit.SECONDS)
                    selected = node
                    return MockResponse().setResponseCode(204)
                }
                return when (path) {
                    "/configs" -> MockResponse().setBody("""{"mode":"rule"}""")
                    "/api/proxy-groups" -> MockResponse().setBody("[]")
                    "/proxies" -> {
                        val previous = selected
                        readArrived.countDown()
                        holdRead?.await(4, TimeUnit.SECONDS)
                        if (failed) MockResponse().setResponseCode(503) else MockResponse().setBody(
                            """{"proxies":{"G":{"type":"Selector","now":"$previous","all":["A","B"]},"H":{"type":"Selector","now":"$previous","all":["A","B"]},"A":{"type":"Direct"},"B":{"type":"Direct"}}}"""
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        vm = ProxyGroupsViewModel(vpn, profile, runningProfile, {}, { id, node -> saves += id to node }, {}, MeowApi(server.url("/")))
        collector = CoroutineScope(Dispatchers.Main).launch { vm.uiState.collect() }
    }

    @After fun teardown() = runBlocking {
        holdWrite?.countDown(); holdRead?.countDown()
        collector.cancelAndJoin()
        vm.viewModelScope.coroutineContext.job.cancelAndJoin()
        Dispatchers.resetMain()
        mainDispatcher.close()
        server.shutdown()
    }

    private suspend fun state(predicate: (ProxyGroupsUiState) -> Boolean) =
        withTimeout(5000) { vm.uiState.first(predicate) }
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun loaded() = state { it.groups.size == 2 && !it.loading }

    @Test fun `failed refresh retains data marks stale and retry clears error`() = runBlocking {
        loaded(); failed = true; main { vm.onResume() }
        val stale = state { it.loadFailed }
        assertEquals(2, stale.groups.size)
        failed = false; main { vm.onResume() }
        assertFalse(state { !it.loadFailed && !it.loading && it.groups.size == 2 }.loadFailed)
    }

    @Test fun `initial failure is distinct from actual empty groups`() = runBlocking {
        loaded(); main { vpn.value = BaseService.State.Stopped }; state { it.state == BaseService.State.Stopped }
        failed = true; main { vpn.value = BaseService.State.Connected }
        val error = state { it.loadFailed }
        assertTrue(error.groups.isEmpty())
    }

    @Test fun `same group cannot issue reversed writes while another group remains usable`() = runBlocking {
        loaded(); holdWrite = CountDownLatch(1)
        main { vm.onSelectNode("G", "B") }
        assertTrue(writeArrived.await(2, TimeUnit.SECONDS))
        main { vm.onSelectNode("G", "A"); vm.onSelectNode("H", "B") }
        delay(100)
        assertEquals(1, writes.count { it.startsWith("/proxies/G:") })
        assertTrue(writes.any { it.startsWith("/proxies/H:") })
        assertEquals("A", vm.uiState.value.groups.first().now)
        holdWrite!!.countDown()
        state { it.pendingGroups.isEmpty() && it.groups.all { g -> g.now == "B" } }
        assertEquals("B", selected)
    }

    @Test fun `stopped session late write cannot save into a different profile`() = runBlocking {
        loaded(); holdWrite = CountDownLatch(1)
        main { vm.onSelectNode("G", "B") }
        assertTrue(writeArrived.await(2, TimeUnit.SECONDS))
        main { vpn.value = BaseService.State.Stopped; profile.value = 2L }
        state { it.state == BaseService.State.Stopped && it.groups.isEmpty() }
        holdWrite!!.countDown(); delay(150)
        assertTrue(saves.isEmpty())
        assertTrue(vm.uiState.value.pendingGroups.isEmpty())
    }

    @Test fun `profile change cannot write to old engine during reload settling`() = runBlocking {
        loaded()
        main { profile.value = 2L }
        state { it.waitingForProfile }
        main { vm.onSelectNode("G", "B"); vm.onResume() }
        delay(100)
        assertTrue(writes.isEmpty())
        assertTrue(vm.uiState.value.groups.isEmpty())
        main { vpn.value = BaseService.State.Stopped }
        state { it.state == BaseService.State.Stopped }
        main { runningProfile.value = 2L; vpn.value = BaseService.State.Connected }
        loaded()
        main { vm.onSelectNode("G", "B") }
        state { it.pendingGroups.isEmpty() && it.groups.all { group -> group.now == "B" } }
        assertEquals(listOf(2L to "B"), saves.toList())
    }

    @Test fun `new viewmodel during profile switch cannot write to still running old engine`() = runBlocking {
        loaded()
        main { profile.value = 2L }
        state { it.waitingForProfile }
        collector.cancelAndJoin(); vm.viewModelScope.coroutineContext.job.cancelAndJoin()
        main {
            vm = ProxyGroupsViewModel(vpn, profile, runningProfile, {}, { id, node -> saves += id to node }, {}, MeowApi(server.url("/")))
            collector = CoroutineScope(Dispatchers.Main).launch { vm.uiState.collect() }
        }
        delay(150)
        main { vm.onSelectNode("G", "B") }
        delay(150)
        assertTrue("old engine must receive no write attributed to new selected profile", writes.isEmpty())
        assertTrue(vm.uiState.value.waitingForProfile)
    }

    @Test fun `late old profile read cannot repopulate stopped screen`() = runBlocking {
        loaded(); readArrived = CountDownLatch(1); holdRead = CountDownLatch(1)
        main { vm.onResume() }
        assertTrue(readArrived.await(2, TimeUnit.SECONDS))
        main { vpn.value = BaseService.State.Stopped; profile.value = 2L }
        state { it.state == BaseService.State.Stopped && it.groups.isEmpty() }
        holdRead!!.countDown(); delay(150)
        assertTrue(vm.uiState.value.groups.isEmpty())
    }
}
