/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.downloads

import android.app.Service
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import mozilla.components.concept.fetch.Response
import mozilla.components.feature.downloads.AbstractFetchDownloadService.Companion.ACTION_CANCEL
import mozilla.components.feature.downloads.AbstractFetchDownloadService.Companion.ACTION_PAUSE
import mozilla.components.feature.downloads.AbstractFetchDownloadService.Companion.ACTION_RESUME
import mozilla.components.feature.downloads.AbstractFetchDownloadService.Companion.ACTION_TRY_AGAIN
import mozilla.components.feature.downloads.AbstractFetchDownloadService.DownloadJobStatus.ACTIVE
import mozilla.components.feature.downloads.AbstractFetchDownloadService.DownloadJobStatus.CANCELLED
import mozilla.components.feature.downloads.AbstractFetchDownloadService.DownloadJobStatus.COMPLETED
import mozilla.components.feature.downloads.AbstractFetchDownloadService.DownloadJobStatus.FAILED
import mozilla.components.feature.downloads.AbstractFetchDownloadService.DownloadJobStatus.PAUSED
import mozilla.components.feature.downloads.ext.putDownloadExtra
import mozilla.components.feature.downloads.facts.DownloadsFacts.Items.NOTIFICATION
import mozilla.components.support.base.facts.Action
import mozilla.components.support.base.facts.processor.CollectionProcessor
import mozilla.components.support.test.any
import mozilla.components.support.test.argumentCaptor
import mozilla.components.support.test.mock
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations.initMocks

@RunWith(AndroidJUnit4::class)
class AbstractFetchDownloadServiceTest {

    @Rule @JvmField
    val folder = TemporaryFolder()

    @Mock private lateinit var client: Client
    @Mock private lateinit var broadcastManager: LocalBroadcastManager
    private lateinit var service: AbstractFetchDownloadService

    @Before
    fun setup() {
        initMocks(this)
        service = spy(object : AbstractFetchDownloadService() {
            override val httpClient = client
        })

        doReturn(broadcastManager).`when`(service).broadcastManager
        doReturn(testContext).`when`(service).context
        doNothing().`when`(service).useFileStream(any(), anyBoolean(), any())
    }

    @Test
    fun `begins download when started`() = runBlocking {
        val download = DownloadState("https://example.com/file.txt", "file.txt")
        val response = Response(
            "https://example.com/file.txt",
            200,
            MutableHeaders(),
            Response.Body(mock())
        )
        doReturn(response).`when`(client).fetch(Request("https://example.com/file.txt"))

        val downloadIntent = Intent("ACTION_DOWNLOAD").apply {
            putDownloadExtra(download)
        }

        service.onStartCommand(downloadIntent, 0, 0)
        service.downloadJobs.values.forEach { it.job?.join() }

        val providedDownload = argumentCaptor<DownloadState>()
        verify(service).performDownload(providedDownload.capture())

        assertEquals(download.url, providedDownload.value.url)
        assertEquals(download.fileName, providedDownload.value.fileName)

        // Ensure the job is properly added to the map
        assertEquals(1, service.downloadJobs.count())
        assertNotNull(service.downloadJobs[providedDownload.value.id])
    }

    @Test
    fun `service redelivers if no download extra is passed `() = runBlocking {
        val downloadIntent = Intent("ACTION_DOWNLOAD")

        val intentCode = service.onStartCommand(downloadIntent, 0, 0)

        assertEquals(Service.START_REDELIVER_INTENT, intentCode)
    }

    @Test
    fun `verifyDownload sets the download to failed if it is not complete`() = runBlocking {
        val downloadState = DownloadState(
            url = "mozilla.org/mozilla.txt",
            contentLength = 50L
        )

        val downloadJobState = AbstractFetchDownloadService.DownloadJobState(
            job = null,
            state = downloadState,
            currentBytesCopied = 5,
            status = ACTIVE,
            foregroundServiceId = 1,
            downloadDeleted = false
        )

        service.verifyDownload(downloadJobState)

        assertEquals(FAILED, service.getDownloadJobStatus(downloadJobState))
    }

