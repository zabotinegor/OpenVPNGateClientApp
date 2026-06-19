# How-To Runbook

This runbook collects non-obvious techniques and patterns discovered during development. Add an
entry when a technique is reusable and not obvious from the library documentation alone.

---

## Use MockWebServer in pure-JVM unit tests (no Robolectric)

**When to use**

When you need to test an API client (e.g., a Retrofit + OkHttp wrapper) at the HTTP level
without standing up a real server. `MockWebServer` runs entirely in-process on a random port,
so these tests are fast pure-JVM tests that run with `testDebugUnitTestApp` — no emulator or
Robolectric required.

**Step 1 — Register the artifact in the version catalog**

In `src/gradle/libs.versions.toml`, add a `[libraries]` entry that reuses the existing
`square-okhttp` version reference:

```toml
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "square-okhttp" }
```

The `square-okhttp` version ref is already present (used by the runtime `okhttp` library). Using
the same ref keeps `MockWebServer` and the runtime client at identical versions, which prevents
subtle protocol-mismatch bugs.

**Step 2 — Declare the dependency as test-only**

In the module's `build.gradle.kts` (e.g., `src/core/build.gradle.kts`):

```kotlin
testImplementation(libs.okhttp.mockwebserver)
```

Do not add it to `implementation` or `androidTestImplementation` unless specifically needed for
instrumented tests.

**Step 3 — Write the test**

```kotlin
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class MyApiClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `returns expected result on HTTP 202`() = runTest {
        server.enqueue(MockResponse().setResponseCode(202))

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val api = retrofit.create(MyApi::class.java)
        val client = MyApiClient(api)

        val result = client.doRequest(serverId = 1)

        assertEquals(MyResult.Queued, result)
    }
}
```

Key points:
- Call `server.start()` in `@Before` and `server.shutdown()` in `@After`.
- Use `server.url("/")` as the Retrofit base URL so requests go to the in-process server.
- `server.enqueue()` queues responses in FIFO order; each response is consumed by exactly one
  request.
- `server.takeRequest()` lets you assert on the outgoing request (method, path, headers, body).

**First demonstrated**

SUB-03 (`HardProbeApiClientTest`).

**References**

- `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/servers/probe/HardProbeApiClientTest.kt`
- `src/gradle/libs.versions.toml` (`okhttp-mockwebserver` entry)
- [OkHttp MockWebServer README](https://github.com/square/okhttp/tree/master/mockwebserver)
