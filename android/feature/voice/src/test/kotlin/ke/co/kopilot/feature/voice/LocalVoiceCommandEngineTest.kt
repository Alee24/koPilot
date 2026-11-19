package ke.co.kopilot.feature.voice

import android.content.Context
import io.mockk.mockk
import ke.co.kopilot.core.common.coroutines.DispatcherProvider
import ke.co.kopilot.core.security.SecureTokenStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVoiceCommandEngineTest {

    private class FakeToolExecutor : VoiceToolExecutor {
        val calledTools = mutableListOf<String>()

        override suspend fun executeTool(name: String, arguments: JsonObject): String {
            calledTools.add(name)
            return when (name) {
                "get_smartwatch_health" ->
                    """{"status":"ok","summary":"Your current heart rate is 74 beats per minute."}"""
                "get_sleep_summary" ->
                    """{"status":"ok","summary":"Your last recorded sleep was 6h 45m with 94 percent efficiency."}"""
                "get_fuel_efficiency_summary" ->
                    """{"status":"ok","summary":"Current fuel consumption is 6.8 litres per 100 kilometres."}"""
                "ask_vehicle_intelligence" ->
                    """{"status":"ok","answer":"Battery voltage is 14.1 volts, coolant temperature is 88 degrees Celsius."}"""
                "get_all_sensors_summary" ->
                    """{"status":"ok","summary":"Heading North at 4 degrees. Road surface score is 92 out of 100."}"""
                "get_recent_road_observations" ->
                    """{"status":"ok","last_sign":"Speed Limit 50","last_speed_limit_kmh":50,"traffic_light":"GREEN","last_plate":"KDM ***A"}"""
                else -> """{"status":"ok"}"""
            }
        }
    }

    private fun createEngine(executor: VoiceToolExecutor): LocalVoiceCommandEngine {
        val dispatchers = object : DispatcherProvider {
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
            override val io: CoroutineDispatcher = Dispatchers.Unconfined
            override val default: CoroutineDispatcher = Dispatchers.Unconfined
        }
        return LocalVoiceCommandEngine(
            context = mockk<Context>(relaxed = true),
            toolExecutor = executor,
            tokenStore = mockk<SecureTokenStore>(relaxed = true),
            okHttpClient = OkHttpClient(),
            dispatchers = dispatchers,
        )
    }

    @Test
    fun `routes heartbeat and smartwatch queries offline`() = runTest {
        val fake = FakeToolExecutor()
        val engine = createEngine(fake)

        val reply = engine.handleSpokenCommand("check my heartbeat", "en")
        assertEquals("get_smartwatch_health", fake.calledTools.last())
        assertTrue(reply.contains("74 beats per minute"))
    }

    @Test
    fun `routes sleep queries offline`() = runTest {
        val fake = FakeToolExecutor()
        val engine = createEngine(fake)

        val reply = engine.handleSpokenCommand("how did I sleep last night", "en")
        assertEquals("get_sleep_summary", fake.calledTools.last())
        assertTrue(reply.contains("6h 45m"))
    }

    @Test
    fun `routes fuel consumption to OBD fuel summary instead of nearby places`() = runTest {
        val fake = FakeToolExecutor()
        val engine = createEngine(fake)

        val reply = engine.handleSpokenCommand("check my fuel consumption", "en")
        assertEquals("get_fuel_efficiency_summary", fake.calledTools.last())
        assertTrue(reply.contains("6.8 litres"))
    }

    @Test
    fun `routes battery voltage and OBD queries offline`() = runTest {
        val fake = FakeToolExecutor()
        val engine = createEngine(fake)

        val reply = engine.handleSpokenCommand("what is my voltage", "en")
        assertEquals("ask_vehicle_intelligence", fake.calledTools.last())
        assertTrue(reply.contains("14.1 volts"))
    }

    @Test
    fun `routes all sensors and compass queries offline`() = runTest {
        val fake = FakeToolExecutor()
        val engine = createEngine(fake)

        val reply = engine.handleSpokenCommand("read all sensors", "en")
        assertEquals("get_all_sensors_summary", fake.calledTools.last())
        assertTrue(reply.contains("Road surface score is 92"))
    }
}
