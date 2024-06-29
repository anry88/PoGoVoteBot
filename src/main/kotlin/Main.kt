import org.slf4j.LoggerFactory
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResultArticle
import org.telegram.telegrambots.meta.api.objects.inlinequery.inputmessagecontent.InputTextMessageContent
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import java.io.IOException
import java.time.Instant
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class VotingData(
    var title: String,
    val votes: MutableMap<String, MutableList<UserVote>>,
    val creationDate: Instant = Instant.now()
)

data class UserVote(
    val username: String?,
    val userId: Long,
    val fullName: String
)

val votingDataMap = mutableMapOf<String, VotingData>()
val emojiMap = mapOf(
    "vote_red" to "❤️",
    "vote_yellow" to "💛",
    "vote_blue" to "💙",
    "vote_envelope" to "💌"
)

fun loadToken(): String {
    val properties = Properties()
    try {
        properties.load(Thread.currentThread().contextClassLoader.getResourceAsStream("config.properties"))
    } catch (e: IOException) {
        e.printStackTrace()
    }
    return properties.getProperty("bot.token") ?: throw IllegalArgumentException("bot.token not found in properties")
}

class VotingBot : TelegramLongPollingBot() {
    private val logger = LoggerFactory.getLogger(VotingBot::class.java)
    private val botToken: String = loadToken()

    init {
        scheduleOldVotingCleanup()
    }

    override fun getBotToken(): String = botToken

    override fun getBotUsername(): String = "PoGoVoteBot"

    override fun onUpdateReceived(update: Update) {
        logger.info("Received update: $update")
        when {
            update.hasInlineQuery() -> handleInlineQuery(update)
            update.hasCallbackQuery() -> handleCallbackQuery(update)
            else -> logger.warn("Unknown update type: $update")
        }
    }

    private fun handleInlineQuery(update: Update) {
        val inlineQuery = update.inlineQuery
        val queryId = inlineQuery.id
        val queryText = inlineQuery.query

        if (queryText.isBlank()) {
            logger.error("Query text is empty, ignoring inline query.")
            return
        }

        logger.info("Handling inline query: $queryText")

        val article = InlineQueryResultArticle().apply {
            id = queryId
            title = "Создать голосование: \n$queryText"
            inputMessageContent = InputTextMessageContent().apply {
                messageText = queryText
            }
            replyMarkup = InlineKeyboardMarkup().apply {
                keyboard = createVoteButtons(queryId)
            }
        }

        votingDataMap[queryId] = VotingData(queryText, mutableMapOf())

        execute(org.telegram.telegrambots.meta.api.methods.AnswerInlineQuery().apply {
            this.inlineQueryId = queryId
            this.results = listOf(article)
            this.isPersonal = true
        })
    }

    private fun handleCallbackQuery(update: Update) {
        val callbackQuery = update.callbackQuery
        val inlineMessageId = callbackQuery.inlineMessageId
        val from = callbackQuery.from
        val fromUsername = from.userName
        val fromUserId = from.id
        val fromFullName = "${from.firstName} ${from.lastName ?: ""}".trim()
        val callbackData = callbackQuery.data.split("|")
        val voteType = callbackData[0]
        val queryId = callbackData[1]

        val votingData = votingDataMap[queryId]
        if (votingData == null) {
            logger.error("No voting data found for queryId: $queryId")
            return
        }

        logger.info("Handling callback query: $voteType from $fromFullName")

        val votesInMessage = votingData.votes

        // Удаляем пользователя из всех списков
        for ((key, userVotes) in votesInMessage) {
            userVotes.removeIf { it.userId == fromUserId }
            // Удаляем список, если он пустой
            if (userVotes.isEmpty()) {
                votesInMessage.remove(key)
            }
        }

        // Добавляем пользователя в новый список
        val userVotes = votesInMessage.getOrPut(voteType) { mutableListOf() }
        userVotes.add(UserVote(fromUsername, fromUserId, fromFullName))

        val newText = buildVoteText(votingData)

        if (inlineMessageId != null) {
            execute(EditMessageText().apply {
                this.inlineMessageId = inlineMessageId
                this.text = newText
                this.replyMarkup = InlineKeyboardMarkup().apply {
                    keyboard = createVoteButtons(queryId)
                }
            })
        } else {
            val message = callbackQuery.message
            if (message != null) {
                val currentText = message.text ?: ""
                if (currentText != newText) {
                    execute(EditMessageText().apply {
                        this.chatId = message.chatId.toString()
                        this.messageId = message.messageId
                        this.text = newText
                        this.replyMarkup = InlineKeyboardMarkup().apply {
                            keyboard = createVoteButtons(queryId)
                        }
                    })
                } else {
                    logger.info("Message content is the same, no need to update.")
                }
            } else {
                logger.error("Both message and inlineMessageId are null in callback query, cannot process vote.")
                return
            }
        }

        // Ответ на callback query, чтобы показать, что запрос обработан
        execute(AnswerCallbackQuery().apply {
            this.callbackQueryId = callbackQuery.id
            this.text = "Ваш голос учтен!"
            this.showAlert = false
        })
    }

    private fun buildVoteText(votingData: VotingData): String {
        val voteText = StringBuilder("${votingData.title}\n\n")

        votingData.votes.forEach { (voteType, userVotes) ->
            val emoji = emojiMap[voteType] ?: voteType
            val userLinks = userVotes.joinToString(", ") { userVote ->
                if (userVote.username != null) {
                    "@${userVote.username}"
                } else {
                    "[${userVote.fullName}](tg://user?id=${userVote.userId})"
                }
            }
            voteText.append("$emoji: $userLinks\n")
        }

        return voteText.toString()
    }

    private fun createVoteButtons(queryId: String): List<List<InlineKeyboardButton>> {
        return listOf(
            listOf(
                InlineKeyboardButton().apply { text = "❤️"; callbackData = "vote_red|$queryId" },
                InlineKeyboardButton().apply { text = "💛"; callbackData = "vote_yellow|$queryId" },
                InlineKeyboardButton().apply { text = "💙"; callbackData = "vote_blue|$queryId" },
                InlineKeyboardButton().apply { text = "💌"; callbackData = "vote_envelope|$queryId" }
            )
        )
    }

    private fun scheduleOldVotingCleanup() {
        val scheduler = Executors.newScheduledThreadPool(1)
        scheduler.scheduleAtFixedRate({
            val now = Instant.now()
            val iterator = votingDataMap.entries.iterator()

            while (iterator.hasNext()) {
                val entry = iterator.next()
                val votingData = entry.value
                if (votingData.creationDate.plus(7, TimeUnit.DAYS.toChronoUnit()).isBefore(now)) {
                    iterator.remove()
                    logger.info("Removed old voting: ${votingData.title}")
                }
            }
        }, 0, 1, TimeUnit.DAYS)
    }
}

fun main() {
    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)
    botsApi.registerBot(VotingBot())
}
