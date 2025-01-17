package moe.nikky

import dev.kordex.core.commands.Arguments
import dev.kordex.core.commands.application.slash.ephemeralSubCommand
import dev.kordex.core.commands.converters.impl.string
import dev.kordex.core.commands.converters.impl.user
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.ephemeralSlashCommand
import dev.kordex.core.extensions.ephemeralUserCommand
import dev.kordex.core.storage.Data
import dev.kordex.core.storage.StorageType
import dev.kordex.core.storage.StorageUnit
import dev.kordex.core.utils.suggestStringMap
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.GuildBehavior
import dev.kord.core.entity.User
import dev.kord.rest.builder.message.embed
import dev.kordex.core.i18n.toKey
import io.klogging.Klogging
import io.ktor.client.request.forms.*
import io.ktor.utils.io.*
import kotlinx.datetime.Clock
import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.koin.dsl.module
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

class LocalTimeExtension : Extension(), Klogging {
    override val name: String = "localtime"
    init {
        bot.getKoin().loadModules(
            listOf(
                module {
                    single { this@LocalTimeExtension }
                }
            )
        )
    }

    private val userConfig = StorageUnit(
        StorageType.Config,
        name,
        "timezone-config",
        TimezoneConfig::class
    )

    fun GuildBehavior.config(userId: Snowflake) =
        userConfig
            .withGuild(id)
            .withUser(userId)

    companion object {

        private val SUGGEST_TIMEZONES = listOf(
            "UTC", "GMT", "Europe/London",
            "CET", "Europe/Berlin", "Europe/Paris",
            "NZ", "Japan", "Asia/Tokyo",
            "US/Alaska", "US/Pacific", "US/Mountain",
            "US/Central", "US/Eastern", "Canada/Eastern",
            "America/New_York", "America/Sao_Paulo", "America/Chicago",
            "America/Los_Angeles", "Europe/Moscow", "Singapore",
        )
    }

    override suspend fun setup() {

        ephemeralSlashCommand(::TimezoneArgs) {
            name = "timezone".toKey()
            description = "list or set timezones".toKey()

            ephemeralSubCommand(::TimezoneArgs) {
                name = "set".toKey()
                description = "update your timezone".toKey()

                action {
                    withLogContext(event, guild) { guild ->
                        logger.infoF { "received timezone id: ${arguments.timezoneId}" }
                        val timezone = try {
                            TimeZone.of(arguments.timezoneId)
                        } catch (e: IllegalTimeZoneException) {
                            respond {
                                content =
                                    """Possibly you meant one of the following timezones?
                                        |for a full list of availabe zone ids run 
                                        |```
                                        |/timezone list
                                        |```
                                        |""".trimMargin()
                                embed {
                                    SUGGEST_TIMEZONES.mapNotNull { zoneId ->
                                        try {
                                            TimeZone.of(zoneId)
                                        } catch (e: IllegalTimeZoneException) {
                                            logger.errorF { "incorrect timezone id: '$zoneId'" }
                                            null
                                        }
                                    }.forEach { timezone ->
                                        field {
                                            val formattedTime = formatTime(Clock.System.now(), timezone)
                                            name = timezone.id
                                            value = "\uD83D\uDD57 `$formattedTime`"
                                            inline = true
                                        }
                                    }
                                }
                            }
                            return@withLogContext
                        }

                        val configStorage = guild.config(event.interaction.user.id)
                        configStorage.save(
                            TimezoneConfig(timezoneId = timezone.id)
                        )

                        respond {
                            val formattedTime = formatTime(Clock.System.now(), timezone)

                            content =
                                "Timezone has been set to **${timezone.id}**. Your current time should be `$formattedTime`"
                        }
                    }
                }
            }

            ephemeralSubCommand() {
                name = "list".toKey()
                description = "sends a list of valid timezones".toKey()

                action {
                    withLogContext(event, guild) { guild ->
                        val timezones = TimeZone.sortedList().map { (tz, offset) ->
                            "${offset.toString().padEnd(10, ' ')} ${tz.id}"
                        }

                        respond {
                            content = "a list of valid timezone ids is in the attachment"

                            addFile(
                                "timezones.txt",
                                ChannelProvider {
                                    ByteReadChannel(timezones.joinToString("\n"))
                                }
                            )
                        }
                    }
                }
            }

        }

        ephemeralUserCommand {
            name = "Local Time".toKey()

            action {
                withLogContext(event, guild) { guild ->
                    val targetUser = event.interaction.getTarget()
                    val selfUser = event.interaction.user

                    respond {
                        content = calculateDifference(
                            guild = guild,
                            targetUser = targetUser,
                            selfUser = selfUser
                        )
                    }
                }
            }
        }
        ephemeralSlashCommand(::TimezoneTargetArgs) {
            name = "LocalTime".toKey()
            description = "get the local time for a user".toKey()

            action {
                withLogContext(event, guild) { guild ->
                    val targetUser = arguments.user
                    val selfUser = event.interaction.user

                    respond {
                        content = calculateDifference(
                            guild = guild,
                            targetUser = targetUser,
                            selfUser = selfUser
                        )
                    }
                }
            }
        }
    }

