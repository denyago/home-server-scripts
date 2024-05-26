#!/usr/bin/env kscript

@file:Repository("https://jitpack.io")

@file:DependsOn("com.github.vishna:watchservice-ktx:master-SNAPSHOT")
@file:DependsOn("com.offbytwo:docopt:0.6.0.20150202", "log4j:log4j:1.2.17")
@file:DependsOn("ch.qos.logback:logback-classic:1.2.9")

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import dev.vishna.watchservice.KWatchEvent.Kind.Created
import dev.vishna.watchservice.KWatchEvent.Kind.Deleted
import dev.vishna.watchservice.KWatchEvent.Kind.Initialized
import dev.vishna.watchservice.KWatchEvent.Kind.Modified
import dev.vishna.watchservice.asWatchChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.docopt.Docopt
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.WatchService
import kotlin.system.exitProcess

val usage = """
Moves files from one directory to another.
Usage: FileWatcher [options] <from> <to>

Options:
 -l --log-level=<level>  Log level [default: INFO]
"""

val doArgs: MutableMap<String, Any> = Docopt(usage).parse(args.toList())
val logLevel: Level = Level.toLevel(doArgs.getOrDefault("--log-level", "INFO").toString())
val from = doArgs["<from>"].toString()
val to = doArgs["<to>"].toString()

val errorMessages =
    listOfNotNull(
        if (!File(from).exists()) "'from' directory does not exist" else null,
        if (!File(to).exists()) "'to' directory does not exist" else null,
    )
if (errorMessages.isNotEmpty()) {
    errorMessages.forEach { println(it) }
    exitProcess(1)
}

val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
rootLogger.level = logLevel
val logger = LoggerFactory.getLogger("FileWatcher") as Logger
logger.level = logLevel

logger.info("Moving files from $from to $to and preserving the directory structure")

val currentDirectory = File(from)
val watchService: WatchService = FileSystems.getDefault().newWatchService()

val watchChannel = currentDirectory.asWatchChannel()

runBlocking {
    launch {
        watchChannel.consumeEach {
            logger.info("Event: $it")
            when (it.kind) {
                Initialized, Deleted, Modified -> logger.info("Ignoring event")
                Created -> {
                    val src = it.file.toPath()
                    val dest = Paths.get(to, src.toString().removePrefix(from))
                    logger.info("Moving $src to $dest")
                    try {
                        if (it.file.isDirectory) {
                            dest.toFile().mkdirs().also { result ->
                                if (result) {
                                    logger.info("Created directory $dest")
                                } else {
                                    logger.error("Failed to create directory $dest")
                                }
                            }
                        } else {
                            // 0. Create a task file:
                            //   1. Upload to Google Photos
                            //   2. Upload to Photoprism
                            //      1. Upload
                            //      2. Make Album
                            //      3. Add to Album
                            //   3. Delete the file
                            //   4. Delete the task file
                            moveTo(src, dest)
                        }
                    } catch (e: FileAlreadyExistsException) {
                        logger.error("File already exists $dest", e)
                    }
                }
            }
        }
    }
}

watchChannel.close() // clean up resources afterwards
//
// val watcher = FileSystems.getDefault().newWatchService()
//
// fun recursivelyRegister(path: Path) {
//     path.toFile().listFiles()?.forEach {
//         if (it.isDirectory) {
//             it.toPath().register(watcher, ENTRY_CREATE, ENTRY_MODIFY)
//             recursivelyRegister(it.toPath())
//         }
//     }
// }
//
// recursivelyRegister(Paths.get(from))
//
// // Create same directory structure in the destination directory
// Paths.get(from).toFile().walk().forEach {
//     val dest = Paths.get(to, it.toString().removePrefix(from))
//     if (it.isDirectory) {
//         dest.toFile().mkdir()
//     }
// }
//
// while (true) {
//     val key = watcher.take()
//     key.pollEvents()
//         .also {
//             logger.info("Events: $it")
//         }
//         .forEach { event ->
//             val src = Paths.get(from, event.context().toString())
//             val dest = Paths.get(to, src.toString().removePrefix(from))
//             logger.info("Moving $src to $dest")
//             try {
//                 src.toFile().copyTo(dest.toFile(), false)
//             } catch (e: FileAlreadyExistsException) {
//                 logger.error("File already exists $dest", e)
//             }
//         }
//     key.reset()
// }

fun moveTo(
    src: Path,
    dest: Path,
) {
    src.toFile().copyTo(dest.toFile(), false)
    src.toFile().delete()
}
