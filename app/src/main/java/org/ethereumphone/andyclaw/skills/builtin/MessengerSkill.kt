package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.BuildConfig
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.contactssdk.ContactsSDK
import org.ethereumphone.walletsdk.WalletSDK
import org.ethereumhpone.messengersdk.MessengerSDK
import org.ethereumhpone.messengersdk.SdkException
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService

/**
 * XMTP messaging skill using the MessengerSDK IdentityClient.
 *
 * Creates an isolated XMTP identity for the AI and uses it to send
 * messages to wallet addresses. On the first message, the AI's identity
 * is also saved as a device contact (using ContactsSDK) so the user
 * can find the AI in their contacts list.
 *
 * Requires the XMTP Messenger app to be installed on the device.
 */
class MessengerSkill(private val context: Context) : AndyClawSkill {
    override val id = "messenger"
    override val name = "Messenger"

    companion object {
        private const val TAG = "MessengerSkill"
    }

    @Volatile private var sdk: MessengerSDK? = null
    @Volatile private var identityReady = false
    @Volatile private var contactCreated = false
    private val initMutex = Mutex()
    private val contactsSDK by lazy { ContactsSDK(context) }
    private val walletSDK: WalletSDK by lazy {
        val rpc = "https://eth-mainnet.g.alchemy.com/v2/${BuildConfig.ALCHEMY_API}"
        WalletSDK(
            context = context,
            web3jInstance = Web3j.build(HttpService(rpc)),
            bundlerRPCUrl = "https://api.pimlico.io/v2/1/rpc?apikey=${BuildConfig.BUNDLER_API}",
        )
    }

