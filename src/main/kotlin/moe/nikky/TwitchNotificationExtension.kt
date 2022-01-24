package moe.nikky

import com.kotlindiscord.kord.extensions.DiscordRelayedException
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalChannel
import com.kotlindiscord.kord.extensions.commands.converters.impl.role
import com.kotlindiscord.kord.extensions.commands.converters.impl.string
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.chatGroupCommand
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.utils.envOrNull
import com.kotlindiscord.kord.extensions.utils.getJumpUrl
import com.kotlindiscord.kord.extensions.utils.getLocale
import com.kotlindiscord.kord.extensions.utils.respond
import com.kotlindiscord.kord.extensions.utils.scheduling.Scheduler
import com.kotlindiscord.kord.extensions.utils.scheduling.Task
import dev.kord.common.entity.ChannelType
import dev.kord.common.entity.Permission
import dev.kord.common.entity.PresenceStatus
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.ChannelBehavior
import dev.kord.core.behavior.channel.TopGuildMessageChannelBehavior
import dev.kord.core.behavior.channel.createWebhook
import dev.kord.core.behavior.execute
import dev.kord.core.behavior.getChannelOfOrNull
import dev.kord.core.entity.Guild
import dev.kord.core.entity.Message
import dev.kord.core.entity.Webhook
import dev.kord.core.entity.channel.NewsChannel
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.entity.channel.TopGuildMessageChannel
import dev.kord.rest.Image
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.create.embed
import dev.kord.rest.builder.message.modify.embed
import dev.kord.rest.request.KtorRequestException
import io.klogging.Klogging
import io.klogging.context.logContext
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.request.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import moe.nikky.checks.hasBotControl
import moe.nikky.db.DiscordbotDatabase
import moe.nikky.db.TwitchConfig
import org.koin.core.component.inject
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class TwitchNotificationExtension() : Extension(), Klogging {
    override val name = "Twitch Notifications"

    private val database: DiscordbotDatabase by inject()
    private var token: Token? = null
    private var tokenExpiration: Instant = Instant.DISTANT_PAST
    private val webhooksCache = mutableMapOf<Snowflake, Webhook>()

    companion object {
        private const val WEBHOOK_NAME = "twitch-notifications"
        private const val twitchApi = "https://api.twitch.tv/helix"
        private val clientId = envOrNull("TWITCH_CLIENT_ID")
        private val clientSecret = envOrNull("TWITCH_CLIENT_SECRET")

        private val requiredPermissions = arrayOf(
            Permission.ViewChannel,
            Permission.ManageWebhooks,
            Permission.SendMessages,
            Permission.ManageMessages,
            Permission.ReadMessageHistory,
        )
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
//    private val httpClient = kord.resources.httpClient

    private val httpClient = HttpClient(CIO) {
        install(JsonFeature) {
            serializer = KotlinxSerializer()
        }
//        install(Logging) {
//            logger = Logger.DEFAULT
//            level = LogLevel.INFO
//        }
    }

    private val scheduler = Scheduler()
    private val task: Task =
        scheduler.schedule(seconds = 15, startNow = true, pollingSeconds = 1, name = "Twitch Loop") {
            try {
                withContext(
                    logContext(
                        "event" to "TwitchLoop"
                    )
                ) {
                    logger.debugF { "checking streams" }
                    val token = httpClient.getToken()
                    if (token != null) {
                        checkStreams(kord.guilds.toList(), token)
                    } else {
                        logger.errorF { "failed to acquire token" }
                    }
                }
            } catch (e: Exception) {
                logger.errorF(e) { "failed in twitch loop" }
            } finally {
                restartTask()
            }
        }

    private fun restartTask() {
        task.restart()
    }

    inner class TwitchAddArgs : Arguments() {
        val role by role {
            name = "role"
            description = "notification ping"
        }
        val twitchUserName by string {
            name = "twitch"
            description = "Twitch username"
        }
        val channel by optionalChannel {
            name = "channel"
            description = "notification channel, defaults to current channel"
            requireChannelType(ChannelType.GuildText)
        }
    }

    inner class TwitchRemoveArgs : Arguments() {
        val twitchUserName by string {
            name = "twitch"
            description = "Twitch username"
        }
        val channel by optionalChannel {
            name = "channel"
            description = "notification channel, defaults to current channel"
            requireChannelType(ChannelType.GuildText)
        }
    }

    override suspend fun setup() {
        chatGroupCommand {
            name = "twitch"
            description = "be notified about more streamers"

            chatCommand(::TwitchAddArgs) {
                name = "add"
                description = "be notified about more streamers"
                locking = true

                check {
                    hasBotControl(database, event.getLocale())
                }

                requireBotPermissions(
                    *requiredPermissions
                )
                action {
                    withLogContext(event, guild) { guild ->
                        val responseMessage = add(
                            guild,
                            arguments,
                            event.message.channel,
                        )

                        val response = event.message.respond {
                            content = responseMessage
                        }
                        event.message.delete()
                        launch {
                            delay(30_000)
                            response.delete()
                        }
                    }
                }
            }
            chatCommand(::TwitchRemoveArgs) {
                name = "remove"
                description = "removes a streamer from notifications"

                check {
                    hasBotControl(database, event.getLocale())
                }

                requireBotPermissions(
                    Permission.ManageMessages
                )

                action {
                    withLogContext(event, guild) { guild ->
                        val responseMessage = remove(
                            guild,
                            arguments,
                            event.message.channel,
                        )

                        val response = event.message.respond {
                            content = responseMessage
                        }
                        event.message.delete()
                        launch {
                            delay(30_000)
                            response.delete()
                        }
                    }
                }

            }
        }
        ephemeralSlashCommand {
            name = "twitch"
            description = "twitch notifications"

            ephemeralSubCommand(::TwitchAddArgs) {
                name = "add"
                description = "be notified about more streamers"
                locking = true

                check {
                    hasBotControl(database)
                }

                requireBotPermissions(
                    *requiredPermissions
                )

                action {
                    withLogContext(event, guild) { guild ->
                        val responseMessage = add(
                            guild,
                            arguments,
                            event.interaction.channel,
                        )

                        respond {
                            content = responseMessage
                        }
                    }
                }
            }

            ephemeralSubCommand(::TwitchRemoveArgs) {
                name = "remove"
                description = "removes a streamer from notifications"

                check {
                    hasBotControl(database)
                }

                requireBotPermissions(
                    Permission.ManageMessages
                )

                action {
                    withLogContext(event, guild) { guild ->
                        val responseMessage = remove(
                            guild,
                            arguments,
                            event.interaction.channel,
                        )

                        respond {
                            content = responseMessage
                        }
                    }
                }
            }

            ephemeralSubCommand {
                name = "list"
                description = "lists all streamers in config"

                check {
                    hasBotControl(database)
                }

                action {
                    withLogContext(event, guild) { guild ->
                        val messages =
                            database.twitchConfigQueries.getAll(guildId = guild.id).executeAsList().map { entry ->
                                val message = entry.message?.let { channel.getMessageOrNull(it) }
                                """
                            <https://twitch.tv/${entry.twitchUserName}>
                            ${entry.role(guild).mention}
                            ${entry.channel(guild).mention}
                            ${message?.getJumpUrl()}
                        """.trimIndent()
                            }

                        val response = if (messages.isNotEmpty()) {
                            "registered twitch notifications: \n\n" + messages.joinToString("\n\n")
                        } else {
                            "no twitch notifications registered, get started with /twitch add "
                        }

                        respond {
                            content = response
                        }
                    }
                }

            }

            ephemeralSubCommand {
                name = "check"
                description = "check permissions in channel"

                requireBotPermissions(
                    *requiredPermissions
                )
                check {
                    hasBotControl(database)
                }

                action {
                    respond {
                        content = "OK"
                    }
                }
            }

            ephemeralSubCommand {
                name = "status"
                description = "check status of twitch background loop"

                check {
                    hasBotControl(database)
                }

                action {
                    respond {
                        content = "running: ${task.running}"
                    }
                }
            }
        }

//        event<ReadyEvent> {
//            action {
//                withContext(
//                    logContext(
//                        "event" to event::class.simpleName,
//                        "extension" to name
//                    )
//                ) {
//                    logger.infoF { "launching twitch background job" }
//                    backgroundJob = kord.launch(
//                        logContext(
//                            "event" to "TwitchLoop"
//                        ) + CoroutineName("TwitchLoop")
//                    ) {
//                        while (true) {
//                            delay(15_000)
//                            val token = httpClient.getToken()
//                            if (token != null) {
//                                checkStreams(kord.guilds.toList(), token)
//                            } else {
//                                logger.errorF { "failed to acquire token" }
//                            }
//                        }
//                    }
//                }
//            }
//        }
    }

    private suspend fun add(
        guild: Guild,
        arguments: TwitchAddArgs,
        currentChannel: ChannelBehavior,
    ): String {
        val channelInput = arguments.channel ?: currentChannel.asChannel()
        val channel = guild.getChannelOfOrNull<TextChannel>(channelInput.id)
            ?: guild.getChannelOfOrNull<NewsChannel>(channelInput.id)
            ?: relayError("must be a TextChannel or NewsChannel, was: ${channelInput.type}")

        val user = try {
            val token = httpClient.getToken() ?: relayError("cannot get twitch token")
            val userData = httpClient.getUsers(token, listOf(arguments.twitchUserName))
                ?: relayError("cannot fetch user data for <https://twitch.tv/${arguments.twitchUserName}>")
            userData[arguments.twitchUserName.lowercase()]
                ?: relayError("cannot fetch user data: $userData for <https://twitch.tv/${arguments.twitchUserName}>")
        } catch (e: IllegalStateException) {
            relayError(e.message
                ?: "unknown error fetching user data for <https://twitch.tv/${arguments.twitchUserName}>")
        }

        database.twitchConfigQueries.upsert(
            guildId = guild.id,
            channel = channel.id,
            twitchUserName = user.login,
            role = arguments.role.id,
            message = null
        )

        return "added ${user.display_name} <https://twitch.tv/${user.login}> to ${channelInput.mention} to notify ${arguments.role.mention}"
    }

    private suspend fun remove(guild: Guild, arguments: TwitchRemoveArgs, currentChannel: ChannelBehavior): String {
        val channelInput = arguments.channel ?: currentChannel.asChannel()
        val channel = guild.getChannelOfOrNull<TextChannel>(channelInput.id)
            ?: guild.getChannelOfOrNull<NewsChannel>(channelInput.id)
            ?: relayError("must be a TextChannel or NewsChannel, was: ${channelInput.type}")

        val toRemove = database.twitchConfigQueries.get(
            guildId = guild.id,
            channel = channel.id,
            twitchUserName = arguments.twitchUserName
        ).executeAsOneOrNull()

        toRemove?.message?.let {
            channel.deleteMessage(it)
        }

        database.twitchConfigQueries.delete(
            guildId = guild.id,
            channel = channel.id,
            twitchUserName = arguments.twitchUserName
        )

        return "removed ${arguments.twitchUserName} from ${channel.mention}"
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun getWebhook(channel: TopGuildMessageChannelBehavior): Webhook? {
        return try {
            webhooksCache.getOrPut(channel.id) {
                channel.webhooks.firstOrNull {
                    it.name == WEBHOOK_NAME
                }?.also { webhook ->
                    logger.traceF { "found webhook" }
                    webhooksCache[channel.id] = webhook
                } ?: channel.createWebhook(name = WEBHOOK_NAME) {
                    @Suppress("BlockingMethodInNonBlockingContext")
                    avatar = Image.raw(
                        data = TwitchNotificationExtension::class.java.getResourceAsStream("/twitch/TwitchGlitchPurple.png")
                            ?.readAllBytes() ?: error("failed to read bytes"),
                        format = Image.Format.PNG,
                    )
                }.also { webhook ->
                    logger.infoF { "created webhook $webhook" }
                    webhooksCache[channel.id] = webhook
                }
            }

//            webhooksCache[channel.id]?.also {
//                logger.traceF { "reusing webhook" }
//            } ?: channel.webhooks.firstOrNull {
//                it.name == WEBHOOK_NAME
//            }?.also { webhook ->
//                logger.traceF { "found webhook" }
//                webhooksCache[channel.id] = webhook
//            } ?: channel.createWebhook(name = WEBHOOK_NAME) {
//                @Suppress("BlockingMethodInNonBlockingContext")
//                avatar = Image.raw(
//                    data = TwitchNotificationExtension::class.java.getResourceAsStream("/twitch/TwitchGlitchPurple.png")
//                        ?.readAllBytes() ?: error("failed to read bytes"),
//                    format = Image.Format.PNG,
//                )
//            }.also { webhook ->
//                logger.infoF { "created webhook $webhook" }
//                webhooksCache[channel.id] = webhook
//            }
        } catch (e: KtorRequestException) {
            logger.errorF { e.message }
            null
        }
    }


    private suspend fun updateTwitchNotificationMessage(
        guild: Guild,
        twitchConfig: TwitchConfig,
        token: Token,
        userData: TwitchUserData,
        channelInfo: TwitchChannelInfo,
        streamData: StreamData?,
        gameData: TwitchGameData?,
        webhook: Webhook,
    ) {
        val channel = twitchConfig.channel(guild)
        when (channel) {
            is TextChannel, is NewsChannel -> {
            }
            else -> {
                logger.errorF { "channel: ${channel.name} is not a Text or News channel" }
            }
        }

        suspend fun updateMessageId(message: Message, publish: Boolean = true) {
            if (publish && channel is NewsChannel) {
                try {
                    logger.infoF { "publishing in ${channel.name}" }
                    channel.getMessage(message.id).publish()
                } catch (e: KtorRequestException) {
                    logger.errorF { "failed to publish" }
                }
            }
            database.twitchConfigQueries.updateMessage(
                message = message.id,
                guildId = guild.id,
                channel = channel.id,
                twitchUserName = twitchConfig.twitchUserName
            )
        }

        val oldMessage = twitchConfig.message?.let {
            channel.getMessageOrNull(it)
        } ?: findMessage(channel, userData, webhook)?.also { foundMessage ->
            updateMessageId(foundMessage, publish = false)
        }
        if (streamData != null) {
            // live
            if (oldMessage != null) {
                val containsMention = oldMessage.content.contains("""<@&\d+>""".toRegex())
                if (containsMention) {
                    // was online, editing message

                    // TODO: check title, timestamp and game_name, only edit if different
                    val oldEmbed = oldMessage.embeds.firstOrNull()
                    val messageContent =
                        "<https://twitch.tv/${userData.login}> \n ${twitchConfig.role(guild).mention}"
                    val editMessage = when {
                        oldEmbed == null -> true
                        oldMessage.content != messageContent -> true
                        oldEmbed.title != streamData.title -> true
                        oldEmbed.footer?.text != streamData.game_name -> true
                        oldEmbed.timestamp != streamData.started_at -> true
                        else -> false
                    }
                    if (editMessage) {
                        val messageId = kord.rest.webhook.editWebhookMessage(
                            webhook.id,
                            webhook.token!!,
                            oldMessage.id
                        ) {
                            content = messageContent
                            embed {
                                buildEmbed(userData, streamData, gameData)
                            }
                        }.id
                        val message = channel.getMessage(messageId)
                        updateMessageId(message, publish = true)
                    }
                    return
                }
            }
            // was offline, creating new message and deleting old message
            val message = webhook.execute(webhook.token!!) {
                username = userData.display_name
                avatarUrl = userData.profile_image_url
                content =
                    "<https://twitch.tv/${userData.login}> \n ${twitchConfig.role(guild).mention}"
                embed {
                    buildEmbed(userData, streamData, gameData)
                }
            }
            updateMessageId(message, publish = true)
            oldMessage?.delete()
        } else {
            // offline

            // TODO: delete message if VOD is disabled ?

            // check if it was online before
            val updateMessage = if (oldMessage != null) {
                oldMessage.content.contains("""<@&\d+>""".toRegex())
            } else {
                true
            }

            if (updateMessage) {
                val vod = httpClient.getLastVOD(token, userData.id)
                val message = "<https://twitch.tv/${userData.login}>\n" +
                        if (vod != null) {
                            """
                            <${vod.url}>
                            **${vod.title}**
                            ${channelInfo.game_name}
                            """.trimIndent()
                        } else {
                            """
                            _VOD url not available_
                            **${channelInfo.title}**
                            ${channelInfo.game_name}
                            """.trimIndent()
                        }
                val messageId = if (oldMessage != null) {
                    val messageId = kord.rest.webhook.editWebhookMessage(webhook.id, webhook.token!!, oldMessage.id) {
                        content = message
                        embeds = mutableListOf()
                    }.id
                    channel.getMessage(messageId)
                } else {
                    webhook.execute(webhook.token!!) {
                        username = userData.display_name
                        avatarUrl = userData.profile_image_url
                        content = message
                    }
                }
                updateMessageId(messageId, publish = false)
            }
        }
    }

    private suspend fun findMessage(
        channel: TopGuildMessageChannel,
        userData: TwitchUserData,
        webhook: Webhook,
    ): Message? {
        logger.debugF { "searching for message with author '${userData.display_name}' in '${channel.name}' webhook: ${webhook.id}" }
        return try {
            channel.getMessagesBefore(
                channel.lastMessageId ?: return null,
                100
            ).filter { message ->
//                val author = message.data.author
                val author = message.author ?: run {
                    logger.warnF { "author was null, messageId: ${message.id}" }
                    return@filter false
                }
                logger.traceF { "author: $author" }
                author.id == webhook.id && author.username == userData.display_name

            }.firstOrNull()
        } catch (e: NullPointerException) {
            logger.errorF(e) { "failed to find old message" }
            null
        }
    }

    private fun EmbedBuilder.buildEmbed(
        usersData: TwitchUserData,
        streamData: StreamData,
        gameData: TwitchGameData?,
    ) {
        author {
            name = usersData.login
            url = "https://twitch.tv/${usersData.login}"
            icon = usersData.profile_image_url
        }
        url = "https://twitch.tv/${usersData.login}"
        title = streamData.title
        timestamp = streamData.started_at
        if (gameData != null) {
            footer {
                text = gameData.name
                icon = gameData.box_art_url
                    .replace("{height}", "16")
                    .replace("{width}", "16")
            }
        } else {
            footer {
                text = streamData.game_name
            }
        }
    }

    private suspend fun checkStreams(guilds: List<Guild>, token: Token) = coroutineScope {
        val mappedTwitchConfigs = guilds.associateWith { guild ->
            database.getTwitchConfigs(guild)
        }

        logger.traceF { "checking twitch status for ${guilds.map { it.name }}" }

        val channels = mappedTwitchConfigs.keys.flatMap { guild ->
            val twitchConfig = mappedTwitchConfigs.getValue(guild)
            twitchConfig.map { it.channel(guild) }.distinct()
        }

        val webhooks = channels.mapNotNull { channel ->
            withContext(logContext("channel" to channel.asChannel().name)) {
                getWebhook(channel)
            }
        }.associateBy { it.channel.id }

        val streamDataMap = httpClient.getStreams(
            token,
            mappedTwitchConfigs.values.flatMap {
                it.map(TwitchConfig::twitchUserName)
            }.distinct()
        ) ?: return@coroutineScope
        val userDataMap = httpClient.getUsers(
            token,
            mappedTwitchConfigs.values.flatMap {
                it.map(TwitchConfig::twitchUserName)
            }.distinct()
        ) ?: return@coroutineScope
        val gameDataMap = httpClient.getGames(
            token,
            streamDataMap.values.map { it.game_name }
        ) ?: return@coroutineScope
        val channelInfoMap = httpClient.getChannelInfo(
            token,
            userDataMap.values.map { it.id }
        ) ?: return@coroutineScope

        if (streamDataMap.isNotEmpty()) {
            val userName = streamDataMap.values.random().user_name
            val message = when (streamDataMap.size) {
                1 -> userName
                else -> "$userName and ${streamDataMap.size - 1} More"
            }
            kord.editPresence {
                status = PresenceStatus.Online
                watching(message)
            }
        } else {
            kord.editPresence {
                status = PresenceStatus.Idle
                afk = true
            }
        }

        mappedTwitchConfigs.entries.chunked(10).forEach { chunk ->
            coroutineScope {
                chunk.forEach { (guild, twitchConfigs) ->
                    launch() {
                        withContext(
                            logContext(
                                "guild" to guild.name
                            )
                        ) {
                            try {
                                twitchConfigs.forEach { twitchConfig ->
                                    val userData =
                                        userDataMap[twitchConfig.twitchUserName.lowercase()] ?: return@withContext
                                    val channelInfo = channelInfoMap[userData.login.lowercase()] ?: return@withContext
                                    val webhook = webhooks[twitchConfig.channel] ?: return@withContext
                                    val streamData = streamDataMap[userData.login.lowercase()]
                                    val gameData = gameDataMap[streamData?.game_name?.lowercase()]
                                    updateTwitchNotificationMessage(
                                        guild,
                                        twitchConfig,
                                        token,
                                        userData,
                                        channelInfo,
                                        streamData,
                                        gameData,
                                        webhook
                                    )
                                }
                            } catch (e: DiscordRelayedException) {
                                logger.errorF { e.cause }
                            } catch (e: Exception) {
                                logger.errorF(e) { e.message }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun HttpClient.getToken(): Token? {
        token?.let { token ->
            if ((Clock.System.now() + Duration.minutes(1)) < tokenExpiration) {
                logger.traceF { "reusing token" }
                return token
            }
        }
        if (clientId == null || clientSecret == null) return null
        logger.infoF { "getting new token" }
        return post<Token>(urlString = "https://id.twitch.tv/oauth2/token") {
            parameter("client_id", clientId)
            parameter("client_secret", clientSecret)
            parameter("grant_type", "client_credentials")
        }.also {
            token = it
            tokenExpiration = Clock.System.now() + Duration.seconds(it.expires_in)
            logger.infoF { "new token: $token" }
            logger.infoF { "expiration: $tokenExpiration" }
        }
    }

    private suspend fun HttpClient.getStreams(token: Token, user_logins: List<String>): Map<String, StreamData>? {
        if (clientId == null || clientSecret == null) return null
        val chunkedList = user_logins.chunked(100)
        return chunkedList.flatMap { chunk ->
            get<JsonObject>(urlString = "$twitchApi/streams") {
                chunk.forEach {
                    parameter("user_login", it)
                }
                header("Client-ID", clientId)
                header("Authorization", "Bearer ${token.access_token}")
            }.parseData(StreamData.serializer())
        }.associateBy { it.user_name.lowercase() }
    }


    private suspend fun HttpClient.getUsers(token: Token, logins: List<String>): Map<String, TwitchUserData>? {
        if (clientId == null || clientSecret == null) return null
        val chunkedList = logins.chunked(100)
        return chunkedList.flatMap { chunk ->
            get<JsonObject>(urlString = "$twitchApi/users") {
                chunk.forEach {
                    parameter("login", it)
                }
                header("Client-ID", clientId)
                header("Authorization", "Bearer ${token.access_token}")
            }.parseData(TwitchUserData.serializer())
        }.associateBy { it.login.lowercase() }
    }

    private suspend fun HttpClient.getLastVOD(token: Token, userId: String): TwitchVideoData? {
        if (clientId == null || clientSecret == null) return null
        return try {
            get<JsonObject>(urlString = "$twitchApi/videos") {
                parameter("user_id", userId)
                parameter("last", "1")
                header("Client-ID", clientId)
                header("Authorization", "Bearer ${token.access_token}")
            }.parseData(TwitchVideoData.serializer()).firstOrNull()
        } catch (e: ServerResponseException) {
            logger.errorF { e.message }
            null
        }
    }

    private suspend fun HttpClient.getGames(token: Token, gameNames: List<String>): Map<String, TwitchGameData>? {
        if (clientId == null || clientSecret == null) return null
        val chunkedList = gameNames.chunked(100)
        return chunkedList.flatMap { chunk ->
            get<JsonObject>(urlString = "$twitchApi/games") {
                chunk.forEach {
                    parameter("name", it)
                }
                header("Client-ID", clientId)
                header("Authorization", "Bearer ${token.access_token}")
            }.parseData(TwitchGameData.serializer())
        }.associateBy { it.name.lowercase() }
    }

    private suspend fun HttpClient.getChannelInfo(
        token: Token,
        broadcasterIds: List<String>,
    ): Map<String, TwitchChannelInfo>? {
        if (clientId == null || clientSecret == null) return null
        val chunkedList = broadcasterIds.chunked(100)
        return chunkedList.flatMap { chunk ->
            get<JsonObject>(urlString = "$twitchApi/channels") {
                chunk.forEach {
                    parameter("broadcaster_id", it)
                }
                header("Client-ID", clientId)
                header("Authorization", "Bearer ${token.access_token}")
            }.parseData(TwitchChannelInfo.serializer())
        }.associateBy { it.broadcaster_login.lowercase() }
    }

    private suspend fun <T> JsonObject.parseData(serializer: KSerializer<T>, key: String = "data"): List<T> {
        if (this["error"] != null) {
            logger.errorF { "received: ${json.encodeToString(JsonObject.serializer(), this@parseData)}" }
            error(this["message"]!!.jsonPrimitive.content)
        }
        val array = when (val value = this[key]) {
            is JsonArray -> value
            null -> {
                logger.errorF { "key $key is missing from the twitch response" }
                return emptyList()
            }
            else -> {
                logger.errorF { "twitch data was not a array" }
                logger.errorF { json.encodeToString(JsonElement.serializer(), value) }
                return emptyList()
            }
        }
        return try {
            json.decodeFromJsonElement(
                ListSerializer(serializer),
                array
            )
        } catch (e: SerializationException) {
            logger.errorF(e) {
                "twitch data failed to parse key $key: \n${
                    json.encodeToString(JsonElement.serializer(),
                        array)
                }"
            }
            return emptyList()
        }
    }

}

@Serializable
private data class Token(
    val access_token: String,
    val expires_in: Long,
    val token_type: String,
)

@Serializable
private data class StreamData(
    val id: String,
    val user_id: String,
    val user_name: String,
    val game_id: String,
    val game_name: String,
    val type: String,
    val title: String,
    val viewer_count: Int,
    val started_at: Instant,
    val language: String,
    val thumbnail_url: String,
    val tag_ids: List<String>?,
    val is_mature: Boolean,
)

@Serializable
private data class TwitchUserData(
    val id: String,
    val login: String,
    val display_name: String,
    val description: String,
    val profile_image_url: String,
    val offline_image_url: String,
    val view_count: UInt,
    val created_at: String,
)

@Serializable
private data class TwitchVideoData(
    val id: String,
    val stream_id: String,
    val user_id: String,
    val user_name: String,
    val title: String,
    val description: String,
    val created_at: Instant,
    val published_at: Instant,
    val url: String,
    val thumbnail_url: String,
    val viewable: String,
    val view_count: UInt,
    val language: String,
    val type: String,
    val duration: String,
)

@Serializable
private data class TwitchGameData(
    val box_art_url: String,
    val id: String,
    val name: String,
)

@Serializable
private data class TwitchChannelInfo(
    val broadcaster_id: String,
    val broadcaster_login: String,
    val broadcaster_name: String,
    val broadcaster_language: String,
    val game_id: String,
    val game_name: String,
    val title: String,
    val delay: UInt,
)