    suspend fun calculateDifference(
        guild: GuildBehavior,
        targetUser: User,
        selfUser: User,
    ): String {
        val targetConfig = guild.config(targetUser.id).get()
        val selfConfig = guild.config(selfUser.id).get()

        if (targetConfig == null) {
            return "${targetUser.mention} has not set their timezone"
        } else {
            val now = Clock.System.now()

            val difference = selfConfig?.let { selfConfig ->
                val selfOffset = selfConfig.timezone.offsetAt(now).totalSeconds.seconds
                val targetOffset = targetConfig.timezone.offsetAt(now).totalSeconds.seconds

                logger.infoF { "selfConfig: $selfConfig" }
                logger.infoF { "targetConfig: $targetConfig" }
                logger.infoF { "selfOffset: $selfOffset" }
                logger.infoF { "targetOffset: $targetOffset" }

                val difference = targetOffset - selfOffset
                if (difference != ZERO) {
                    ", relative offset is `$difference`"
                } else null
            } ?: ""
            val formattedTime = formatTime(now, targetConfig.timezone)
            return "Time in ${targetUser.mention}'s timezone: `$formattedTime` (`${targetConfig.timezoneId}`)$difference"
        }
    }

    private fun formatTime(
        instant: Instant,
        timeZone: TimeZone,
    ): String {
        val localDateTime = instant.toLocalDateTime(timeZone = timeZone)
        return "%02d:%02d".format(localDateTime.hour, localDateTime.minute)
    }

    inner class TimezoneArgs : Arguments() {
        val timezoneId by string {
            name = "timezone".toKey()
            description = "time zone id".toKey()

            autoComplete { event ->
                val now = Clock.System.now()
                logger.infoF { "running autocomplete: ${focusedOption.focused} ${focusedOption.value}" }
                suggestStringMap(
                    SUGGEST_TIMEZONES.mapNotNull { zoneId ->
                        try {
                            zoneId to TimeZone.of(zoneId)
                        } catch (e: IllegalTimeZoneException) {
                            logger.errorF { "incorrect timezone id: '$zoneId'" }
                            null
                        }
                    }.associate { (zoneId, timeZone) ->
                        "$zoneId \uD83D\uDD57 ${formatTime(now, timeZone)}" to zoneId
                    }.toMap()
                )
            }
        }
    }

    inner class TimezoneTargetArgs : Arguments() {
        val user by user {
            name = "user".toKey()
            description = "user to get local time for".toKey()
        }
    }
}


@Serializable
data class TimezoneConfig(
    val timezoneId: String,
) : Data {
    val timezone: TimeZone by lazy { TimeZone.of(timezoneId) }
}

fun TimeZone.Companion.sortedList(): List<Pair<TimeZone, Duration>> {
    val now = Clock.System.now()
    return TimeZone.availableZoneIds
        .map { zoneId ->
            TimeZone.of(zoneId)
        }
        .sortedBy { tz ->
            tz.offsetAt(now).totalSeconds
        }.map { tz ->
            val offset = tz.offsetAt(now)
            val hoursOffset = -offset.totalSeconds.seconds
            tz to hoursOffset
        }
}

fun main() {
    TimeZone.sortedList().forEach { (tz, offset) ->
        println("${offset.toString().padEnd(10, ' ')} ${tz.id}")
    }
}