    @Test
    fun `verifyDownload does NOT set the download to failed if it is paused`() = runBlocking {
        val downloadState = DownloadState(
            url = "mozilla.org/mozilla.txt",
            contentLength = 50L
        )

        val downloadJobState = AbstractFetchDownloadService.DownloadJobState(
            job = null,
            state = downloadState,
            currentBytesCopied = 5,
            status = PAUSED,
            foregroundServiceId = 1,
            downloadDeleted = false
        )

        service.verifyDownload(downloadJobState)

        assertEquals(PAUSED, service.getDownloadJobStatus(downloadJobState))
    }

    @Test
    fun `verifyDownload does NOT set the download to failed if it is complete`() = runBlocking {
        val downloadState = DownloadState(
            url = "mozilla.org/mozilla.txt",
            contentLength = 50L
        )

        val downloadJobState = AbstractFetchDownloadService.DownloadJobState(
            job = null,
            state = downloadState,
            currentBytesCopied = 50,
            status = ACTIVE,
            foregroundServiceId = 1,
            downloadDeleted = false
        )

        service.verifyDownload(downloadJobState)

        assertNotEquals(FAILED, service.getDownloadJobStatus(downloadJobState))
    }

    @Test
    fun `verifyDownload does NOT set the download to failed if it is cancelled`() = runBlocking {
        val downloadState = DownloadState(
            url = "mozilla.org/mozilla.txt",
            contentLength = 50L
        )

        val downloadJobState = AbstractFetchDownloadService.DownloadJobState(
            job = null,
            state = downloadState,
            currentBytesCopied = 50,
            status = CANCELLED,
            foregroundServiceId = 1,
            downloadDeleted = false
        )

        service.verifyDownload(downloadJobState)

        assertNotEquals(FAILED, service.getDownloadJobStatus(downloadJobState))
    }

    @Test
    fun `verifyDownload does NOT set the download to failed if it is status COMPLETED`() = runBlocking {
        val downloadState = DownloadState(
            url = "mozilla.org/mozilla.txt",
            contentLength = 50L
        )

        val downloadJobState = AbstractFetchDownloadService.DownloadJobState(
            job = null,
            state = downloadState,
            currentBytesCopied = 50,
            status = COMPLETED,
            foregroundServiceId = 1,
            downloadDeleted = false
        )

        service.verifyDownload(downloadJobState)

        assertNotEquals(FAILED, service.getDownloadJobStatus(downloadJobState))
    }

    @Test
    fun `broadcastReceiver handles ACTION_PAUSE`() = runBlocking {
        val download = DownloadState("https://example.com/file.txt", "file.txt")
        val response = Response(
            "https://example.com/file.txt",
            200,
            MutableHeaders(),
            Response.Body(mock())
        )
        doReturn(response).`when`(client).fetch(Request("https://example.com/file.txt"))

        val downloadIntent = Intent("ACTION_DOWNLOAD").apply {
            putDownloadExtra(download)
        }

        service.onStartCommand(downloadIntent, 0, 0)
        service.downloadJobs.values.forEach { it.job?.join() }

        val providedDownload = argumentCaptor<DownloadState>()
        verify(service).performDownload(providedDownload.capture())

        val pauseIntent = Intent(ACTION_PAUSE).apply {
            setPackage(testContext.applicationContext.packageName)
            putExtra(DownloadNotification.EXTRA_DOWNLOAD_ID, providedDownload.value.id)
        }

        CollectionProcessor.withFactCollection { facts ->
            service.broadcastReceiver.onReceive(testContext, pauseIntent)

            val pauseFact = facts[0]
            assertEquals(Action.PAUSE, pauseFact.action)
            assertEquals(NOTIFICATION, pauseFact.item)
        }

        service.downloadJobs[providedDownload.value.id]?.job?.join()
        val downloadJobState = service.downloadJobs[providedDownload.value.id]!!
        assertEquals(PAUSED, service.getDownloadJobStatus(downloadJobState))
    }

