#!/usr/bin/env kscript

@file:DependsOn("org.http4k:http4k-core:5.20.0.0")
@file:DependsOn("org.http4k:http4k-client-apache:5.20.0.0")
@file:DependsOn("org.http4k:http4k-format-jackson:5.20.0.0")
@file:DependsOn("com.offbytwo:docopt:0.6.0.20150202", "log4j:log4j:1.2.17")
@file:DependsOn("ch.qos.logback:logback-classic:1.2.9")

// The rest is "nailed in" by `./gradlew dependencies`.

@file:DependsOn("org.apache.httpcomponents.client5:httpclient5:5.3.1")
@file:DependsOn("org.apache.httpcomponents.core5:httpcore5:5.2.4")
@file:DependsOn("org.apache.httpcomponents.core5:httpcore5-h2:5.2.4")
@file:DependsOn("org.slf4j:slf4j-api:1.7.36")
@file:DependsOn("org.http4k:http4k-realtime-core:5.20.0.0")
@file:DependsOn("com.fasterxml.jackson.core:jackson-databind:2.17.1")
@file:DependsOn("com.fasterxml.jackson.core:jackson-annotations:2.17.1")
@file:DependsOn("com.fasterxml.jackson.core:jackson-core:2.17.1")
@file:DependsOn("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.fasterxml.jackson.annotation.JsonProperty
import org.docopt.Docopt
import org.http4k.client.ApacheClient
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.format.Jackson.auto
import org.http4k.lens.LensFailure
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.io.path.Path


data class SmartMonData(
    val device: Device,
    @JsonProperty("model_name")
    val modelName: String,
    val temperature: SmartTemperature,
)

data class Device(
    val name: String,
)

data class SmartTemperature(
    val current: Double,
)

val usage = """
Store SMART information of drives for Fan Control
Usage: SmartmonServer.kts [options] <work_directory> <url>

Options:
  -l --log-level=<level>           Log level [default: INFO]
  -p --polling-interval=<seconds>  Polling interval in seconds [default: 20]
"""

val doArgs: MutableMap<String, Any> = Docopt(usage).parse(args.toList())
val workDir = doArgs.getOrDefault("<work_directory>", ".").toString()
val url = doArgs.getOrDefault("<url>", "http://localhost:9000").toString()
val logLevel: Level = Level.toLevel(doArgs.getOrDefault("--log-level", "INFO").toString())
val pollingIntervalMs = doArgs.getOrDefault("--polling-interval", "20").toString().toLong() * 1000

val rootLogger = org.slf4j.LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
rootLogger.level = logLevel
val logger = org.slf4j.LoggerFactory.getLogger("SmartMonClient") as Logger
logger.level = logLevel

val client = ApacheClient()
val listSmartMonDataLens = Body.auto<List<SmartMonData>>().toLens()

fun fetchSmartMonData(): List<Pair<String, Double>> {
    val response = client(Request(Method.GET, url))
    if (response.status.code != 200) {
        logger.error("Failed to fetch data from $url, status code: ${response.status.code}")
        return emptyList()
    } else {
        return try {
            listSmartMonDataLens
                .extract(response)
                .map { it.modelName.replace("\\s+".toRegex(), "") to it.temperature.current }
        } catch (e: LensFailure) {
            logger.error("Failed to parse response: {}. Raw: {}", e, response.bodyString())
            emptyList()
        }
    }
}

logger.info("Starting SmartMonClient: workdir=$workDir, url=$url, pollingInterval=${pollingIntervalMs / 1000} s.")

val thread =
    thread(start = false, name = "PollingThread") {
        try {
            var firstTime = true
            while (!Thread.currentThread().isInterrupted) {
                fetchSmartMonData()
                    .also {
                        if (firstTime) {
                            logger.info("Got following drives: ${it.joinToString(", ") { (key, _) -> key }}")
                            if (it.isNotEmpty()) firstTime = false
                        }
                    }
                    .forEach { (key, value) ->
                        val path = Path(workDir).resolve(Path("$key.sensor"))
                        logger.debug("Writing {} to {}", value, path)
                        try {
                            File(path.toUri()).writeText(value.toString())
                        } catch (e: IOException) {
                            logger.error("Failed to write to $path. Re-try next time", e)
                        }
                    }
                Thread.sleep(pollingIntervalMs)
            }
        } catch (e: InterruptedException) {
            // Graceful shutdown
        }
    }

Runtime.getRuntime().addShutdownHook(
    Thread {
        logger.info("Shutting down...")
        thread.interrupt()
    },
)

thread.start()
thread.join()
