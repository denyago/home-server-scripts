#!/usr/bin/env kscript

@file:DependsOn("org.http4k:http4k-bom:5.20.0.0")
@file:DependsOn("org.http4k:http4k-core:5.20.0.0")
@file:DependsOn("org.http4k:http4k-server-undertow:5.20.0.0")
@file:DependsOn("org.http4k:http4k-format-moshi:5.20.0.0")
@file:DependsOn("com.offbytwo:docopt:0.6.0.20150202", "log4j:log4j:1.2.17")
@file:DependsOn("ch.qos.logback:logback-classic:1.2.9")

// The rest is "nailed in" by `./gradlew dependencies`.

@file:DependsOn("org.http4k:http4k-realtime-core:5.20.0.0")
@file:DependsOn("org.jboss.logging:jboss-logging:3.4.3.Final")
@file:DependsOn("org.jboss.xnio:xnio-api:3.8.8.Final")
@file:DependsOn("org.wildfly.common:wildfly-common:1.5.4.Final")
@file:DependsOn("org.wildfly.client:wildfly-client-config:1.0.1.Final")
@file:DependsOn("org.jboss.xnio:xnio-nio:3.8.8.Final")
@file:DependsOn("org.jboss.xnio:xnio-api:3.8.8.Final")
@file:DependsOn("org.jboss.threads:jboss-threads:3.5.0.Final")
@file:DependsOn("org.http4k:http4k-format-core:5.20.0.0")
@file:DependsOn("com.squareup.moshi:moshi:1.15.1")
@file:DependsOn("com.squareup.okio:okio-jvm:3.7.0")
@file:DependsOn("com.squareup.moshi:moshi-kotlin:1.15.1")

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.docopt.Docopt
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.server.Undertow
import org.http4k.server.asServer
import java.lang.reflect.ParameterizedType

val usage = """
Present SMART information of drives. Pass them comma separated.
Usage: SmartmonServer.kts [options] <drives_files>

Options:
 -p --port=<http_port>    HTTP Server Port [default: 9000]   
 -l --log-level=<level>  Log level [default: INFO]
"""

val doArgs: MutableMap<String, Any> = Docopt(usage).parse(args.toList())
val port = doArgs.getOrDefault("--port", 9000).toString().toInt()
val logLevel: Level = Level.toLevel(doArgs.getOrDefault("--log-level", "INFO").toString())
val drives = doArgs.getOrDefault("<drives_files>", "").toString().split(",")

val rootLogger = org.slf4j.LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
rootLogger.level = logLevel
val logger = org.slf4j.LoggerFactory.getLogger("SmartMonClient") as Logger
logger.level = logLevel

logger.info("Starting SmartMonServer: listening 0.0.0.0:$port, drives: ${drives.joinToString(",")}")

fun runCommand(command: String): String {
    val process =
        ProcessBuilder(*command.split(" ").toTypedArray())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

    process.waitFor()
    return process.inputStream.bufferedReader().readText()
}

val moshi: Moshi = Moshi.Builder().build()
val type: ParameterizedType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
val jsonAdapter: JsonAdapter<Map<String, Any>> = moshi.adapter(type)

val listType: ParameterizedType = Types.newParameterizedType(List::class.java, type)
val listJsonAdapter: JsonAdapter<List<Map<String, Any>>> = moshi.adapter(listType)

val app = { _: Request ->
    val results =
        drives.mapNotNull { driveFile ->
            try {
                val out = runCommand("sudo smartctl -j -a $driveFile")
                jsonAdapter.fromJson(out)
            } catch (e: Exception) {
                logger.error("Error reading $driveFile", e)
                null
            }
        }
    val resultsJson = listJsonAdapter.toJson(results)
    Response(OK).header("Content-Type", "application/json").body(resultsJson)
}

app.asServer(Undertow(port)).start()