    @Test
    fun `broadcastReceiver handles ACTION_CANCEL`() = runBlocking {
        val download = DownloadState("https://example.com/file.txt", "file.txt")
        val response = Response(
            "https://example.com/file.txt",
            200,
            MutableHeaders(),
            Response.Body(mock())
        )
        doReturn(response).`when`(client).fetch(Request("https://example.com/file.txt"))

        val downloadIntent = Intent("ACTION_DOWNLOAD").apply {
            putDownloadExtra(download)
        }

        service.onStartCommand(downloadIntent, 0, 0)
        service.downloadJobs.values.forEach { it.job?.join() }

        val providedDownload = argumentCaptor<DownloadState>()
        verify(service).performDownload(providedDownload.capture())

        val cancelIntent = Intent(ACTION_CANCEL).apply {
            setPackage(testContext.applicationContext.packageName)
            putExtra(DownloadNotification.EXTRA_DOWNLOAD_ID, providedDownload.value.id)
        }

        assertFalse(service.downloadJobs[providedDownload.value.id]!!.downloadDeleted)

        CollectionProcessor.withFactCollection { facts ->
            service.broadcastReceiver.onReceive(testContext, cancelIntent)

            val cancelFact = facts[0]
            assertEquals(Action.CANCEL, cancelFact.action)
            assertEquals(NOTIFICATION, cancelFact.item)
        }

        service.downloadJobs[providedDownload.value.id]?.job?.join()

        val downloadJobState = service.downloadJobs[providedDownload.value.id]!!
        assertEquals(CANCELLED, service.getDownloadJobStatus(downloadJobState))
        assertTrue(downloadJobState.downloadDeleted)
    }

    @Test
    fun `broadcastReceiver handles ACTION_RESUME`() = runBlocking {
        val download = DownloadState("https://example.com/file.txt", "file.txt")

        val downloadResponse = Response(
            "https://example.com/file.txt",
            200,
            MutableHeaders(),
            Response.Body(mock())
        )
        val resumeResponse = Response(
            "https://example.com/file.txt",
            206,
            MutableHeaders("Content-Range" to "1-67589/67589"),
            Response.Body(mock())
        )
        doReturn(downloadResponse).`when`(client)
            .fetch(Request("https://example.com/file.txt"))
        doReturn(resumeResponse).`when`(client)
            .fetch(Request("https://example.com/file.txt", headers = MutableHeaders("Range" to "bytes=1-")))

        val downloadIntent = Intent("ACTION_DOWNLOAD").apply {
            putDownloadExtra(download)
        }

        service.onStartCommand(downloadIntent, 0, 0)
        service.downloadJobs.values.forEach { it.job?.join() }

        val providedDownload = argumentCaptor<DownloadState>()
        verify(service).performDownload(providedDownload.capture())

        // Simulate a pause
        var downloadJobState = service.downloadJobs[providedDownload.value.id]!!
        downloadJobState.currentBytesCopied = 1
        service.setDownloadJobStatus(downloadJobState, PAUSED)
        service.downloadJobs[providedDownload.value.id]?.job?.cancel()

        val resumeIntent = Intent(ACTION_RESUME).apply {
            setPackage(testContext.applicationContext.packageName)
            putExtra(DownloadNotification.EXTRA_DOWNLOAD_ID, providedDownload.value.id)
        }

        CollectionProcessor.withFactCollection { facts ->
            service.broadcastReceiver.onReceive(testContext, resumeIntent)

            val resumeFact = facts[0]
            assertEquals(Action.RESUME, resumeFact.action)
            assertEquals(NOTIFICATION, resumeFact.item)
        }

        downloadJobState = service.downloadJobs[providedDownload.value.id]!!
        assertEquals(ACTIVE, service.getDownloadJobStatus(downloadJobState))

        // Make sure the download job is completed (break out of copyInChunks)
        service.setDownloadJobStatus(downloadJobState, PAUSED)

        service.downloadJobs[providedDownload.value.id]?.job?.join()

        verify(service).startDownloadJob(providedDownload.value.id)
    }

