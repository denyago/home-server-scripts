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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
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

sealed interface Task {
    val src: Path
    val status: Status
    val log: String

    sealed interface Destructive : Task

    sealed interface NonDestructive : Task

    data class Copy(
        override val src: Path,
        val dest: Path,
        override val status: Status,
        override val log: String,
    ) : NonDestructive {
        constructor(src: Path, baseSourceDir: String, baseDestDir: String) : this(
            src = src,
            dest =
                Paths.get(
                    baseDestDir,
                    src.toString().removePrefix(baseSourceDir),
                ),
            status = Status.Pending,
            log = "",
        )
    }

    data class UploadGooglePhoto(
        override val src: Path,
        val albumName: String,
        override val status: Status,
        override val log: String,
    ) : NonDestructive {
        constructor(src: Path, baseSourceDir: String) : this(
            src = src,
            albumName = sourceToAlbumName(src, baseSourceDir),
            status = Status.Pending,
            log = "",
        )
    }

    data class UploadPhotoPrism(
        override val src: Path,
        val albumName: String,
        override val status: Status,
        override val log: String,
    ) : NonDestructive {
        constructor(src: Path, baseSourceDir: String) : this(
            src = src,
            albumName = sourceToAlbumName(src, baseSourceDir),
            status = Status.Pending,
            log = "",
        )
    }

    data class Delete(
        override val src: Path,
        override val status: Status,
        override val log: String,
    ) : Destructive {
        constructor(src: Path) : this(
            src = src,
            status = Status.Pending,
            log = "",
        )
    }

    enum class Status {
        Pending,
        Completed,
        Failed,
    }

    companion object {
        fun sourceToAlbumName(
            src: Path,
            baseSourceDir: String,
        ): String = Path.of(src.toString().removePrefix(baseSourceDir)).parent.toString()
    }
}

suspend fun doCopy(task: Task.Copy): Task =
    try {
        task.src.toFile().copyTo(task.dest.toFile(), false)
        task.copy(
            log = "Copied ${task.src} to ${task.dest}",
            status = Task.Status.Completed,
        )
    } catch (e: Exception) {
        task.copy(
            log = "Failed to copy ${task.src} to ${task.dest}: ${e.message}",
            status = Task.Status.Failed,
        )
    }

suspend fun doUploadGooglePhoto(task: Task.UploadGooglePhoto): Task =
    try {
        task.copy(
            log = "Uploaded ${task.src} to Google Photos album ${task.albumName}",
            status = Task.Status.Completed,
        )
    } catch (e: Exception) {
        task.copy(
            log = "Failed to upload ${task.src} to Google Photos album ${task.albumName}: ${e.message}",
            status = Task.Status.Failed,
        )
    }

suspend fun doUploadPhotoPrism(task: Task.UploadPhotoPrism): Task =
    try {
        task.copy(
            log = "Uploaded ${task.src} to PhotoPrism album ${task.albumName}",
            status = Task.Status.Completed,
        )
    } catch (e: Exception) {
        task.copy(
            log = "Failed to upload ${task.src} to PhotoPrism album ${task.albumName}: ${e.message}",
            status = Task.Status.Failed,
        )
    }

suspend fun doDelete(task: Task.Delete): Task =
    try {
        task.src.toFile().delete()
        task.copy(
            log = "Deleted ${task.src}",
            status = Task.Status.Completed,
        )
    } catch (e: Exception) {
        task.copy(
            log = "Failed to delete ${task.src}: ${e.message}",
            status = Task.Status.Failed,
        )
    }

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
                            val tasks =
                                listOf(
                                    Task.Copy(src, from, to),
                                    Task.UploadGooglePhoto(src, from),
                                    Task.UploadPhotoPrism(src, from),
                                    Task.Delete(src),
                                )
                            logger.info("Created tasks: $tasks")
                            val nonDestructiveTasks: List<Task.NonDestructive> =
                                tasks.filterIsInstance<Task.NonDestructive>()
                            val destructiveTasks: List<Task.Destructive> =
                                tasks.filterIsInstance<Task.Destructive>()
                            val deferredTasks: List<Deferred<Task>> =
                                nonDestructiveTasks.map { task ->
                                    async {
                                        when (task) {
                                            is Task.Copy -> doCopy(task)
                                            is Task.UploadGooglePhoto -> doUploadGooglePhoto(task)
                                            is Task.UploadPhotoPrism -> doUploadPhotoPrism(task)
                                            else -> TODO() // TODO: remove
                                        }
                                    }
                                } +
                                    destructiveTasks.map { task ->
                                        when (task) {
                                            is Task.Delete -> async { doDelete(task) }
                                            else -> TODO() // TODO: remove
                                        }
                                    }
                            val results = deferredTasks.map { deferred -> deferred.await() }
                            logger.info("Results: $results")
                        }
                    } catch (e: FileAlreadyExistsException) {
                        logger.error("File already exists $dest", e)
                    }
                }
            }
        }
    }
}

watchChannel.close()

fun moveTo(
    src: Path,
    dest: Path,
) {
    src.toFile().copyTo(dest.toFile(), false)
    src.toFile().delete()
}
