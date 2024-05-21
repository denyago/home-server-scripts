#!/usr/bin/env kscript

@file:DependsOn("org.http4k:http4k-bom:5.20.0.0")
@file:DependsOn("org.http4k:http4k-core:5.20.0.0")
@file:DependsOn("org.http4k:http4k-server-undertow:5.20.0.0")
@file:DependsOn("org.http4k:http4k-format-moshi:5.20.0.0")
@file:DependsOn("com.offbytwo:docopt:0.6.0.20150202", "log4j:log4j:1.2.17")

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.server.Undertow
import org.http4k.server.asServer
import org.docopt.Docopt
import java.lang.reflect.ParameterizedType

val usage = """
Present SMART information of drives. Pass them comma separated.
Usage: SmartmonServer.kts [options] <drives_files>

Options:
 --port <httpPort> HTTP Server Port [default: 9000]   
"""

val doArgs: MutableMap<String, Any> = Docopt(usage).parse(args.toList())
val port = doArgs.getOrDefault("--port", 9000).toString().toInt()
val drives = doArgs.getOrDefault("<drives_files>", "").toString().split(",")

println("Parsed script arguments are: \n$doArgs")
println("Parsed drives are: $drives")
println("Listening on port: $port")

fun runCommand(command: String): String {
    val process = ProcessBuilder(*command.split(" ").toTypedArray())
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
    val results = drives.mapNotNull {
        val out = runCommand("sudo smartctl -j -a $it")
        jsonAdapter.fromJson(out)
    }
    val resultsJson = listJsonAdapter.toJson(results)
    Response(OK).header("Content-Type", "application/json").body(resultsJson)
}

app.asServer(Undertow(port)).start()