    @Test
    fun `broadcastReceiver handles ACTION_TRY_AGAIN`() = runBlocking {
        val download = DownloadState("https://example.com/file.txt", "file.txt")
        val response = Response(
            "https://example.com/file.txt",
            200,
            MutableHeaders(),
            Response.Body(mock())
        )
        doReturn(response).`when`(client).fetch(Request("https://example.com/file.txt"))

        val downloadIntent = Intent("ACTION_DOWNLOAD").apply {
            putDownloadExtra(download)
        }

        service.onStartCommand(downloadIntent, 0, 0)
        service.downloadJobs.values.forEach { it.job?.join() }

        val providedDownload = argumentCaptor<DownloadState>()
        verify(service).performDownload(providedDownload.capture())
        service.downloadJobs[providedDownload.value.id]?.job?.join()

        // Simulate a failure
        var downloadJobState = service.downloadJobs[providedDownload.value.id]!!
        service.setDownloadJobStatus(downloadJobState, FAILED)
        service.downloadJobs[providedDownload.value.id]?.job?.cancel()

        val tryAgainIntent = Intent(ACTION_TRY_AGAIN).apply {
            setPackage(testContext.applicationContext.packageName)
            putExtra(DownloadNotification.EXTRA_DOWNLOAD_ID, providedDownload.value.id)
        }

        CollectionProcessor.withFactCollection { facts ->
            service.broadcastReceiver.onReceive(testContext, tryAgainIntent)

            val tryAgainFact = facts[0]
            assertEquals(Action.TRY_AGAIN, tryAgainFact.action)
            assertEquals(NOTIFICATION, tryAgainFact.item)
        }

        downloadJobState = service.downloadJobs[providedDownload.value.id]!!
        assertEquals(ACTIVE, service.getDownloadJobStatus(downloadJobState))

        // Make sure the download job is completed (break out of copyInChunks)
        service.setDownloadJobStatus(downloadJobState, PAUSED)

        service.downloadJobs[providedDownload.value.id]?.job?.join()

        verify(service).startDownloadJob(providedDownload.value.id)
    }

    @Test
    fun `download fails on a bad network response`() = runBlocking {
        val download = DownloadState("https://example.com/file.txt", "file.txt")
        val response = Response(
            "https://example.com/file.txt",
            400,
            MutableHeaders(),
            Response.Body(mock())
        )
        doReturn(response).`when`(client).fetch(Request("https://example.com/file.txt"))

        val downloadIntent = Intent("ACTION_DOWNLOAD").apply {
            putDownloadExtra(download)
        }

        service.onStartCommand(downloadIntent, 0, 0)
        service.downloadJobs.values.forEach { it.job?.join() }

        val providedDownload = argumentCaptor<DownloadState>()
        verify(service).performDownload(providedDownload.capture())

        service.downloadJobs[providedDownload.value.id]?.job?.join()
        val downloadJobState = service.downloadJobs[providedDownload.value.id]!!
        assertEquals(FAILED, service.getDownloadJobStatus(downloadJobState))
    }

    @Test
    fun `makeUniqueFileNameIfNecessary transforms fileName when appending FALSE`() {
        folder.newFile("example.apk")

        val download = DownloadState(
            url = "mozilla.org",
            fileName = "example.apk",
            destinationDirectory = folder.root.path
        )
        val transformedDownload = service.makeUniqueFileNameIfNecessary(download, false)

        assertNotEquals(download, transformedDownload)
    }

    @Test
    fun `makeUniqueFileNameIfNecessary does NOT transform fileName when appending TRUE`() {
        folder.newFile("example.apk")

        val download = DownloadState(
            url = "mozilla.org",
            fileName = "example.apk",
            destinationDirectory = folder.root.path
        )
        val transformedDownload = service.makeUniqueFileNameIfNecessary(download, true)

        assertEquals(download, transformedDownload)
    }
}