    override val baseManifest = SkillManifest(
        description = "Send and read XMTP messages using the device's Messenger app. The AI has its own " +
                "XMTP identity and can send direct messages and read incoming conversations. " +
                "Use send_message_to_user to write to the device owner, send_xmtp_message to " +
                "message an arbitrary wallet address, list_conversations to see all conversations, " +
                "and read_messages to read messages in a conversation. " +
                "Requires the XMTP Messenger app to be installed on the device.",
        tools = listOf(
            ToolDefinition(
                name = "send_message_to_user",
                description = "Send an XMTP message to the device owner. " +
                        "Automatically resolves the user's wallet address from the device " +
                        "and sends the message. Use this when the user asks you to " +
                        "send/write them a message.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "message" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The message content to send"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("message"),
                    )),
                )),
                requiredPermissions = listOf(
                    "android.permission.READ_CONTACTS",
                    "android.permission.WRITE_CONTACTS",
                ),
            ),
            ToolDefinition(
                name = "send_xmtp_message",
                description = "Send an XMTP message to a specific wallet address. " +
                        "The message is sent from the AI's own XMTP identity. " +
                        "Use this when you already have the recipient's wallet address.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "recipient_address" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Ethereum wallet address of the recipient (0x-prefixed, 42 characters)"
                            ),
                        )),
                        "message" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The message content to send"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("recipient_address"),
                        JsonPrimitive("message"),
                    )),
                )),
                requiredPermissions = listOf(
                    "android.permission.READ_CONTACTS",
                    "android.permission.WRITE_CONTACTS",
                ),
            ),
            ToolDefinition(
                name = "list_conversations",
                description = "List all XMTP conversations for the AI's identity. " +
                        "Syncs conversations from the network first, then returns the list. " +
                        "Use this to discover conversation IDs before reading messages.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(emptyMap()),
                )),
            ),
            ToolDefinition(
                name = "read_messages",
                description = "Read messages from a specific XMTP conversation. " +
                        "Use list_conversations first to get the conversation ID.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "conversation_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The conversation ID to read messages from"),
                        )),
                        "limit" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Maximum number of messages to return (default 20)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("conversation_id"),
                    )),
                )),
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "send_message_to_user" -> sendMessageToUser(params)
            "send_xmtp_message" -> sendMessage(params)
            "list_conversations" -> listConversations()
            "read_messages" -> readMessages(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    /**
     * Ensures the MessengerSDK is initialized, the identity service is bound,
     * and an identity has been created. Safe to call multiple times — will
     * only perform setup once.
     */
    private suspend fun ensureIdentityReady(): SkillResult? {
        return initMutex.withLock {
            try {
                // Step 1: Get SDK instance
                if (sdk == null) {
                    sdk = MessengerSDK.getInstance(context)
                }
                val messengerSdk = sdk!!

                // Step 2: Bind to identity service (idempotent — re-binds if connection was lost)
                withContext(Dispatchers.IO) {
                    messengerSdk.identity.bind()
                    messengerSdk.identity.awaitConnected()
                }
                Log.d(TAG, "Identity service connected")

                // Step 3: Create identity if one doesn't exist yet (only once)
                if (!identityReady) {
                    val hasIdentity = withContext(Dispatchers.IO) {
                        messengerSdk.identity.hasIdentity()
                    }
                    if (!hasIdentity) {
                        withContext(Dispatchers.IO) {
                            messengerSdk.identity.createIdentity()
                        }
                        Log.d(TAG, "Created new XMTP identity")
                    }
                    identityReady = true
                }

                null // success, no error
            } catch (e: SdkException) {
                Log.e(TAG, "Failed to initialize identity: ${e.message}", e)
                val msg = when {
                    e.message?.contains("not installed", ignoreCase = true) == true ->
                        "XMTP Messenger app is not installed on this device. " +
                                "Install it to enable messaging."
                    e.message?.contains("not connected", ignoreCase = true) == true ->
                        "Failed to connect to the Messenger identity service. " +
                                "Make sure the XMTP Messenger app is running."
                    else -> "Failed to initialize XMTP identity: ${e.message}"
                }
                SkillResult.Error(msg)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error initializing identity: ${e.message}", e)
                SkillResult.Error("Failed to initialize XMTP messaging: ${e.message}")
            }
        }
    }

    /**
     * On the first message send, adds the AI as a device contact using
     * ContactsSDK with the AI's name and XMTP identity address.
     */
    private suspend fun ensureContactCreated() {
        Log.i(TAG, "ensureContactCreated called — contactCreated=$contactCreated, sdk=${if (sdk != null) "present" else "null"}")
        if (contactCreated) return

        try {
            val messengerSdk = sdk
            if (messengerSdk == null) {
                Log.w(TAG, "sdk is null in ensureContactCreated, skipping")
                return
            }
            val identityAddress = withContext(Dispatchers.IO) {
                messengerSdk.identity.getIdentityAddress()
            }
            Log.i(TAG, "XMTP identity address: $identityAddress")

            if (identityAddress.isNullOrBlank()) {
                Log.w(TAG, "Identity address is empty, skipping contact creation")
                return
            }

            val aiName = (context.applicationContext as? NodeApp)
                ?.userStoryManager?.getAiName() ?: "AndyClaw"

            Log.i(TAG, "Adding contact: name='$aiName', ethAddress='$identityAddress'")

            val contactId = withContext(Dispatchers.IO) {
                contactsSDK.addContact(
                    displayName = aiName,
                    ethAddress = identityAddress,
                )
            }

            if (contactId != null) {
                Log.i(TAG, "Created contact '$aiName' (id=$contactId) with XMTP address $identityAddress")
            } else {
                Log.w(TAG, "addContact returned null — contact creation failed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not create AI contact: ${e.message}", e)
        }

        // Mark as done regardless of success — don't retry every message
        contactCreated = true
    }

    private suspend fun sendMessageToUser(params: JsonObject): SkillResult {
        val message = params["message"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: message")

        val userAddress = try {
            withContext(Dispatchers.IO) { walletSDK.getAddress() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get user wallet address: ${e.message}", e)
            return SkillResult.Error("Failed to resolve user's wallet address: ${e.message}")
        }

        if (userAddress.isNullOrBlank()) {
            return SkillResult.Error("Could not determine user's wallet address")
        }

        val messageParams = buildJsonObject {
            put("recipient_address", userAddress)
            put("message", message)
        }
        return sendMessage(messageParams)
    }

    private suspend fun listConversations(): SkillResult {
        ensureIdentityReady()?.let { return it }

        val messengerSdk = sdk
            ?: return SkillResult.Error("MessengerSDK not available")

        return try {
            val conversations = withContext(Dispatchers.IO) {
                messengerSdk.identity.syncConversations()
                messengerSdk.identity.getConversations()
            }
            val result = JsonArray(conversations.map { conv ->
                buildJsonObject {
                    put("id", conv.id)
                    put("peerAddress", conv.peerAddress)
                    put("createdAtMs", conv.createdAtMs)
                }
            })
            Log.d(TAG, "Listed ${conversations.size} conversations")
            SkillResult.Success(result.toString())
        } catch (e: SdkException) {
            Log.e(TAG, "Failed to list conversations: ${e.message}", e)
            SkillResult.Error("Failed to list XMTP conversations: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error listing conversations: ${e.message}", e)
            SkillResult.Error("Failed to list conversations: ${e.message}")
        }
    }

    private suspend fun readMessages(params: JsonObject): SkillResult {
        val conversationId = params["conversation_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: conversation_id")
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 20

        ensureIdentityReady()?.let { return it }

        val messengerSdk = sdk
            ?: return SkillResult.Error("MessengerSDK not available")

        return try {
            val messages = withContext(Dispatchers.IO) {
                messengerSdk.identity.getMessages(conversationId)
            }
            val limited = messages.takeLast(limit)
            val result = JsonArray(limited.map { msg ->
                buildJsonObject {
                    put("id", msg.id)
                    put("senderInboxId", msg.senderInboxId)
                    put("body", msg.body)
                    put("sentAtMs", msg.sentAtMs)
                    put("isMe", msg.isMe)
                }
            })
            Log.d(TAG, "Read ${limited.size} messages from conversation $conversationId")
            SkillResult.Success(result.toString())
        } catch (e: SdkException) {
            Log.e(TAG, "Failed to read messages: ${e.message}", e)
            SkillResult.Error("Failed to read XMTP messages: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error reading messages: ${e.message}", e)
            SkillResult.Error("Failed to read messages: ${e.message}")
        }
    }

    private suspend fun sendMessage(params: JsonObject): SkillResult {
        val recipientAddress = params["recipient_address"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: recipient_address")
        val message = params["message"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: message")

        if (!recipientAddress.startsWith("0x") || recipientAddress.length != 42) {
            return SkillResult.Error(
                "Invalid recipient address: must be a 0x-prefixed Ethereum address (42 characters)"
            )
        }

        // Ensure identity is set up
        ensureIdentityReady()?.let { return it }

        // Add AI as a contact on first message
        ensureContactCreated()

        val messengerSdk = sdk
            ?: return SkillResult.Error("MessengerSDK not available")

        return try {
            val messageId = withContext(Dispatchers.IO) {
                messengerSdk.identity.sendMessage(recipientAddress, message)
            }
            Log.d(TAG, "Message sent to $recipientAddress, id: $messageId")

            SkillResult.Success(buildJsonObject {
                put("message_id", messageId)
                put("recipient", recipientAddress)
                put("status", "sent")
            }.toString())
        } catch (e: SdkException) {
            Log.e(TAG, "Failed to send message: ${e.message}", e)
            SkillResult.Error("Failed to send XMTP message: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error sending message: ${e.message}", e)
            SkillResult.Error("Failed to send message: ${e.message}")
        }
    }
}
